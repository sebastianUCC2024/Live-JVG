package com.live_jgv.matchstate.infrastructure.config;

import com.live_jgv.matchstate.application.service.ProcessAndPublishMatchEvent;
import com.live_jgv.matchstate.infrastructure.jdbc.JdbcMatchStateStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import java.time.Duration;

/** Mantiene Kafka detenido hasta recuperar las publicaciones pendientes. */
public final class DurableRecoveryRunner implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(DurableRecoveryRunner.class);
    private final JdbcMatchStateStore store;
    private final ProcessAndPublishMatchEvent processor;
    private final KafkaListenerEndpointRegistry registry;
    private final ConfigurableApplicationContext context;
    private final boolean startListener;
    private final long retryMillis;

    public DurableRecoveryRunner(JdbcMatchStateStore store, ProcessAndPublishMatchEvent processor,
            KafkaListenerEndpointRegistry registry, ConfigurableApplicationContext context,
            boolean startListener, Duration retryInterval) {
        this.store = store;
        this.processor = processor;
        this.registry = registry;
        this.context = context;
        this.startListener = startListener;
        this.retryMillis = retryInterval.toMillis();
        if (retryMillis < 1) throw new IllegalArgumentException("Recovery retry interval must be positive");
    }

    @Override
    public void run(ApplicationArguments args) throws InterruptedException {
        AvailabilityChangeEvent.publish(context, ReadinessState.REFUSING_TRAFFIC);
        while (context.isActive()) {
            try {
                for (var id : store.matchIds()) processor.recoverPendingMatch(id);
                if (startListener) registry.start();
                log.info("Durable outbox recovered; Kafka startup enabled={}", startListener);
                return;
            } catch (RuntimeException failure) {
                // Omite credenciales, configuración de conexión y payloads en los logs.
                log.warn("Durable startup recovery pending; failureType={}", failure.getClass().getName());
                Thread.sleep(retryMillis);
            }
        }
        throw new IllegalStateException("Context closed before durable recovery completed");
    }
}
