package com.live_jgv.notification.domain;
import java.time.Instant;
import java.util.UUID;

/** Modelo local de alerta, separado del contrato compartido. */
public record LiveAlert(UUID alertId, ScoreUpdate source, Instant createdAt, Content content) {
    public record Content(String locale, String title, String message) { }
}
