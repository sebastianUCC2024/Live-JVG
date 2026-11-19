package com.live_jgv.matchstate.application.port.out;

import com.live_jgv.matchstate.domain.model.ScoreUpdate;
import java.util.List;
import java.util.Optional;
import com.live_jgv.matchstate.domain.model.StoredScoreUpdate;

/**
 * Pending publication boundary, kept separate from broker transport.
 * Marking follows a confirmed send; a crash between send and marking can redeliver an update.
 * Consumers must therefore retain their own duplicate detection.
 */
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
