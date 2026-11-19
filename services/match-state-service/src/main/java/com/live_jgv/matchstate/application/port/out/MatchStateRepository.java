package com.live_jgv.matchstate.application.port.out;

import com.live_jgv.matchstate.domain.model.MatchState;
import java.util.Optional;
import com.live_jgv.matchstate.domain.model.ScoreUpdate;
import com.live_jgv.matchstate.domain.model.LoadedMatch;

/** Storage boundary; match setup must exist before processing sporting events. */
public interface MatchStateRepository {
    Optional<LoadedMatch> load(String matchId);

    default Optional<MatchState> findById(String matchId) {
        return load(matchId).map(LoadedMatch::state);
    }

    /**
     * Atomically replace the loaded snapshot only if storage still represents it.
     * Return false on conflict. Throw on storage failure; never partially persist.
     * Compare the loaded storage revision, never Java object identity.
     * Persist state, accepted history and pending output together.
     */
    boolean compareAndSet(LoadedMatch expected, MatchState updated, ScoreUpdate update);
}
