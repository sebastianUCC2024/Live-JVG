package com.live_jgv.dashboard.application.port.out;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Puerto de publicación y reinicio de rondas; conserva la respuesta del productor. */
public interface SimulationPublisher {
    Map<?, ?> publish(String matchId, Map<String, Object> event);
    List<String> startRound(UUID roundId);
}
