package com.live_jgv.live_feed_producer.entrypoint.rest;

import com.live_jgv.live_feed_producer.application.service.MatchSimulationService;
import com.live_jgv.live_feed_producer.domain.model.MatchEvent;
import jakarta.validation.Valid;
import java.util.concurrent.CompletableFuture;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@Profile({"dev", "test"})
@ConditionalOnProperty(name = "live-feed.integration-rest.enabled", havingValue = "true")
@RequestMapping("/api/integration/events")
public class MatchSimulationController {
    private final MatchSimulationService service;

    public MatchSimulationController(MatchSimulationService service) {
        this.service = service;
    }

    @PostMapping
    public CompletableFuture<ResponseEntity<Void>> publish(@Valid @RequestBody MatchEventRequest request) {
        final MatchEvent event;
        try {
            event = request.toDomain();
        } catch (IllegalArgumentException | java.time.DateTimeException failure) {
            throw new InvalidEventRequestException();
        }
        try {
            return service.publish(event).handle((ignored, failure) -> failure == null
                    ? ResponseEntity.<Void>noContent().build()
                    : ResponseEntity.<Void>status(503).build());
        } catch (RuntimeException failure) {
            return CompletableFuture.completedFuture(ResponseEntity.status(503).build());
        }
    }

    @ResponseStatus(org.springframework.http.HttpStatus.BAD_REQUEST)
    private static final class InvalidEventRequestException extends RuntimeException { }
}
