package com.live_jgv.matchstate.domain.port;

import com.live_jgv.matchstate.domain.model.ScoreUpdate;
import com.live_jgv.matchstate.domain.model.StoredScoreUpdate;

@FunctionalInterface
public interface ScoreUpdatePublisher {
    /** Return only after transport acknowledgement; throw on failure or uncertainty. */
    void publish(ScoreUpdate update);

    default void publishStored(StoredScoreUpdate publication) {
        throw new UnsupportedOperationException("Publisher does not support stored wire images");
    }
}
