package com.live_jgv.matchstate.domain.port;

import com.live_jgv.matchstate.domain.model.ScoreUpdate;
import java.util.List;
import java.util.Optional;
import com.live_jgv.matchstate.domain.model.StoredScoreUpdate;

public interface ScoreUpdateOutbox {
    /** Pending updates in acceptance order, stored atomically with match state. */
    List<ScoreUpdate> pendingForMatch(String matchId);
    void markPublished(ScoreUpdate update);
    default Optional<StoredScoreUpdate> storedPublication(ScoreUpdate update) {
        return Optional.empty();
    }
}
