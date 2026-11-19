package com.live_jgv.betting.infrastructure;

import org.springframework.boot.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.stereotype.Component;

@Component
public class StorageReadiness implements ApplicationRunner {
    private final JdbcTemplate jdbc;private final KafkaListenerEndpointRegistry registry;private final boolean enabled;
    public StorageReadiness(JdbcTemplate jdbc,KafkaListenerEndpointRegistry registry,@Value("${betting.consumer.enabled:false}") boolean enabled) {
        this.jdbc=jdbc;this.registry=registry;this.enabled=enabled;
    }
    @Override public void run(ApplicationArguments args) {
        for(String table:new String[]{"market_state","processed_event","pending_review","input_receipt","closure_command","transition_history","suspension_outbox","authorization_authority","authorization_lease"})
            jdbc.queryForList("SELECT * FROM betting_control."+table+" LIMIT 0");
        if(enabled) {
            var container=registry.getListenerContainer("bettingSuspensionListener");
            if(container==null) throw new IllegalStateException("Suspension listener not registered");
            container.start();
        }
    }
}
