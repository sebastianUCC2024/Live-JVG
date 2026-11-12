package com.live_jgv.matchstate.domain.port;

import com.live_jgv.matchstate.domain.model.ScoreUpdate;

@FunctionalInterface
public interface ScoreUpdatePublisher {
    /** Return only after transport acknowledgement; throw on failure or uncertainty. */
    void publish(ScoreUpdate update);
}
