package com.live_jgv.matchstate.entrypoint.kafka;

import com.live_jgv.matchstate.domain.model.EventType;
import com.live_jgv.matchstate.domain.model.MatchEvent;
import java.time.Instant;

/** Wire DTO keeps mandatory minute nullable until explicit validation. */
public record MatchEventMessage(String eventId, String matchId, EventType eventType,
                                String teamId, Integer minute, String timestamp, String referenceEventId) {
    public MatchEvent toDomain() {
        if (minute == null || timestamp == null) {
            throw new IllegalArgumentException("Minute and timestamp are required");
        }
        return new MatchEvent(eventId, matchId, eventType, teamId, minute,
                Instant.parse(timestamp), referenceEventId);
    }
}
