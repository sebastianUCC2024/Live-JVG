package com.live_jgv.dashboard.infrastructure.web;
import com.live_jgv.dashboard.application.port.out.SimulationPublisher;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@Profile("!memory")
@RestController
@RequestMapping("/api/simulator")
public class SimulationResetController {
    private final SimulationPublisher producer;
    private final String resetKey;

    public SimulationResetController(SimulationPublisher producer,
            @Value("${simulator.reset-key:}") String resetKey) {
        this.producer = producer;
        this.resetKey = resetKey;
    }

    public record ResetRequest(String presentationKey) {}

    @PostMapping("/rounds")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public List<String> reset(
            @RequestHeader(value = "X-Simulation-Key", defaultValue = "") String headerKey,
            @RequestBody(required = false) ResetRequest body) {
        if (resetKey.isBlank()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE);
        }
        String key = body == null ? headerKey : body.presentationKey();
        if (key == null || !MessageDigest.isEqual(resetKey.getBytes(StandardCharsets.UTF_8),
                key.getBytes(StandardCharsets.UTF_8))) {
            String reason = key == null || key.isBlank() ? "ausente"
                    : !key.matches("[0-9a-f]{48}") ? "formato_invalido" : "no_coincide";
            org.slf4j.LoggerFactory.getLogger(SimulationResetController.class)
                    .warn("Simulation reset rejected: source={}, reason={}",
                            body == null ? "header" : "json", reason);
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
        return producer.startRound(UUID.randomUUID());
    }
}
