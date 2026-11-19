package com.live_jgv.dashboard.application.service;

import com.live_jgv.dashboard.domain.model.MatchSnapshot;
import com.live_jgv.dashboard.application.port.in.GetMatchSnapshot;
import com.live_jgv.dashboard.application.port.out.MatchSnapshotRepository;
import java.util.Objects;
import java.util.Optional;

public final class GetMatchSnapshotService implements GetMatchSnapshot {
    private final MatchSnapshotRepository repository;

    public GetMatchSnapshotService(MatchSnapshotRepository repository) {
        this.repository = Objects.requireNonNull(repository);
    }

    @Override
    public Optional<MatchSnapshot> find(String matchId) {
        return repository.findSnapshot(matchId);
    }
}
