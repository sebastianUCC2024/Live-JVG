package com.live_jgv.notification.infrastructure.config;

import com.live_jgv.notification.infrastructure.health.*;
import javax.sql.DataSource;
import org.springframework.amqp.rabbit.listener.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;

@Configuration(proxyBeanMethods=false)
public class HealthConfiguration {
    @Bean ConsumerReadiness consumerReadiness(@Value("${notification.messaging.queue}") String queue) { return new ConsumerReadiness(queue); }
    @Bean NotificationReadinessHealthIndicator notificationReadinessHealthIndicator(
            @Value("${notification.storage.enabled:false}") boolean storage,
            @Value("${notification.messaging.enabled:false}") boolean messaging,
            ObjectProvider<DataSource> source, ObjectProvider<RabbitListenerEndpointRegistry> registry, ConsumerReadiness consumer) {
        return new NotificationReadinessHealthIndicator(storage,messaging,source::getIfAvailable,()->{
            var endpoints=registry.getIfAvailable();
            var listener=endpoints==null ? null : endpoints.getListenerContainer("notificationScores");
            return listener instanceof SimpleMessageListenerContainer simple ? simple : null;
        },consumer);
    }
}
