package com.live_jgv.matchstate.application.port.out;

import com.live_jgv.matchstate.domain.model.MatchState;
import java.util.Optional;
import com.live_jgv.matchstate.domain.model.ScoreUpdate;
import com.live_jgv.matchstate.domain.model.LoadedMatch;

/** Puerto de persistencia; el partido debe existir antes de aceptar eventos. */
public interface MatchStateRepository {
    Optional<LoadedMatch> load(String matchId);

    default Optional<MatchState> findById(String matchId) {
        return load(matchId).map(LoadedMatch::state);
    }

    /** Guarda estado, historial y outbox de forma atómica si coincide la revisión; un conflicto permite reintentar. */
    boolean compareAndSet(LoadedMatch expected, MatchState updated, ScoreUpdate update);
}
