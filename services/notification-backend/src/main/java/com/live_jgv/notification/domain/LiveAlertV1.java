package com.live_jgv.notification.domain;

import java.time.Instant;
import java.util.UUID;

/** Immutable inbound identity. Distinct from the existing REST projection. */
public record LiveAlertV1(String contractVersion, UUID alertId, String matchId,
        String sourceEventId, ScoreUpdate.EventType eventType, String message,
        Instant timestamp, String referenceEventId, ScoreUpdate snapshot) {
    public LiveAlertV1 {
        if (!"1.0".equals(contractVersion) || alertId == null || timestamp == null)
            throw new IllegalArgumentException("LiveAlert V1 identity, version and timestamp required");
        if (matchId == null || matchId.isBlank() || sourceEventId == null || sourceEventId.isBlank()
                || eventType == null || message == null || message.isBlank() || message.length() > 500)
            throw new IllegalArgumentException("Invalid LiveAlert V1 payload");
        if (snapshot == null || !matchId.equals(snapshot.matchId()) || !sourceEventId.equals(snapshot.eventId())
                || eventType != snapshot.eventType() || !timestamp.equals(snapshot.updatedAt())
                || !java.util.Objects.equals(referenceEventId,snapshot.referenceEventId()))
            throw new IllegalArgumentException("LiveAlert snapshot identity mismatch");
        if (eventType == ScoreUpdate.EventType.MATCH_CONTINUES)
            throw new IllegalArgumentException("Continuation has no visible alert");
        if ((referenceEventId != null && referenceEventId.isBlank())
                || (eventType == ScoreUpdate.EventType.GOAL_ANNULLED && referenceEventId == null))
            throw new IllegalArgumentException("Annulment requires original goal reference");
    }
}
