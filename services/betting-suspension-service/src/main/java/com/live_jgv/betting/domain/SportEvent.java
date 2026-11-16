package com.live_jgv.betting.domain;

import java.time.Instant;
import java.util.Objects;

/** Local representation of the observed wire format, not an approved shared contract. */
public record SportEvent(String eventId, String matchId, EventType eventType, String teamId,
                         int minute, Instant timestamp, String referenceEventId) {
    public enum EventType { GOAL, VAR_CHECK, GOAL_ANNULLED, MATCH_CONTINUES }
    public SportEvent {
        requireText(eventId); requireText(matchId);
        Objects.requireNonNull(eventType); Objects.requireNonNull(timestamp);
        if (minute < 0 || minute > 130) throw new IllegalArgumentException("Invalid minute");
        if (eventType == EventType.GOAL) requireText(teamId);
        if (eventType == EventType.GOAL_ANNULLED) requireText(referenceEventId);
    }
    private static void requireText(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Identifier required");
    }
}
