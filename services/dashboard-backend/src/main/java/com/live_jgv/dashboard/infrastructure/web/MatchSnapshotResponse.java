package com.live_jgv.dashboard.infrastructure.web;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.live_jgv.dashboard.domain.model.*;

/** Respuesta del navegador, separada del contrato RabbitMQ. */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record MatchSnapshotResponse(String streamId, long revision, String matchId,
        int homeScore, int awayScore, String eventId, EventType eventType, String updatedAt,
        VarStatus varStatus, String referenceEventId) {
    public static MatchSnapshotResponse from(MatchSnapshot snapshot) {
        var update = snapshot.update();
        return new MatchSnapshotResponse(snapshot.streamId(), snapshot.revision(), update.matchId(),
                update.homeScore(), update.awayScore(), update.eventId(), update.eventType(),
                update.updatedAt().toString(), update.varStatus(), update.referenceEventId());
    }
}
