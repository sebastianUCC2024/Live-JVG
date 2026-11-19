package com.live_jgv.dashboard.application.port.out;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Outbound simulation operations; acknowledgment retains the existing upstream response shape. */
public interface SimulationPublisher {
    Map<?, ?> publish(String matchId, Map<String, Object> event);
    List<String> startRound(UUID roundId);
}
