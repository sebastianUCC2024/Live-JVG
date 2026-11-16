package com.live_jgv.notification.infrastructure.amqp;

import static org.junit.jupiter.api.Assertions.*;
import com.live_jgv.notification.infrastructure.config.RabbitConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.*;

class RabbitTopologyTest {
    @Test void queueIsIndependentDurableAndBoundToExplicitMatch() {
        var declarations=RabbitConfiguration.topology("live_updates","notification.score-updates","match-001");
        var queues=declarations.getDeclarablesByType(Queue.class);
        assertEquals(2,queues.size()); assertTrue(queues.stream().allMatch(Queue::isDurable));
        assertTrue(queues.stream().noneMatch(queue->queue.getName().equals("mvp-dashboard-scores")));
        var bindings=declarations.getDeclarablesByType(Binding.class);
        assertTrue(bindings.stream().anyMatch(binding->binding.getExchange().equals("live_updates")
                && binding.getDestination().equals("notification.score-updates") && binding.getRoutingKey().equals("score.match-001")));
        assertTrue(bindings.stream().anyMatch(binding->binding.getRoutingKey().equals("rejected")));
    }
    @Test void rejectsCompetingQueueMissingMatchAndWildcards() {
        assertThrows(IllegalArgumentException.class,()->RabbitConfiguration.topology("live_updates","mvp-dashboard-scores","m"));
        assertThrows(IllegalArgumentException.class,()->RabbitConfiguration.topology("live_updates","notification.scores",""));
        assertThrows(IllegalArgumentException.class,()->RabbitConfiguration.topology("live_updates","notification.scores","#"));
    }
    @Test void refusesPublisherWithoutConfirmedAndRoutedCustody() {
        var factory=org.mockito.Mockito.mock(org.springframework.amqp.rabbit.connection.ConnectionFactory.class);
        var config=new RabbitConfiguration();
        assertThrows(IllegalArgumentException.class,()->config.notificationRabbitTemplate(factory));
        org.mockito.Mockito.when(factory.isPublisherConfirms()).thenReturn(true);
        assertThrows(IllegalArgumentException.class,()->config.notificationRabbitTemplate(factory));
        org.mockito.Mockito.when(factory.isPublisherReturns()).thenReturn(true);
        assertDoesNotThrow(()->config.notificationRabbitTemplate(factory));
    }
}
