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
        return process(event, null);
    }

    public MatchState processAtRevision(MatchEvent event, long expectedRevision) {
        return process(event, Long.valueOf(expectedRevision));
    }

    private MatchState process(MatchEvent event, Long expectedRevision) {
        if (event == null) {
            throw new IllegalArgumentException("Event must not be null");
        }
        var loaded = repository.load(event.matchId())
                .orElseThrow(() -> new IllegalArgumentException("Match has not been registered"));
        var current = loaded.state();
        if (expectedRevision != null && loaded.revision() != expectedRevision) {
            throw new ConcurrentModificationException("Approval revision changed");
        }
        var updated = current.apply(event);
        if (updated == current) {
            return current;
        }
        requirePublishableReference(event);
        var score = updated.score();
        var update = new ScoreUpdate(updated.matchId(), score.home(), score.away(), event.eventId(),
                event.eventType(), clock.instant(), updated.varStatus(), event.referenceEventId());
        if (!repository.compareAndSet(loaded, updated, update)) {
            throw new ConcurrentModificationException("Match changed concurrently; reload and retry event");
        }
        return updated;
    }

    /** New outputs must satisfy the consumer contract; historical aggregate replay is unchanged. */
    public static void requirePublishableReference(MatchEvent event) {
        if (event.referenceEventId() != null && event.referenceEventId().isBlank()) {
            throw new com.live_jgv.matchstate.domain.model.InvalidMatchEventException("reference_event_id must be null or nonblank");
        }
    }
}
