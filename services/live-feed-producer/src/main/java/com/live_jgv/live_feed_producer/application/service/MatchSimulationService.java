package com.live_jgv.live_feed_producer.application.service;

import com.live_jgv.live_feed_producer.domain.model.MatchEvent;
import com.live_jgv.live_feed_producer.domain.port.MatchEventPublisher;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import org.springframework.stereotype.Service;

@Service
public final class MatchSimulationService {
    private final MatchEventPublisher publisher;

    public MatchSimulationService(MatchEventPublisher publisher) {
        this.publisher = Objects.requireNonNull(publisher);
    }

    /** Preserve caller identity, including repeated events; deduplication belongs downstream. */
    public CompletableFuture<Void> publish(MatchEvent event) {
        return publisher.publish(Objects.requireNonNull(event));
    }
}
