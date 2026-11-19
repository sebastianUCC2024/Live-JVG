package com.live_jgv.dashboard.application.port.in;

import com.live_jgv.dashboard.domain.model.ScoreUpdate;

/** Accepts an authoritative snapshot without recalculating score or VAR rules. */
@FunctionalInterface
public interface ProcessScoreUpdate {
    void process(ScoreUpdate update);
}
