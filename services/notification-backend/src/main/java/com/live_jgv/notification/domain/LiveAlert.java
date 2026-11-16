package com.live_jgv.notification.domain;
import java.time.Instant;
import java.util.UUID;

/** Service-local model; no shared LiveAlert schema is approved yet. */
public record LiveAlert(UUID alertId, ScoreUpdate source, Instant createdAt, Content content) {
    public record Content(String locale, String title, String message) { }
}
