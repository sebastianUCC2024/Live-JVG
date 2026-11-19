package com.live_jgv.archiver.infrastructure;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.stereotype.Component;

/** Verifica el esquema preparado antes de consumir; no ejecuta migraciones automáticas. */
@Component
public class StorageReadiness implements ApplicationRunner {
    private final JdbcTemplate jdbc;
    private final KafkaListenerEndpointRegistry registry;
    private final boolean enabled;
    public StorageReadiness(JdbcTemplate jdbc, KafkaListenerEndpointRegistry registry,
                            @Value("${archive.consumer.enabled:false}") boolean enabled) {
        this.jdbc=jdbc; this.registry=registry; this.enabled=enabled;
    }
    @Override public void run(ApplicationArguments args) {
        jdbc.queryForList("SELECT event_id,canonical_payload FROM archiver.archived_event LIMIT 0");
        jdbc.queryForList("SELECT source_id,outcome,match_id FROM archiver.input_receipt LIMIT 0");
        if (enabled) {
            var listener = registry.getListenerContainer("historicalArchiveListener");
            if (listener == null) throw new IllegalStateException("Archive listener not registered");
            listener.start();
        }
    }
}
