package com.live_jgv.archiver.infrastructure;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.dao.DataAccessResourceFailureException;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

class StorageReadinessTest {
    @Test void schemaMustBeVerifiedBeforeConsumerCanStart() throws Exception {
        var jdbc=mock(JdbcTemplate.class);var registry=mock(KafkaListenerEndpointRegistry.class);var container=mock(MessageListenerContainer.class);
        when(registry.getListenerContainer("historicalArchiveListener")).thenReturn(container);
        new StorageReadiness(jdbc,registry,true).run(null);
        var order=inOrder(jdbc,container);order.verify(jdbc).queryForList(contains("archived_event"));
        order.verify(jdbc).queryForList(contains("input_receipt"));order.verify(container).start();
    }
    @Test void missingSchemaDoesNotStartKafkaAndNeverRunsMigration() {
        var jdbc=mock(JdbcTemplate.class);var registry=mock(KafkaListenerEndpointRegistry.class);
        when(jdbc.queryForList(anyString())).thenThrow(new DataAccessResourceFailureException("missing schema"));
        assertThrows(DataAccessResourceFailureException.class,()->new StorageReadiness(jdbc,registry,true).run(null));verifyNoInteractions(registry);
    }
}
