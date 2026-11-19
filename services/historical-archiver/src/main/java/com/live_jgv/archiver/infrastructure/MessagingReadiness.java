package com.live_jgv.archiver.infrastructure;

import org.springframework.boot.actuate.health.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.stereotype.Component;

/** Consulta el estado local del listener, sin conectarlo ni medir su retraso. */
@Component("messagingReadiness")
public final class MessagingReadiness implements HealthIndicator {
    private final KafkaListenerEndpointRegistry registry;private final boolean enabled;
    public MessagingReadiness(KafkaListenerEndpointRegistry registry,@Value("${archive.consumer.enabled:false}") boolean enabled){
        this.registry=registry;this.enabled=enabled;
    }
    @Override public Health health(){
        if(!enabled)return unavailable("DISABLED");
        var listener=registry.getListenerContainer("historicalArchiveListener");
        if(listener==null)return unavailable("LISTENER_MISSING");
        if(!listener.isRunning())return unavailable("STOPPED");
        if(listener.isPauseRequested()||listener.isContainerPaused())return unavailable("PAUSED");
        var assigned=listener.getAssignedPartitions();
        if(assigned==null||assigned.isEmpty())return unavailable("NO_ASSIGNMENT");
        return Health.up().withDetail("state","CONSUMING").withDetail("assigned_partitions",assigned.size())
                .withDetail("lag_verified",false).build();
    }
    private Health unavailable(String reason){return Health.status(Status.OUT_OF_SERVICE).withDetail("state",reason).build();}
}
