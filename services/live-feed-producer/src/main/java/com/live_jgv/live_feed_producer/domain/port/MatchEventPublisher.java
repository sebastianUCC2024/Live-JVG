package com.live_jgv.live_feed_producer.domain.port;

import com.live_jgv.live_feed_producer.domain.model.MatchEvent;
import java.util.concurrent.CompletableFuture;

/** Output port for publishing a validated sporting event. */
@FunctionalInterface
public interface MatchEventPublisher {
    /** Completes after transport acknowledgement; failures complete exceptionally. */
    CompletableFuture<Void> publish(MatchEvent event);
}
