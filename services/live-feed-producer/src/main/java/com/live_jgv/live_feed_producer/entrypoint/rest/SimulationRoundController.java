package com.live_jgv.live_feed_producer.entrypoint.rest;

import com.live_jgv.live_feed_producer.application.service.AutomaticMatchSimulator;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/internal/simulation")
public class SimulationRoundController {
    private final AutomaticMatchSimulator simulator;

    public SimulationRoundController(AutomaticMatchSimulator simulator) {
        this.simulator = simulator;
    }

    @PostMapping("/rounds/{id}")
    public List<String> start(@PathVariable UUID id) {
        return simulator.restart(id);
    }
}
