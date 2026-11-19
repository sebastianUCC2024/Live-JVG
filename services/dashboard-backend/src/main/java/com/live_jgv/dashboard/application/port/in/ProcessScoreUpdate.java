package com.live_jgv.dashboard.application.port.in;

import com.live_jgv.dashboard.domain.model.ScoreUpdate;

/** Acepta el marcador recibido sin recalcular goles ni reglas VAR. */
@FunctionalInterface
public interface ProcessScoreUpdate {
    void process(ScoreUpdate update);
}
