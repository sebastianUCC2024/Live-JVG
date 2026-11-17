package com.live_jgv.worker.infrastructure;

import org.springframework.boot.actuate.health.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.amqp.rabbit.listener.*;
import org.springframework.stereotype.Component;

/** Transport readiness is not permission to accept an operation. No broker calls or auto-recovery. */
@Component("messagingReadiness")
public final class MessagingReadiness implements HealthIndicator {
    private final RabbitListenerEndpointRegistry registry;private final boolean enabled;
    public MessagingReadiness(RabbitListenerEndpointRegistry registry,@Value("${worker.consumer.enabled:false}") boolean enabled){
        this.registry=registry;this.enabled=enabled;
    }
    @Override public Health health(){
        if(!enabled)return unavailable("DISABLED");
        var listener=registry.getListenerContainer("simulatedBettingWorker");
        if(listener==null)return unavailable("LISTENER_MISSING");
        if(!listener.isRunning())return unavailable("STOPPED");
        if(!(listener instanceof SimpleMessageListenerContainer simple))return unavailable("UNVERIFIED_CONTAINER");
        int consumers=simple.getActiveConsumerCount();
        if(consumers<1)return unavailable("NO_ACTIVE_CONSUMER");
        return Health.up().withDetail("state","CONSUMING").withDetail("active_consumers",consumers)
                .withDetail("betting_authorized_by_health",false).build();
    }
    private Health unavailable(String reason){return Health.status(Status.OUT_OF_SERVICE).withDetail("state",reason).build();}
}
