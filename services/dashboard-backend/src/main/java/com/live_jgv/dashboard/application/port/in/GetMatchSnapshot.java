package com.live_jgv.dashboard.application.port.in;

import com.live_jgv.dashboard.domain.model.MatchSnapshot;
import java.util.Optional;

@FunctionalInterface
public interface GetMatchSnapshot {
    Optional<MatchSnapshot> find(String matchId);
}
