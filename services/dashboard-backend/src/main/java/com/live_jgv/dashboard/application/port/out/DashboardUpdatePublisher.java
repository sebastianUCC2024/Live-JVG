package com.live_jgv.dashboard.application.port.out;

import com.live_jgv.dashboard.domain.model.ScoreUpdate;

/** Delivery failures propagate to the caller; no delivery guarantee is implied. */
@FunctionalInterface
public interface DashboardUpdatePublisher {
    void publish(ScoreUpdate update);
}
