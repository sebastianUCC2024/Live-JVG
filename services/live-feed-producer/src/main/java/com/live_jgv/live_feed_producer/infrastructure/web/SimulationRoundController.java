package com.live_jgv.live_feed_producer.infrastructure.web;

import com.live_jgv.live_feed_producer.infrastructure.scheduling.AutomaticMatchSimulator;
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
