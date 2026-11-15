package com.live_jgv.dashboard.domain.port.out;

import com.live_jgv.dashboard.domain.model.MatchSnapshot;

@FunctionalInterface
public interface MatchSnapshotPublisher {
    void publish(MatchSnapshot snapshot);
}
