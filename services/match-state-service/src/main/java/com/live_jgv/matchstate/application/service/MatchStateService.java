package com.live_jgv.matchstate.application.service;

import com.live_jgv.matchstate.domain.model.MatchEvent;
import com.live_jgv.matchstate.domain.model.MatchState;
import com.live_jgv.matchstate.domain.port.MatchStateRepository;
import com.live_jgv.matchstate.domain.port.ProcessMatchEvent;
import java.util.ConcurrentModificationException;
import java.util.Objects;

public final class MatchStateService implements ProcessMatchEvent {
    private final MatchStateRepository repository;

    public MatchStateService(MatchStateRepository repository) {
        this.repository = Objects.requireNonNull(repository);
    }

    @Override
    public MatchState process(MatchEvent event) {
        if (event == null) {
            throw new IllegalArgumentException("Event must not be null");
        }
        var current = repository.findById(event.matchId())
                .orElseThrow(() -> new IllegalArgumentException("Match has not been registered"));
        var updated = current.apply(event);
        if (updated == current) {
            return current;
        }
        if (!repository.compareAndSet(current, updated)) {
            throw new ConcurrentModificationException("Match changed concurrently; reload and retry event");
        }
        return updated;
    }
}
