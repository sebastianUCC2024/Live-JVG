package com.live_jgv.matchstate.domain.model;

import java.time.Instant;
import java.util.Objects;

/** Provisional output contract; no transport annotations. */
public record ScoreUpdate(String matchId, int homeScore, int awayScore,
                          String eventId, EventType eventType, Instant updatedAt,
                          VarStatus varStatus, String referenceEventId) {
    public ScoreUpdate {
        if (matchId == null || matchId.isBlank() || eventId == null || eventId.isBlank()) {
            throw new IllegalArgumentException("Match and event IDs are required");
        }
        if (homeScore < 0 || awayScore < 0) {
            throw new IllegalArgumentException("Score must not be negative");
        }
        Objects.requireNonNull(eventType);
        Objects.requireNonNull(updatedAt);
        Objects.requireNonNull(varStatus);
    }
}
