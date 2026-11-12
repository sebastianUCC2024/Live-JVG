package com.live_jgv.matchstate;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.boot.autoconfigure.amqp.RabbitProperties;
import static org.junit.jupiter.api.Assertions.*;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {"spring.kafka.listener.auto-startup=false", "spring.rabbitmq.dynamic=false"})
class MatchStateServiceApplicationTests {

    @Autowired KafkaListenerEndpointRegistry registry;
    @Autowired ConcurrentKafkaListenerContainerFactory<String, String> matchEventsContainerFactory;
    @Autowired TopicExchange liveUpdatesExchange;
    @Autowired CachingConnectionFactory rabbitConnectionFactory;
    @Autowired RabbitProperties rabbitProperties;

    @Test
    void kafkaUsesRecordAcknowledgementsAndDisablesAutoCommitWithoutStartingBrokerConnection() {
        var container = registry.getListenerContainer("matchEventsListener");
        assertNotNull(container);
        assertFalse(container.isRunning());
        assertEquals("match-state-service", container.getGroupId());
        assertArrayEquals(new String[] {"match_events"}, container.getContainerProperties().getTopics());
        assertEquals(ContainerProperties.AckMode.RECORD, container.getContainerProperties().getAckMode());
        assertEquals(false, matchEventsContainerFactory.getConsumerFactory().getConfigurationProperties().get("enable.auto.commit"));
        assertEquals("earliest", matchEventsContainerFactory.getConsumerFactory().getConfigurationProperties().get("auto.offset.reset"));
    }

    @Test
    void rabbitUsesDurableTopicExchangeCorrelatedConfirmsReturnsAndMandatoryRouting() {
        assertEquals("live_updates", liveUpdatesExchange.getName());
        assertEquals("topic", liveUpdatesExchange.getType());
        assertTrue(liveUpdatesExchange.isDurable());
        assertTrue(rabbitConnectionFactory.isPublisherConfirms());
        assertTrue(rabbitConnectionFactory.isPublisherReturns());
        assertEquals(Boolean.TRUE, rabbitProperties.getTemplate().getMandatory());
    }

	@Test
	void contextLoads() {
	}

}
