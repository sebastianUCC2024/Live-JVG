package com.live_jgv.betting.infrastructure;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.dao.DataAccessResourceFailureException;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

class StorageReadinessTest {
    @Test void allTablesMustBePresentBeforeExplicitConsumerStart() {
        var jdbc=mock(JdbcTemplate.class);var registry=mock(KafkaListenerEndpointRegistry.class);var container=mock(MessageListenerContainer.class);
        when(registry.getListenerContainer("bettingSuspensionListener")).thenReturn(container);
        new StorageReadiness(jdbc,registry,true).run(null);
        var order=inOrder(jdbc,container);order.verify(jdbc,times(9)).queryForList(anyString());order.verify(container).start();
    }
    @Test void failureOrDisabledFlagNeverStartsConsumer() {
        var jdbc=mock(JdbcTemplate.class);var registry=mock(KafkaListenerEndpointRegistry.class);
        new StorageReadiness(jdbc,registry,false).run(null);verifyNoInteractions(registry);
        when(jdbc.queryForList(anyString())).thenThrow(new DataAccessResourceFailureException("missing schema"));
        assertThrows(DataAccessResourceFailureException.class,()->new StorageReadiness(jdbc,registry,true).run(null));verifyNoInteractions(registry);
    }
}
