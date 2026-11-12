package com.live_jgv.live_feed_producer.domain.port;

import com.live_jgv.live_feed_producer.domain.model.MatchEvent;

/** Output port for publishing a validated sporting event. */
@FunctionalInterface
public interface MatchEventPublisher {
    void publish(MatchEvent event);
}
