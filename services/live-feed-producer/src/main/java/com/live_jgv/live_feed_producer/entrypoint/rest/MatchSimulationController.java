package com.live_jgv.live_feed_producer.entrypoint.rest;

import com.live_jgv.live_feed_producer.application.service.MatchSimulationService;
import com.live_jgv.live_feed_producer.application.service.EventPublicationException;
import com.live_jgv.live_feed_producer.domain.model.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.time.Instant;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.springframework.beans.factory.annotation.Value;

@RestController
@RequestMapping("/api/matches")
public class MatchSimulationController {
    private final MatchSimulationService simulator;
    private final boolean writeEnabled;
    public MatchSimulationController(MatchSimulationService simulator,
            @Value("${simulator.write-enabled:false}") boolean writeEnabled){this.simulator=simulator;this.writeEnabled=writeEnabled;}
    public record EventRequest(String eventId,EventType eventType,String teamId,Integer minute,Instant timestamp,String referenceEventId) {
        @JsonCreator
        public static EventRequest fromJson(@JsonProperty("event_id") String eventId, @JsonProperty("event_type") EventType eventType,
                @JsonProperty("team_id") String teamId, @JsonProperty("minute") Integer minute,
                @JsonProperty("timestamp") Instant timestamp,
                @JsonProperty("reference_event_id") JsonNode reference,
                @JsonProperty("annuls_event_id") JsonNode annuls) {
            return new EventRequest(eventId,eventType,teamId,minute,timestamp,resolveReference(eventType,reference,annuls));
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
    }
    @PostMapping("/{matchId}/events")
    public CompletableFuture<ResponseEntity<?>> publish(@PathVariable String matchId,@RequestBody EventRequest request){
        if(!writeEnabled)return CompletableFuture.completedFuture(ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(Map.of("error","El simulador de escritura está deshabilitado.")));
        if(request.minute()==null) throw new IllegalArgumentException("El minuto es obligatorio");
        return simulator.publish(matchId,request.eventId(),request.eventType(),request.teamId(),request.minute(),request.timestamp(),request.referenceEventId())
                .<ResponseEntity<?>>thenApply(event->ResponseEntity.accepted().body(event))
                .exceptionally(this::publicationError);
    }
    private ResponseEntity<?> publicationError(Throwable failure) {
        while (failure instanceof java.util.concurrent.CompletionException && failure.getCause() != null) {
            failure = failure.getCause();
        }
        if (failure instanceof EventPublicationException publication) {
            boolean preparation = publication.preparationFailure();
            return ResponseEntity.status(preparation ? HttpStatus.INTERNAL_SERVER_ERROR : HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("error", preparation ? "No se pudo preparar el evento; no se envió a Kafka."
                                    : "Kafka no confirmó el evento. Resultado incierto; reintenta el mismo evento sin cambiar su identidad.",
                            "code", preparation ? "EVENT_PREPARATION_FAILED" : "PUBLICATION_UNCONFIRMED",
                            "event", publication.event()));
        }
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", "No se pudo completar la publicación."));
    }
    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<?> invalid(IllegalArgumentException failure){return ResponseEntity.badRequest().body(Map.of("error",failure.getMessage()));}
}
