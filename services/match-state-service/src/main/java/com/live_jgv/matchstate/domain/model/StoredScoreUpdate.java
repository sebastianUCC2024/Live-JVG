package com.live_jgv.matchstate.domain.model;

import java.util.Objects;

/** Immutable wire image and destination captured in the acceptance transaction. */
public record StoredScoreUpdate(ScoreUpdate update, String payload, String exchange, String routingKey, Long applicationSeq) {
    public StoredScoreUpdate(ScoreUpdate update,String payload,String exchange,String routingKey) { this(update,payload,exchange,routingKey,null); }
    public StoredScoreUpdate {
        if(applicationSeq!=null && applicationSeq<1)throw new IllegalArgumentException("Positive durable sequence required");
        Objects.requireNonNull(update);
        Objects.requireNonNull(payload);
        if (exchange == null || exchange.isBlank() || routingKey == null || routingKey.isBlank()) {
            throw new IllegalArgumentException("Stored publication requires a destination");
        }
    }
}
