package com.live_jgv.matchstate.domain.model;

import java.time.Instant;
import java.util.UUID;

/**
 * Immutable sporting event with no infrastructure dependencies.
 * The inclusive 0..130 minute interval is an initial project simulation limit,
 * not a universal football rule.
 * A GOAL_ANNULLED reference must identify the original GOAL. This value object
 * only checks that the reference is nonblank. The match-state-service will check
 * existence, event type, match ownership and whether the goal was already annulled.
 */
public record MatchEvent(String eventId, String matchId, EventType eventType,
                         String teamId, int minute, Instant timestamp,
                         String referenceEventId) {
    public static final int MIN_MINUTE = 0;
    public static final int MAX_MINUTE = 130;

    public MatchEvent {
        requireIdentifier(eventId, "eventId");
        requireIdentifier(matchId, "matchId");
        if (eventType == null) {
            throw new IllegalArgumentException("eventType must not be null");
        }
        if (timestamp == null) {
            throw new IllegalArgumentException("timestamp must not be null");
        }
        if (minute < MIN_MINUTE || minute > MAX_MINUTE) {
            throw new IllegalArgumentException("minute must be between 0 and 130 inclusive");
        }
        if (eventType == EventType.GOAL) {
            requireIdentifier(teamId, "teamId");
        }
        if (eventType == EventType.GOAL_ANNULLED) {
            requireIdentifier(referenceEventId, "referenceEventId");
        }
    }

    public static MatchEvent create(String matchId, EventType eventType, String teamId,
                                    int minute, Instant timestamp, String referenceEventId) {
        return new MatchEvent(UUID.randomUUID().toString(), matchId, eventType,
                teamId, minute, timestamp, referenceEventId);
    }

    private static void requireIdentifier(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be null or blank");
        }
    }
}
