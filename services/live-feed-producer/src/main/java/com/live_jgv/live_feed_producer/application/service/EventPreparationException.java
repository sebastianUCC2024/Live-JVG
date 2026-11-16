package com.live_jgv.live_feed_producer.application.service;

/** Failure before the Kafka send is invoked. */
public final class EventPreparationException extends IllegalArgumentException {
    public EventPreparationException(Throwable cause) {
        super("Could not prepare event JSON", cause);
    }
}
