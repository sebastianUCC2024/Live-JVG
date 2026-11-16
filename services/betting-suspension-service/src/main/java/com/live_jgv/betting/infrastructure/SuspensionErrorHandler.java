package com.live_jgv.betting.infrastructure;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.listener.*;
import org.springframework.util.backoff.FixedBackOff;
import java.util.List;
import java.util.Map;

/** Four deliveries per processing cycle, then pause for operator review. No discard recovery. */
public final class SuspensionErrorHandler extends DefaultErrorHandler {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(SuspensionErrorHandler.class);
    private final ThreadLocal<MessageListenerContainer> current = new ThreadLocal<>();
    public SuspensionErrorHandler(long intervalMs) {
        super((record, failure) -> { throw new IllegalStateException("Suspension input remains uncommitted"); },
                backoff(intervalMs));
        setClassifications(Map.of(Exception.class, true), true);
        setResetStateOnExceptionChange(false);
        setAckAfterHandle(false); setCommitRecovered(false);
        setRetryListeners((record, failure, attempt) -> {
            if (attempt >= 4 && current.get() != null) {
                current.get().pause();
                log.error("Suspension retries exhausted; partition={}, offset={}; consumer paused with offset retained",
                        record.partition(), record.offset());
            }
        });
    }
    private static FixedBackOff backoff(long interval) {
        if (interval < 1) throw new IllegalArgumentException("Retry interval must be positive");
        return new FixedBackOff(interval, 3);
    }
    @Override public void handleRemaining(Exception failure, List<ConsumerRecord<?, ?>> records,
                                           Consumer<?, ?> consumer, MessageListenerContainer container) {
        current.set(container);
        try { super.handleRemaining(failure, records, consumer, container); }
        finally { current.remove(); }
    }
}
