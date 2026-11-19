package com.live_jgv.notification.infrastructure.health;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.sql.Connection;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.springframework.amqp.rabbit.listener.*;
import org.springframework.boot.health.contributor.Status;

class NotificationReadinessTest {
    DataSource source; Connection connection; SimpleMessageListenerContainer container; ConsumerReadiness consumer;
    @BeforeEach void setup() throws Exception {
        source=mock(DataSource.class); connection=mock(Connection.class); container=mock(SimpleMessageListenerContainer.class);
        consumer=new ConsumerReadiness("notification.scores");
        when(source.getConnection()).thenReturn(connection); when(connection.isValid(2)).thenReturn(true);
        when(container.isRunning()).thenReturn(true); when(container.isActive()).thenReturn(true); when(container.getActiveConsumerCount()).thenReturn(1);
    }
    NotificationReadinessHealthIndicator indicator(boolean storage,boolean messaging,boolean started) {
        var indicator=new NotificationReadinessHealthIndicator(storage,messaging,()->source,()->container,consumer);
        if (started) indicator.applicationReady(null);
        return indicator;
    }
    void consumeOk() { consumer.onApplicationEvent(new ConsumeOkEvent(this,"notification.scores","tag")); }
    @Test void disabledDependenciesNeverReportReadyAndNeverConnect() {
        var health=indicator(false,false,true).health();
        assertEquals(Status.OUT_OF_SERVICE,health.getStatus()); assertEquals(false,health.getDetails().get("ready"));
        assertEquals(true,health.getDetails().get("service_started")); verifyNoInteractions(source,container);
    }
    @Test void runningContainerWithoutConsumeOkIsNotOperational() {
        var health=indicator(true,true,true).health();
        assertEquals(Status.DOWN,health.getStatus()); assertEquals(false,health.getDetails().get("consumer_operational"));
    }
    @Test void allRequiredDependenciesAndStartupAreNecessaryForUp() {
        consumeOk();
        assertEquals(Status.OUT_OF_SERVICE,indicator(true,true,false).health().getStatus());
        assertEquals(Status.UP,indicator(true,true,true).health().getStatus());
        assertEquals(Status.OUT_OF_SERVICE,indicator(true,false,true).health().getStatus());
        assertEquals(Status.OUT_OF_SERVICE,indicator(false,true,true).health().getStatus());
    }
    @Test void postgresFailureOrInvalidConnectionMakesReadinessDownWithoutDetailsLeak() throws Exception {
        consumeOk(); when(connection.isValid(2)).thenReturn(false);
        assertEquals(Status.DOWN,indicator(true,true,true).health().getStatus());
        when(source.getConnection()).thenThrow(new java.sql.SQLException("secret-password"));
        var health=indicator(true,true,true).health();
        assertEquals(Status.DOWN,health.getStatus()); assertFalse(health.getDetails().toString().contains("secret"));
    }
    @Test void disconnectedStoppedOrZeroConsumerContainerIsNotReady() {
        consumeOk(); when(container.isRunning()).thenReturn(false);
        assertEquals(Status.DOWN,indicator(true,true,true).health().getStatus());
        when(container.isRunning()).thenReturn(true); when(container.getActiveConsumerCount()).thenReturn(0);
        assertEquals(Status.DOWN,indicator(true,true,true).health().getStatus());
    }
    @Test void missingBeansNeverProduceUp() {
        consumeOk();
        var health=new NotificationReadinessHealthIndicator(true,true,()->null,()->null,consumer);
        health.applicationReady(null); assertEquals(Status.DOWN,health.health().getStatus());
    }
    @Test void connectionLossAndRecoveryRequireFreshConsumeOk() {
        consumeOk(); assertTrue(consumer.accepted());
        consumer.onClose(null); assertFalse(consumer.accepted());
        assertEquals(Status.DOWN,indicator(true,true,true).health().getStatus());
        consumeOk(); assertEquals(Status.UP,indicator(true,true,true).health().getStatus());
    }
    @Test void foreignQueueDoesNotMarkConsumerOperationalAndFailureResetsIt() {
        consumer.onApplicationEvent(new ConsumeOkEvent(this,"other.queue","other-tag")); assertFalse(consumer.accepted());
        consumeOk(); assertTrue(consumer.accepted());
        consumer.onApplicationEvent(new ListenerContainerConsumerFailedEvent(this,"failed",new IllegalStateException(),false));
        assertFalse(consumer.accepted());
    }
    @Test void shutdownAndExplicitDlqStopInvalidateReadiness() {
        consumeOk(); var indicator=indicator(true,true,true); consumer.invalidate();
        assertEquals(Status.DOWN,indicator.health().getStatus());
        consumeOk(); indicator.applicationClosed(null);
        assertEquals(Status.OUT_OF_SERVICE,indicator.health().getStatus()); assertFalse(consumer.accepted());
    }
    @Test void stoppedConsumerAndConnectionShutdownRequireFreshAcceptance() {
        consumeOk(); consumer.onApplicationEvent(new AsyncConsumerStoppedEvent(this,new Object()));
        assertFalse(consumer.accepted()); consumeOk(); consumer.onShutDown(null); assertFalse(consumer.accepted());
    }
}
