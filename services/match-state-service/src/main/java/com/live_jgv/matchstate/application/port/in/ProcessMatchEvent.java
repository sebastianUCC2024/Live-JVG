package com.live_jgv.matchstate.application.port.in;

import com.live_jgv.matchstate.domain.model.MatchEvent;
import com.live_jgv.matchstate.domain.model.MatchState;

/** Inbound use case independent of transport technology. */
@FunctionalInterface
public interface ProcessMatchEvent {
    MatchState process(MatchEvent event);
}
