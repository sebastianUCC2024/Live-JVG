package com.live_jgv.notification.infrastructure.config;

import static org.mockito.Mockito.*;
import com.live_jgv.notification.infrastructure.health.ConsumerReadiness;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;

class HealthConfigurationTest {
    @Test void consumerConnectionListenerIsNotAttachedToPublisherOnlyConnections() {
        var input=mock(ConnectionFactory.class); var publisher=mock(ConnectionFactory.class);
        when(input.getPublisherConnectionFactory()).thenReturn(publisher);
        var monitor=new ConsumerReadiness("notification.scores");
        new RabbitConfiguration().notificationAdmin(input,monitor);
        verify(input).addConnectionListener(monitor);
        verify(publisher).removeConnectionListener(monitor);
        verify(input,never()).createConnection(); verify(publisher,never()).createConnection();
    }
}
