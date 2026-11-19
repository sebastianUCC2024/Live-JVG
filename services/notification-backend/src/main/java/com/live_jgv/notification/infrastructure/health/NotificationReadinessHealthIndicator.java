package com.live_jgv.notification.infrastructure.health;

import java.util.function.Supplier;
import javax.sql.DataSource;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.health.contributor.*;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;

/** Marca disponibilidad solo si el almacenamiento y el consumidor están preparados. */
public final class NotificationReadinessHealthIndicator implements HealthIndicator {
    private final boolean storageEnabled;
    private final boolean consumerEnabled;
    private final Supplier<DataSource> dataSource;
    private final Supplier<SimpleMessageListenerContainer> container;
    private final ConsumerReadiness consumer;
    private volatile boolean started;
    public NotificationReadinessHealthIndicator(boolean storageEnabled, boolean consumerEnabled,
            Supplier<DataSource> dataSource, Supplier<SimpleMessageListenerContainer> container, ConsumerReadiness consumer) {
        this.storageEnabled=storageEnabled; this.consumerEnabled=consumerEnabled;
        this.dataSource=dataSource; this.container=container; this.consumer=consumer;
    }
    @EventListener public void applicationReady(ApplicationReadyEvent event) { started=true; }
    @EventListener public void applicationClosed(ContextClosedEvent event) { started=false; consumer.invalidate(); }
    @Override public Health health() {
        boolean postgres=false;
        if (storageEnabled) {
            try {
                var source=dataSource.get();
                if (source!=null) try (var connection=source.getConnection()) { postgres=connection.isValid(2); }
            } catch (Exception unavailable) { postgres=false; }
        }
        boolean registered=false, operational=false;
        if (consumerEnabled) {
            try {
                var listener=container.get();
                registered=listener!=null;
                operational=registered && listener.isRunning() && listener.isActive()
                        && listener.getActiveConsumerCount()>0 && consumer.accepted();
            } catch (RuntimeException unavailable) { operational=false; }
        }
        boolean ready=started && storageEnabled && postgres && consumerEnabled && operational;
        Status status=ready ? Status.UP : !started || !storageEnabled || !consumerEnabled ? Status.OUT_OF_SERVICE : Status.DOWN;
        return Health.status(status).withDetail("service_started",started).withDetail("storage_enabled",storageEnabled)
                .withDetail("postgres_available",postgres).withDetail("consumer_enabled",consumerEnabled)
                .withDetail("consumer_registered",registered).withDetail("consumer_operational",operational).withDetail("ready",ready).build();
    }
}
