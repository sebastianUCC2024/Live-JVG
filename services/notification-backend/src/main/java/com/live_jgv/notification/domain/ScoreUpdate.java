package com.live_jgv.notification.domain;

import java.time.Instant;

public record ScoreUpdate(String matchId, String eventId, EventType eventType,
        int homeScore, int awayScore, VarStatus varStatus, Instant updatedAt, String referenceEventId) {
    public enum EventType { GOAL, VAR_CHECK, GOAL_ANNULLED, MATCH_CONTINUES }
    public enum VarStatus { CLEAR, IN_PROGRESS }
    public ScoreUpdate {
        if (matchId == null || matchId.isBlank() || eventId == null || eventId.isBlank()
                || eventType == null || varStatus == null || updatedAt == null || homeScore < 0 || awayScore < 0
                || (referenceEventId != null && referenceEventId.isBlank())
                || (eventType == EventType.GOAL_ANNULLED && referenceEventId == null)) {
            throw new InvalidUpdateException("Invalid ScoreUpdate");
        }
    }
}
