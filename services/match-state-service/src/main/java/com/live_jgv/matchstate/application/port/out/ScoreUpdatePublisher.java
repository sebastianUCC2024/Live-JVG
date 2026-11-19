package com.live_jgv.matchstate.application.port.out;

import com.live_jgv.matchstate.domain.model.ScoreUpdate;
import com.live_jgv.matchstate.domain.model.StoredScoreUpdate;

@FunctionalInterface
public interface ScoreUpdatePublisher {
    /** Retorna tras la confirmación; falla si la entrega es incierta. */
    void publish(ScoreUpdate update);

    default void publishStored(StoredScoreUpdate publication) {
        throw new UnsupportedOperationException("Publisher does not support stored wire images");
    }
}
