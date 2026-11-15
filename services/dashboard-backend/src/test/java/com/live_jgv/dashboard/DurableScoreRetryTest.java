package com.live_jgv.dashboard;

import com.live_jgv.dashboard.infrastructure.amqp.*;
import com.live_jgv.dashboard.domain.model.InvalidScoreUpdateException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.amqp.ImmediateRequeueAmqpException;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.support.ListenerExecutionFailedException;
import org.springframework.dao.TransientDataAccessResourceException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DurableScoreRetryTest {
    @Test void recoveryFailureStopsTheRegisteredDurableListener() {
        var registry = mock(org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry.class);
        var container = mock(org.springframework.amqp.rabbit.listener.MessageListenerContainer.class);
        when(registry.getListenerContainer("durableScores")).thenReturn(container);
        var recovery = new MessagingConfiguration().durableScoreRecovery(mock(RabbitTemplate.class), registry,
                "scores", Duration.ofMillis(1));
        assertThrows(ImmediateRequeueAmqpException.class, () -> recovery.recover(new Message(new byte[0]), new IllegalArgumentException()));
        verify(container).stop(any(Runnable.class));
        verify(container, never()).start();
    }
    @Test void actualDurableReceiverRejectsMalformedInputBeforeDatabaseWrites() {
        var jdbc = mock(org.springframework.jdbc.core.JdbcTemplate.class);
        when(jdbc.queryForMap(anyString(), eq("m"))).thenReturn(java.util.Map.of("home_team_id", "h", "away_team_id", "a"));
        var publisher = mock(com.live_jgv.dashboard.domain.port.out.MatchSnapshotPublisher.class);
        var projection = spy(new ScoreProjection(jdbc, mock(org.springframework.transaction.PlatformTransactionManager.class),
                new com.fasterxml.jackson.databind.ObjectMapper(), publisher, "m", "h", "a", "Home", "Away"));
        clearInvocations(jdbc);
        var recovery = mock(DurableScoreRecovery.class);
        var input = new Message("{}".getBytes(StandardCharsets.UTF_8));
        retrying(projection::receive, recovery).onMessage(input);
        verify(projection).receive(input);
        verify(recovery).recover(eq(input), any());
        verifyNoInteractions(jdbc, publisher);
    }
    public interface Invocation { void call(com.rabbitmq.client.Channel channel, Message message); }

    private MessageListener retrying(MessageListener listener, DurableScoreRecovery recovery) {
        Invocation target = (channel, message) -> listener.onMessage(message);
        var proxy = new ProxyFactory(target);
        proxy.addAdvice(new MessagingConfiguration().durableScoreRetry(recovery, 3, 1));
        var advised = (Invocation) proxy.getProxy();
        return message -> advised.call(null, message);
    }

    private RabbitTemplate confirming() {
        var template = mock(RabbitTemplate.class);
        doAnswer(call -> {
            call.getArgument(3, CorrelationData.class).getFuture().complete(new CorrelationData.Confirm(true, null));
            return null;
        }).when(template).send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));
        return template;
    }

    @Test void invalidJsonIsParkedOnceWithOriginalBytes() {
        var template = confirming();
        var recovery = new DurableScoreRecovery(template, "scores.dlx", Duration.ofSeconds(1), () -> fail("must not stop"));
        var count = new AtomicInteger();
        var original = new Message("not-json".getBytes(StandardCharsets.UTF_8));
        retrying(message -> {
            count.incrementAndGet();
            try { new ScoreUpdateMessageDecoder().decode(message.getBody()); }
            catch (RuntimeException failure) { throw new ListenerExecutionFailedException("wrapped", failure, message); }
        }, recovery).onMessage(original);
        assertEquals(1, count.get());
        var message = org.mockito.ArgumentCaptor.forClass(Message.class);
        verify(template).send(eq("scores.dlx"), eq("rejected"), message.capture(), any(CorrelationData.class));
        assertArrayEquals(original.getBody(), message.getValue().getBody());
        assertEquals(MessageDeliveryMode.PERSISTENT, message.getValue().getMessageProperties().getDeliveryMode());
        assertFalse(original.getMessageProperties().getHeaders().containsKey("failure_type"));
    }

    @Test void transientFailureSucceedsWithinBudgetWithoutParking() {
        var recovery = mock(DurableScoreRecovery.class);
        var count = new AtomicInteger();
        retrying(message -> {
            if (count.incrementAndGet() < 3) throw new TransientDataAccessResourceException("temporary");
        }, recovery).onMessage(new Message(new byte[0]));
        assertEquals(3, count.get());
        verifyNoInteractions(recovery);
    }

    @Test void exhaustedTransientFailureIsParkedAfterThreeAttempts() {
        var recovery = mock(DurableScoreRecovery.class);
        var count = new AtomicInteger();
        var input = new Message(new byte[0]);
        retrying(message -> {
            count.incrementAndGet();
            throw new TransientDataAccessResourceException("temporary");
        }, recovery).onMessage(input);
        assertEquals(3, count.get());
        verify(recovery).recover(eq(input), any());
    }

    @Test void identityConflictIsPermanent() {
        var recovery = mock(DurableScoreRecovery.class);
        var count = new AtomicInteger();
        retrying(message -> {
            count.incrementAndGet(); throw new InvalidScoreUpdateException("conflict");
        }, recovery).onMessage(new Message(new byte[0]));
        assertEquals(1, count.get());
        verify(recovery).recover(any(), any());
    }

    @Test void nackStopsConsumerAndRetainsInputForExplicitRecovery() {
        var template = mock(RabbitTemplate.class);
        doAnswer(call -> {
            call.getArgument(3, CorrelationData.class).getFuture().complete(new CorrelationData.Confirm(false, "nack"));
            return null;
        }).when(template).send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));
        var stop = mock(Runnable.class);
        assertThrows(ImmediateRequeueAmqpException.class, () ->
                new DurableScoreRecovery(template, "scores.dlx", Duration.ofSeconds(1), stop)
                        .recover(new Message(new byte[0]), new IllegalArgumentException()));
        verify(stop).run();
    }

    @Test void missingRouteStopsConsumerEvenWhenExchangeAcknowledges() {
        var template = mock(RabbitTemplate.class);
        doAnswer(call -> {
            var correlation = call.getArgument(3, CorrelationData.class);
            correlation.setReturned(new ReturnedMessage(call.getArgument(2), 312, "NO_ROUTE", "scores.dlx", "rejected"));
            correlation.getFuture().complete(new CorrelationData.Confirm(true, null));
            return null;
        }).when(template).send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));
        var stop = mock(Runnable.class);
        assertThrows(ImmediateRequeueAmqpException.class, () ->
                new DurableScoreRecovery(template, "scores.dlx", Duration.ofSeconds(1), stop)
                        .recover(new Message(new byte[0]), new IllegalArgumentException()));
        verify(stop).run();
    }

    @Test void confirmTimeoutRetainsInputAndStopsConsumer() {
        var stop = mock(Runnable.class);
        assertThrows(ImmediateRequeueAmqpException.class, () ->
                new DurableScoreRecovery(mock(RabbitTemplate.class), "scores.dlx", Duration.ofMillis(1), stop)
                        .recover(new Message(new byte[0]), new IllegalArgumentException()));
        verify(stop).run();
    }

    @Test void topologyKeepsExistingQueueArgumentsAndAddsDurableFailureQueue() throws Exception {
        var config = new MessagingConfiguration();
        assertTrue(config.scores("scores").getArguments().isEmpty());
        var topology = config.durableFailureTopology("scores");
        var dlq = topology.getDeclarablesByType(Queue.class).get(0);
        assertEquals("scores.dlq", dlq.getName()); assertTrue(dlq.isDurable());
        assertEquals("direct", topology.getDeclarablesByType(Exchange.class).get(0).getType());
        assertEquals("rejected", topology.getDeclarablesByType(Binding.class).get(0).getRoutingKey());
        var annotation = ScoreProjection.class.getMethod("receive", Message.class)
                .getAnnotation(org.springframework.amqp.rabbit.annotation.RabbitListener.class);
        assertEquals("durableScoresFactory", annotation.containerFactory());
    }
}
