package com.live_jgv.live_feed_producer.entrypoint.rest;

import com.live_jgv.live_feed_producer.application.service.MatchSimulationService;
import com.live_jgv.live_feed_producer.domain.model.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.time.Instant;
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
    public record EventRequest(String eventId,EventType eventType,String teamId,Integer minute,Instant timestamp,String referenceEventId){}
    @PostMapping("/{matchId}/events")
    public CompletableFuture<ResponseEntity<?>> publish(@PathVariable String matchId,@RequestBody EventRequest request){
        if(!writeEnabled)return CompletableFuture.completedFuture(ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(Map.of("error","El simulador de escritura está deshabilitado.")));
        if(request.minute()==null) throw new IllegalArgumentException("El minuto es obligatorio");
        return simulator.publish(matchId,request.eventId(),request.eventType(),request.teamId(),request.minute(),request.timestamp(),request.referenceEventId())
                .<ResponseEntity<?>>thenApply(event->ResponseEntity.accepted().body(event))
                .exceptionally(failure->ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of("error","Kafka no confirmó el evento. El resultado puede ser incierto; no reenvíes con otro ID.")));
    }
    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<?> invalid(IllegalArgumentException failure){return ResponseEntity.badRequest().body(Map.of("error",failure.getMessage()));}
}
