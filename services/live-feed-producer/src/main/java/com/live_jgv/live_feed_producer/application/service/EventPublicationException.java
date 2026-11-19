package com.live_jgv.live_feed_producer.application.service;

import com.live_jgv.live_feed_producer.domain.model.MatchEvent;

/** Conserva el evento exacto para recuperarlo sin guardarlo en el servidor. */
public final class EventPublicationException extends IllegalStateException {
    private final MatchEvent event;
    private final boolean preparationFailure;

    public EventPublicationException(MatchEvent event, Throwable cause) {
        super("Event publication did not complete successfully", cause);
        this.event = event;
        this.preparationFailure = cause instanceof EventPreparationException;
    }

    public MatchEvent event() { return event; }
    public boolean preparationFailure() { return preparationFailure; }
}
