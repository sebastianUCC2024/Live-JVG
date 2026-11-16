package com.live_jgv.worker.infrastructure;

import org.springframework.boot.*;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;

@Component
public class StorageReadiness implements ApplicationRunner {
    private final com.live_jgv.worker.application.AtomicAuthorizationPort atomic;
    private final JdbcTemplate jdbc;private final RabbitListenerEndpointRegistry registry;private final boolean enabled;
    public StorageReadiness(JdbcTemplate jdbc,RabbitListenerEndpointRegistry registry,@Value("${worker.consumer.enabled:false}") boolean enabled){
        this(jdbc,registry,enabled,com.live_jgv.worker.application.AtomicAuthorizationPort.disabled());
    }
    @org.springframework.beans.factory.annotation.Autowired
    public StorageReadiness(JdbcTemplate jdbc,RabbitListenerEndpointRegistry registry,@Value("${worker.consumer.enabled:false}") boolean enabled,com.live_jgv.worker.application.AtomicAuthorizationPort atomic){
        this.jdbc=jdbc;this.registry=registry;this.enabled=enabled;this.atomic=atomic;
    }
    @Override public void run(ApplicationArguments args){
        for(String table:new String[]{"market_guard","command_inbox","operation","operation_transition","operation_evidence","message_audit","operation_authorization"})
            jdbc.queryForList("SELECT * FROM betting_worker."+table+" LIMIT 0");
        atomic.verifyInstallation();
        if(enabled){var container=registry.getListenerContainer("simulatedBettingWorker");if(container==null)throw new IllegalStateException("Worker listener absent");container.start();}
    }
}
