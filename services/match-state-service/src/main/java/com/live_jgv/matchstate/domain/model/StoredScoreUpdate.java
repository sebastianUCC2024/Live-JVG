package com.live_jgv.matchstate.domain.model;

import java.util.Objects;

/** Immutable wire image and destination captured in the acceptance transaction. */
public record StoredScoreUpdate(ScoreUpdate update, String payload, String exchange, String routingKey) {
    public StoredScoreUpdate {
        Objects.requireNonNull(update);
        Objects.requireNonNull(payload);
        if (exchange == null || exchange.isBlank() || routingKey == null || routingKey.isBlank()) {
            throw new IllegalArgumentException("Stored publication requires a destination");
        }
    }
}
