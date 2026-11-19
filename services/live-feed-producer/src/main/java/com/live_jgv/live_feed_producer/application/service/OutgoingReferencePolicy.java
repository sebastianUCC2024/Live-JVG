package com.live_jgv.live_feed_producer.application.service;

/** Valida publicaciones nuevas sin modificar la reconstrucción del historial. */
public final class OutgoingReferencePolicy {
    private OutgoingReferencePolicy() { }
    public static void validate(String reference) {
        if (reference != null && reference.isBlank()) {
            throw new IllegalArgumentException("reference_event_id must be null or nonblank");
        }
    }
}
