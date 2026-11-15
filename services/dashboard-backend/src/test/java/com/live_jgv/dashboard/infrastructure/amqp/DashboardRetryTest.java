package com.live_jgv.dashboard.infrastructure.amqp;

import com.live_jgv.dashboard.domain.model.InvalidScoreUpdateException;
import com.live_jgv.dashboard.domain.port.in.ProcessScoreUpdate;
import com.live_jgv.dashboard.domain.port.out.DashboardUpdatePublisher;
import com.live_jgv.dashboard.application.service.ProcessScoreUpdateService;
import com.live_jgv.dashboard.infrastructure.memory.InMemoryDashboardProjection;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.MessageListener;
import org.springframework.amqp.core.Message;
import com.rabbitmq.client.Channel;
import org.springframework.amqp.rabbit.support.ListenerExecutionFailedException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;

class DashboardRetryTest {
    private MessageListener retrying(MessageListener target) {
        // AMQP's container advice wraps an invocation with (Channel, Message).
        ContainerInvocation invocation = (channel, message) -> target.onMessage(message);
        var proxy = new ProxyFactory(invocation);
        proxy.addAdvice(new DashboardRabbitConfiguration().dashboardRetryAdvice(3, 1));
        var advised = (ContainerInvocation) proxy.getProxy();
        return message -> advised.invoke(null, message);
    }

    public interface ContainerInvocation {
        void invoke(Channel channel, Message message);
    }

    @Test
    void retriesActualListenerAndUseCaseThenUpdatesProjection() {
        var projection = new InMemoryDashboardProjection(10);
        var output = mock(DashboardUpdatePublisher.class);
        doThrow(new IllegalStateException("temporary"))
                .doAnswer(invocation -> { projection.publish(invocation.getArgument(0)); return null; })
                .when(output).publish(any());
        var listener = new ScoreUpdateListener(new ScoreUpdateMessageDecoder(), new ProcessScoreUpdateService(output));
        retrying(listener::onMessage).onMessage(ScoreUpdateListenerTest.message(ScoreUpdateListenerTest.JSON));
        verify(output, times(2)).publish(any());
        assertEquals(2, projection.findByMatchId("match-1").orElseThrow().homeScore());
    }

    @Test
    void malformedJsonTraversesRealDecoderOnlyOnceAndNeverCallsUseCase() {
        var processor = mock(ProcessScoreUpdate.class);
        var listener = spy(new ScoreUpdateListener(new ScoreUpdateMessageDecoder(), processor));
        var message = ScoreUpdateListenerTest.message("malformed");
        assertThrows(AmqpRejectAndDontRequeueException.class, () -> retrying(listener::onMessage).onMessage(message));
        verify(listener, times(1)).onMessage(message);
        verifyNoInteractions(processor);
    }

    @Test
    void permanentUseCaseFailureIsRejectedAfterThreeInvocations() {
        var processor = mock(ProcessScoreUpdate.class);
        doThrow(new IllegalStateException("unavailable")).when(processor).process(any());
        var listener = new ScoreUpdateListener(new ScoreUpdateMessageDecoder(), processor);
        assertThrows(AmqpRejectAndDontRequeueException.class, () -> retrying(listener::onMessage)
                .onMessage(ScoreUpdateListenerTest.message(ScoreUpdateListenerTest.JSON)));
        verify(processor, times(3)).process(any());
    }

    @Test
    void transientErrorSucceedsOnThirdAttempt() {
        var attempts = new AtomicInteger();
        var listener = retrying(message -> {
            if (attempts.incrementAndGet() < 3) { throw new IllegalStateException("temporary"); }
        });
        listener.onMessage(ScoreUpdateListenerTest.message(ScoreUpdateListenerTest.JSON));
        assertEquals(3, attempts.get());
    }

    @Test
    void exhaustedErrorIsRejectedAfterExactlyThreeAttempts() {
        var attempts = new AtomicInteger();
        var listener = retrying(message -> {
            attempts.incrementAndGet();
            throw new ListenerExecutionFailedException("wrapped", new IllegalStateException("temporary"), message);
        });
        assertThrows(AmqpRejectAndDontRequeueException.class, () ->
                listener.onMessage(ScoreUpdateListenerTest.message(ScoreUpdateListenerTest.JSON)));
        assertEquals(3, attempts.get());
    }

    @Test
    void wrappedInvalidJsonIsRejectedWithoutRetry() {
        var attempts = new AtomicInteger();
        var listener = retrying(message -> {
            attempts.incrementAndGet();
            throw new ListenerExecutionFailedException("wrapped", new InvalidScoreUpdateMessageException(), message);
        });
        assertThrows(AmqpRejectAndDontRequeueException.class, () -> listener.onMessage(ScoreUpdateListenerTest.message("bad")));
        assertEquals(1, attempts.get());
    }

    @Test
    void conflictingIdentityIsRejectedWithoutRetry() {
        var attempts = new AtomicInteger();
        var listener = retrying(message -> {
            attempts.incrementAndGet();
            throw new InvalidScoreUpdateException("conflict");
        });
        assertThrows(AmqpRejectAndDontRequeueException.class, () ->
                listener.onMessage(ScoreUpdateListenerTest.message(ScoreUpdateListenerTest.JSON)));
        assertEquals(1, attempts.get());
    }

    @Test
    void invalidRetrySettingsFailFast() {
        assertThrows(IllegalArgumentException.class, () -> new DashboardRabbitConfiguration().dashboardRetryAdvice(0, 1));
        assertThrows(IllegalArgumentException.class, () -> new DashboardRabbitConfiguration().dashboardRetryAdvice(3, 0));
    }
}
