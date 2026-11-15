package com.live_jgv.matchstate.domain.port;

import com.live_jgv.matchstate.domain.model.MatchState;
import java.util.Optional;
import com.live_jgv.matchstate.domain.model.ScoreUpdate;

/** Storage boundary; match setup must exist before processing sporting events. */
public interface MatchStateRepository {
    Optional<MatchState> findById(String matchId);

    /**
     * Atomically replace the loaded snapshot only if storage still represents it.
     * Return false on conflict. Throw on storage failure; never partially persist.
     * Future durable adapters must use an explicit revision/CAS mechanism rather
     * than Java object identity. Persist state, event history and the pending output together.
     */
    boolean compareAndSet(MatchState expected, MatchState updated, ScoreUpdate update);
}
