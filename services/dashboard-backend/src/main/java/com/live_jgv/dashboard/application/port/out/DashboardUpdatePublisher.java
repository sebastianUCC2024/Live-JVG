package com.live_jgv.dashboard.application.port.out;

import com.live_jgv.dashboard.domain.model.ScoreUpdate;

/** Propaga los fallos de entrega al llamador, sin prometer entrega garantizada. */
@FunctionalInterface
public interface DashboardUpdatePublisher {
    void publish(ScoreUpdate update);
}
