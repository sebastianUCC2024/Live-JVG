package com.live_jgv.notification.infrastructure.amqp;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.live_jgv.notification.Fixtures;
import com.live_jgv.notification.application.*;
import com.live_jgv.notification.domain.*;
import com.live_jgv.notification.infrastructure.json.ScoreUpdateCodec;
import com.rabbitmq.client.Channel;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.*;
import org.junit.jupiter.api.*;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.dao.DataAccessResourceFailureException;

class ScoreUpdateListenerTest {
    NotificationStore store; RabbitTemplate template; Channel channel; Runnable stop;
    ScoreUpdateListener listener; ScoreUpdateListener.Sleeper sleeper;
    @BeforeEach void setup() {
        store=mock(NotificationStore.class); template=mock(RabbitTemplate.class); channel=mock(Channel.class);
        stop=mock(Runnable.class); sleeper=mock(ScoreUpdateListener.Sleeper.class);
        listener=new ScoreUpdateListener(new ProcessScoreUpdateService(store,new NotificationPolicy(),Clock.systemUTC()),
                new ScoreUpdateCodec(),new ConfirmedDlqRecovery(template,"notification.score-updates.dlx",Duration.ofMillis(20)),stop,sleeper,3,1);
        confirm(true,false);
    }
    private void confirm(boolean ack,boolean returned) {
        doAnswer(call->{
            CorrelationData correlation=call.getArgument(3);
            if (returned) correlation.setReturned(new ReturnedMessage(call.getArgument(2),312,"NO_ROUTE","notification.score-updates.dlx","rejected"));
            correlation.getFuture().complete(new CorrelationData.Confirm(ack,null)); return null;
        }).when(template).send(anyString(),anyString(),any(Message.class),any(CorrelationData.class));
    }
    private Message message(String body) {
        var properties=new MessageProperties(); properties.setDeliveryTag(42);
        properties.setReceivedExchange("live_updates"); properties.setReceivedRoutingKey("score.match-001");
        return new Message(body.getBytes(StandardCharsets.UTF_8),properties);
    }
    @Test void ackFollowsCompletedPersistence() throws Exception {
        listener.receive(message(Fixtures.JSON),channel);
        var order=inOrder(store,channel); order.verify(store).accept(any(),any()); order.verify(channel).basicAck(42,false);
        verifyNoInteractions(template,stop,sleeper);
    }
    @Test void duplicateIsAcknowledgedWithoutAnotherVisibleEffect() throws Exception {
        when(store.accept(any(),any())).thenReturn(NotificationStore.Result.DUPLICATE);
        listener.receive(message(Fixtures.JSON),channel);
        verify(channel).basicAck(42,false); verifyNoInteractions(template);
    }
    @Test void transientErrorRetriesThenAcknowledges() throws Exception {
        when(store.accept(any(),any())).thenThrow(new DataAccessResourceFailureException("temporary"))
                .thenReturn(NotificationStore.Result.CREATED);
        listener.receive(message(Fixtures.JSON),channel);
        verify(store,times(2)).accept(any(),any()); verify(sleeper).sleep(1); verify(channel).basicAck(42,false);
        verifyNoInteractions(template);
    }
    @Test void exhaustedRetriesTransferBeforeAck() throws Exception {
        when(store.accept(any(),any())).thenThrow(new DataAccessResourceFailureException("temporary"));
        listener.receive(message(Fixtures.JSON),channel);
        verify(store,times(3)).accept(any(),any()); verify(sleeper).sleep(1); verify(sleeper).sleep(2);
        var order=inOrder(template,channel);
        order.verify(template).send(eq("notification.score-updates.dlx"),eq("rejected"),any(Message.class),any(CorrelationData.class));
        order.verify(channel).basicAck(42,false);
    }
    @Test void invalidInputIsPreservedInConfirmedDlqAndNextInputWorks() throws Exception {
        var invalid=message("broken");
        listener.receive(invalid,channel); listener.receive(message(Fixtures.JSON),channel);
        var captured=org.mockito.ArgumentCaptor.forClass(Message.class);
        verify(template).send(eq("notification.score-updates.dlx"),eq("rejected"),captured.capture(),any(CorrelationData.class));
        assertArrayEquals(invalid.getBody(),captured.getValue().getBody());
        assertEquals(MessageDeliveryMode.PERSISTENT,captured.getValue().getMessageProperties().getDeliveryMode());
        verify(store).accept(any(),any()); verify(channel,times(2)).basicAck(42,false); verifyNoInteractions(stop,sleeper);
    }
    @Test void identityConflictIsNotRetried() throws Exception {
        when(store.accept(any(),any())).thenThrow(new IdentityConflictException());
        listener.receive(message(Fixtures.JSON),channel);
        verify(store).accept(any(),any()); verifyNoInteractions(sleeper); verify(channel).basicAck(42,false);
    }
    @Test void nackFromDlqNeverAcknowledgesOriginalAndStopsConsumer() throws Exception {
        confirm(false,false); listener.receive(message("broken"),channel);
        verify(channel,never()).basicAck(anyLong(),anyBoolean()); verify(channel).basicNack(42,false,true); verify(stop).run();
    }
    @Test void returnedDlqPublicationIsNotSuccessfulCustody() throws Exception {
        confirm(true,true); listener.receive(message("broken"),channel);
        verify(channel,never()).basicAck(anyLong(),anyBoolean()); verify(channel).basicNack(42,false,true); verify(stop).run();
    }
    @Test void synchronousDlqFailureRetainsOriginalAndStops() throws Exception {
        doThrow(new IllegalStateException("connection unavailable")).when(template).send(anyString(),anyString(),any(Message.class),any(CorrelationData.class));
        listener.receive(message("broken"),channel);
        verify(channel).basicNack(42,false,true); verify(stop).run(); verify(channel,never()).basicAck(anyLong(),anyBoolean());
    }
    @Test void nackIoFailureStillStopsAndNeverAcknowledges() throws Exception {
        confirm(false,false); doThrow(new IOException("closed")).when(channel).basicNack(42,false,true);
        assertThrows(IOException.class,()->listener.receive(message("broken"),channel));
        verify(stop).run(); verify(channel,never()).basicAck(anyLong(),anyBoolean());
    }
    @Test void confirmTimeoutRetainsOriginal() throws Exception {
        doNothing().when(template).send(anyString(),anyString(),any(Message.class),any(CorrelationData.class));
        listener.receive(message("broken"),channel);
        verify(channel,never()).basicAck(anyLong(),anyBoolean()); verify(channel).basicNack(42,false,true); verify(stop).run();
    }
    @Test void originalIsNotAcknowledgedWhileConfirmIsPending() throws Exception {
        var sent=new java.util.concurrent.CompletableFuture<CorrelationData>();
        doAnswer(call->{sent.complete(call.getArgument(3)); return null;}).when(template)
                .send(anyString(),anyString(),any(Message.class),any(CorrelationData.class));
        var waitingListener=new ScoreUpdateListener(new ProcessScoreUpdateService(store,new NotificationPolicy(),Clock.systemUTC()),
                new ScoreUpdateCodec(),new ConfirmedDlqRecovery(template,"notification.score-updates.dlx",Duration.ofSeconds(2)),stop,sleeper,3,1);
        var worker=java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            var delivered=worker.submit(()->{waitingListener.receive(message("broken"),channel); return null;});
            var correlation=sent.get(1,java.util.concurrent.TimeUnit.SECONDS);
            verify(channel,never()).basicAck(anyLong(),anyBoolean());
            correlation.getFuture().complete(new CorrelationData.Confirm(true,null));
            delivered.get(1,java.util.concurrent.TimeUnit.SECONDS);
            verify(channel).basicAck(42,false);
        } finally { worker.shutdownNow(); }
    }
    @Test void ackIoFailureDoesNotRetryBusinessProcessing() throws Exception {
        doThrow(new IOException("channel closed")).when(channel).basicAck(42,false);
        assertThrows(IOException.class,()->listener.receive(message(Fixtures.JSON),channel));
        verify(store).accept(any(),any()); verifyNoInteractions(template,sleeper);
    }
    @Test void interruptedBackoffRetainsMessageInsteadOfSendingToDlq() throws Exception {
        when(store.accept(any(),any())).thenThrow(new DataAccessResourceFailureException("temporary"));
        doThrow(new InterruptedException()).when(sleeper).sleep(anyLong());
        try {
            listener.receive(message(Fixtures.JSON),channel);
            assertTrue(Thread.currentThread().isInterrupted()); verify(channel).basicNack(42,false,true);
            verify(stop).run(); verifyNoInteractions(template);
        } finally { Thread.interrupted(); }
    }
    @Test void routingAndMessageIdentityMustAgreeWithBody() throws Exception {
        var input=message(Fixtures.JSON); input.getMessageProperties().setMessageId("other-id");
        listener.receive(input,channel); verifyNoInteractions(store); verify(channel).basicAck(42,false);
    }
}
