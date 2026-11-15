package com.live_jgv.matchstate.infrastructure.memory;

import com.live_jgv.matchstate.domain.model.MatchState;
import com.live_jgv.matchstate.domain.model.ScoreUpdate;
import com.live_jgv.matchstate.domain.port.MatchStateRepository;
import com.live_jgv.matchstate.domain.port.ScoreUpdateOutbox;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Single-process adapter. State, deduplication and pending messages are NOT durable. */
public final class InMemoryMatchStateStore implements MatchStateRepository, ScoreUpdateOutbox {
    private record Entry(MatchState state, Map<String, ScoreUpdate> pending) { }
    private final Map<String, Entry> matches = new HashMap<>();

    public InMemoryMatchStateStore(List<MatchState> initialMatches) {
        for (var match : initialMatches) {
            if (matches.putIfAbsent(match.matchId(), new Entry(match, Map.of())) != null) {
                throw new IllegalArgumentException("Duplicate configured match ID");
            }
        }
    }

    @Override
    public synchronized Optional<MatchState> findById(String matchId) {
        var entry = matches.get(matchId);
        return entry == null ? Optional.empty() : Optional.of(entry.state());
    }

    @Override
    public synchronized boolean compareAndSet(MatchState expected, MatchState updated, ScoreUpdate update) {
        if (!expected.matchId().equals(updated.matchId()) || !updated.matchId().equals(update.matchId())) {
            throw new IllegalArgumentException("State and update must identify the same match");
        }
        var current = matches.get(expected.matchId());
        if (current == null || current.state() != expected) {
            return false;
        }
        var pending = new LinkedHashMap<>(current.pending());
        if (pending.putIfAbsent(update.eventId(), update) != null) {
            throw new IllegalStateException("Update already pending");
        }
        // Replace one entry after constructing both objects; readers see state and outbox together.
        matches.put(updated.matchId(), new Entry(updated, java.util.Collections.unmodifiableMap(pending)));
        return true;
    }

    @Override
    public synchronized List<ScoreUpdate> pendingForMatch(String matchId) {
        var current = matches.get(matchId);
        return current == null ? List.of() : List.copyOf(current.pending().values());
    }

    @Override
    public synchronized void markPublished(ScoreUpdate update) {
        var current = matches.get(update.matchId());
        if (current == null || !update.equals(current.pending().get(update.eventId()))) {
            throw new IllegalStateException("Pending update does not match acknowledgement");
        }
        var pending = new LinkedHashMap<>(current.pending());
        pending.remove(update.eventId());
        matches.put(update.matchId(), new Entry(current.state(), java.util.Collections.unmodifiableMap(pending)));
    }
}
