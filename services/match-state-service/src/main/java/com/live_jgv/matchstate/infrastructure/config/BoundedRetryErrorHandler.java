package com.live_jgv.matchstate.infrastructure.config;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.*;

/** Realiza tres reintentos y pausa el contenedor; nunca descarta el registro. */
public final class BoundedRetryErrorHandler extends DefaultErrorHandler implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(BoundedRetryErrorHandler.class);
    private final ThreadLocal<MessageListenerContainer> current = new ThreadLocal<>();
    private final Set<MessageListenerContainer> paused = ConcurrentHashMap.newKeySet();
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> {
        var t = new Thread(r, "match-input-retry-cooldown"); t.setDaemon(true); return t;
    });

    public BoundedRetryErrorHandler(Duration interval, Duration cooldown) {
        super((record, failure) -> { throw new IllegalStateException("Input remains uncommitted", failure); }, backoff(interval));
        if (cooldown.toMillis() < 1) throw new IllegalArgumentException("Cooldown must be positive");
        setClassifications(Map.of(Exception.class, true), true);
        setResetStateOnExceptionChange(false);
        setAckAfterHandle(false); setCommitRecovered(false);
        setLogLevel(org.springframework.kafka.KafkaException.Level.TRACE);
        setRetryListeners((record, failure, attempt) -> {
            log.warn("Input retry; partition={}, offset={}, attempt={}, failureType={}", record.partition(), record.offset(), attempt, failure.getClass().getName());
            var container = current.get();
            if (attempt >= 4 && container != null && paused.add(container)) {
                container.pause();
                log.warn("Input retry budget exhausted; pausing consumer for {} ms; offset retained", cooldown.toMillis());
                timer.schedule(() -> {
                    try { if (container.isRunning()) container.resume(); }
                    finally { paused.remove(container); }
                }, cooldown.toMillis(), TimeUnit.MILLISECONDS);
            }
        });
    }
    private static ExponentialBackOffWithMaxRetries backoff(Duration interval) {
        if (interval.toMillis() < 1) throw new IllegalArgumentException("Retry interval must be positive");
        var backoff = new ExponentialBackOffWithMaxRetries(3);
        backoff.setInitialInterval(interval.toMillis()); backoff.setMultiplier(2);
        backoff.setMaxInterval(Math.multiplyExact(interval.toMillis(), 4)); return backoff;
    }
    @Override
    public void handleRemaining(Exception failure, List<ConsumerRecord<?, ?>> records, Consumer<?, ?> consumer, MessageListenerContainer container) {
        current.set(container);
        try { super.handleRemaining(failure, records, consumer, container); }
        finally { current.remove(); }
    }
    @Override public void close() { timer.shutdownNow(); clearThreadState(); paused.clear(); }
}
