package com.live_jgv.live_feed_producer.entrypoint.rest;

import com.live_jgv.live_feed_producer.domain.model.EventType;
import com.live_jgv.live_feed_producer.domain.model.MatchEvent;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.Instant;

/** REST uses camelCase; Kafka serialization remains the existing adapter's responsibility. */
public record MatchEventRequest(
        @NotBlank String eventId,
        @NotBlank String matchId,
        @NotBlank String eventType,
        @Pattern(regexp = ".*\\S.*", flags = Pattern.Flag.DOTALL) String teamId,
        @NotNull @DecimalMin("0") @DecimalMax("130") @Digits(integer = 3, fraction = 0) BigDecimal minute,
        @NotBlank String timestamp,
        @Pattern(regexp = ".*\\S.*", flags = Pattern.Flag.DOTALL) String referenceEventId) {

    public MatchEvent toDomain() {
        return new MatchEvent(eventId, matchId, EventType.valueOf(eventType), teamId,
                minute.intValueExact(), Instant.parse(timestamp), referenceEventId);
    }
}
