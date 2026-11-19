package com.live_jgv.matchstate.application.port.in;

import com.live_jgv.matchstate.domain.model.MatchEvent;
import com.live_jgv.matchstate.domain.model.MatchState;

/** Puerto de entrada independiente de Kafka y RabbitMQ. */
@FunctionalInterface
public interface ProcessMatchEvent {
    MatchState process(MatchEvent event);
}
