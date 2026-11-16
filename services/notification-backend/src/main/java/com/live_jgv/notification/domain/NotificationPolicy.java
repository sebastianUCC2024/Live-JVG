package com.live_jgv.notification.domain;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public final class NotificationPolicy {
    public Optional<LiveAlert> create(ScoreUpdate update, Instant now) {
        String title = switch (update.eventType()) {
            case GOAL -> "Gol";
            case VAR_CHECK -> "Revisión VAR";
            case GOAL_ANNULLED -> "Gol anulado";
            case MATCH_CONTINUES -> null;
        };
        if (title == null) return Optional.empty();
        String message = update.eventType() == ScoreUpdate.EventType.VAR_CHECK
                ? "Revisión VAR en curso. Marcador: "
                : update.varStatus() == ScoreUpdate.VarStatus.IN_PROGRESS
                    ? "Revisión VAR en curso. Marcador actualizado: " : "Marcador confirmado: ";
        return Optional.of(new LiveAlert(UUID.randomUUID(), update, now,
                new LiveAlert.Content("es", title, message + update.homeScore() + "–" + update.awayScore() + ".")));
    }
}
