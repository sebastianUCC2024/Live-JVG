package com.live_jgv.betting.domain;

import java.time.Instant;
import java.util.Objects;

/** Comando interno auditado; no se expone por Kafka ni HTTP. */
public record ReviewClosure(String commandId, String matchId, String reviewId,
                            String continuationEventId, long expectedVersion, String actor, String reason,
                            Instant evidenceIssuedAt, Instant evidenceExpiresAt) {
    /** La versión se interpreta explícitamente, sin deducirla del mensaje. */
    public enum ReferenceMode { V1_GOAL, V2_REVIEW }
    public ReviewClosure {
        for (String value : new String[]{commandId,matchId,reviewId,continuationEventId,actor,reason})
            if (value == null || value.isBlank()) throw new IllegalArgumentException("Closure fields required");
        if (expectedVersion < 0) throw new IllegalArgumentException("Invalid expected version");
        Objects.requireNonNull(evidenceIssuedAt);Objects.requireNonNull(evidenceExpiresAt);
        if(!evidenceExpiresAt.isAfter(evidenceIssuedAt))throw new IllegalArgumentException("Invalid closure evidence interval");
    }
}
