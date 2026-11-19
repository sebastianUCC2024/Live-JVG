package com.live_jgv.matchstate.infrastructure.kafka;

import com.live_jgv.matchstate.domain.model.EventType;
import com.live_jgv.matchstate.domain.model.MatchEvent;
import java.time.Instant;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;

/** Mantiene minute nullable hasta validar explícitamente el mensaje. */
public record MatchEventMessage(String eventId, String matchId, EventType eventType, String teamId, Integer minute, String timestamp, String referenceEventId) {
    @JsonCreator
    public static MatchEventMessage fromJson(@JsonProperty("event_id") String eventId, @JsonProperty("match_id") String matchId,
            @JsonProperty("event_type") EventType eventType, @JsonProperty("team_id") String teamId,
            @JsonProperty("minute") Integer minute, @JsonProperty("timestamp") String timestamp,
            @JsonProperty("reference_event_id") JsonNode reference,
            @JsonProperty("annuls_event_id") JsonNode annuls) {
        return new MatchEventMessage(eventId,matchId,eventType,teamId,minute,timestamp,resolveReference(eventType,reference,annuls));
    }
    private static String resolveReference(EventType type, JsonNode reference, JsonNode annuls) {
        String old = referenceText(reference);
        if (annuls != null && type != EventType.GOAL_ANNULLED) {
            throw new IllegalArgumentException("annuls_event_id is only valid for GOAL_ANNULLED");
        }
        String alias = referenceText(annuls);
        if (alias != null && alias.isBlank()) throw new IllegalArgumentException("Blank annuls_event_id");
        if (old != null && alias != null && !old.equals(alias)) {
            throw new IllegalArgumentException("Contradictory goal references");
        }
        return alias == null ? old : alias;
    }
    private static String referenceText(JsonNode node) {
        if (node == null || node.isNull()) return null;
        if (!node.isTextual()) throw new IllegalArgumentException("Goal reference must be text");
        return node.textValue();
    }

    public MatchEvent toDomain() {
        if (minute == null || timestamp == null) {
            throw new IllegalArgumentException("Minute and timestamp are required");
        }
        return new MatchEvent(eventId, matchId, eventType, teamId, minute,
                Instant.parse(timestamp), referenceEventId);
    }
}
