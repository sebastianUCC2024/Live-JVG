package com.live_jgv.live_feed_producer.application.service;

/** Representa un fallo anterior al envío a Kafka. */
public final class EventPreparationException extends IllegalArgumentException {
    public EventPreparationException(Throwable cause) {
        super("Could not prepare event JSON", cause);
    }
}
