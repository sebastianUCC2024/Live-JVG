package com.live_jgv.dashboard.application.port.out;

import com.live_jgv.dashboard.domain.model.MatchSnapshot;
import java.util.Optional;

@FunctionalInterface
public interface MatchSnapshotRepository {
    Optional<MatchSnapshot> findSnapshot(String matchId);
}
