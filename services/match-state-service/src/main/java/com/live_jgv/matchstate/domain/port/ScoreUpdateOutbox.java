package com.live_jgv.matchstate.domain.port;

import com.live_jgv.matchstate.domain.model.ScoreUpdate;
import java.util.List;

public interface ScoreUpdateOutbox {
    /** Pending updates in acceptance order, stored atomically with match state. */
    List<ScoreUpdate> pendingForMatch(String matchId);
    void markPublished(ScoreUpdate update);
}
