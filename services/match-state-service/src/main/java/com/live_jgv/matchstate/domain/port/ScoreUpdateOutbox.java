package com.live_jgv.matchstate.domain.port;

import com.live_jgv.matchstate.domain.model.ScoreUpdate;
import java.util.List;
import java.util.Optional;
import com.live_jgv.matchstate.domain.model.StoredScoreUpdate;

public interface ScoreUpdateOutbox {
    /** Durable implementations must retain ownership until the callback completes. */
    default void withPublicationLock(String matchId, Runnable publication) { publication.run(); }
    /** Next pending batch in acceptance order; repeat until empty under the publication lock. */
    List<ScoreUpdate> pendingForMatch(String matchId);
    void markPublished(ScoreUpdate update);
    default Optional<StoredScoreUpdate> storedPublication(ScoreUpdate update) {
        return Optional.empty();
    }
}
