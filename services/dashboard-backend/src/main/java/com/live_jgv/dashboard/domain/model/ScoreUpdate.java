package com.live_jgv.dashboard.domain.model;

import java.time.Instant;

/** Internal immutable snapshot; transport mapping belongs to future adapters. */
public record ScoreUpdate(
        String matchId, int homeScore, int awayScore, String eventId,
        EventType eventType, Instant updatedAt, VarStatus varStatus, String referenceEventId) {

    public ScoreUpdate {
        requireIdentifier(matchId, "matchId");
        requireIdentifier(eventId, "eventId");
        if (homeScore < 0 || awayScore < 0) {
            throw new InvalidScoreUpdateException("Scores must be non-negative");
        }
        if (eventType == null || updatedAt == null || varStatus == null) {
            throw new InvalidScoreUpdateException("eventType, updatedAt and varStatus are required");
        }
        if (referenceEventId != null) {
            requireIdentifier(referenceEventId, "referenceEventId");
        }
        if (eventType == EventType.GOAL_ANNULLED && referenceEventId == null) {
            throw new InvalidScoreUpdateException("An annulment requires referenceEventId");
        }
    }

    private static void requireIdentifier(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new InvalidScoreUpdateException(field + " must not be blank");
        }
    }
}
