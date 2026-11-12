package com.live_jgv.matchstate.application.service;

import com.live_jgv.matchstate.domain.model.MatchEvent;
import com.live_jgv.matchstate.domain.model.MatchState;
import com.live_jgv.matchstate.domain.port.MatchStateRepository;
import com.live_jgv.matchstate.domain.port.ProcessMatchEvent;
import java.util.ConcurrentModificationException;
import java.util.Objects;
import java.time.Clock;
import com.live_jgv.matchstate.domain.model.ScoreUpdate;

public final class MatchStateService implements ProcessMatchEvent {
    private final MatchStateRepository repository;
    private final Clock clock;

    public MatchStateService(MatchStateRepository repository) {
        this(repository, Clock.systemUTC());
    }

    public MatchStateService(MatchStateRepository repository, Clock clock) {
        this.repository = Objects.requireNonNull(repository);
        this.clock = Objects.requireNonNull(clock);
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
        var score = updated.score();
        var update = new ScoreUpdate(updated.matchId(), score.home(), score.away(), event.eventId(),
                event.eventType(), clock.instant(), updated.varStatus(), event.referenceEventId());
        if (!repository.compareAndSet(current, updated, update)) {
            throw new ConcurrentModificationException("Match changed concurrently; reload and retry event");
        }
        return updated;
    }
}
