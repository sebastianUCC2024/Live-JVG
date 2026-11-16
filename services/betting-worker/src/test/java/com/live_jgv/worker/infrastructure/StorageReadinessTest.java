package com.live_jgv.worker.infrastructure;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.amqp.rabbit.listener.*;
import org.springframework.dao.DataAccessResourceFailureException;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

class StorageReadinessTest {
    @Test void schemaChecksPrecedeExplicitConsumerStart(){
        var jdbc=mock(JdbcTemplate.class);var registry=mock(RabbitListenerEndpointRegistry.class);var container=mock(MessageListenerContainer.class);
        when(registry.getListenerContainer("simulatedBettingWorker")).thenReturn(container);new StorageReadiness(jdbc,registry,true).run(null);
        var order=inOrder(jdbc,container);order.verify(jdbc,times(7)).queryForList(anyString());order.verify(container).start();
    }
    @Test void disabledConsumerOrMissingSchemaNeverStartsRabbit(){
        var jdbc=mock(JdbcTemplate.class);var registry=mock(RabbitListenerEndpointRegistry.class);
        new StorageReadiness(jdbc,registry,false).run(null);verifyNoInteractions(registry);
        when(jdbc.queryForList(anyString())).thenThrow(new DataAccessResourceFailureException("no schema"));
        assertThrows(DataAccessResourceFailureException.class,()->new StorageReadiness(jdbc,registry,true).run(null));verifyNoInteractions(registry);
    }
}
