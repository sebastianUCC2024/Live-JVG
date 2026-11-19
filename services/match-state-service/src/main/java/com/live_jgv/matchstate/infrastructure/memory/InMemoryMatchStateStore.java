package com.live_jgv.matchstate.infrastructure.memory;

import com.live_jgv.matchstate.domain.model.MatchState;
import com.live_jgv.matchstate.domain.model.LoadedMatch;
import com.live_jgv.matchstate.domain.model.ScoreUpdate;
import com.live_jgv.matchstate.application.port.out.MatchStateRepository;
import com.live_jgv.matchstate.application.port.out.ScoreUpdateOutbox;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Adaptador en memoria: estado, deduplicación y pendientes se pierden al reiniciar. */
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
    public synchronized Optional<LoadedMatch> load(String matchId) {
        var entry = matches.get(matchId);
        return entry == null ? Optional.empty() : Optional.of(new LoadedMatch(entry.state(), entry.state().processedEvents().size()));
    }

    @Override
    public synchronized boolean compareAndSet(LoadedMatch loaded, MatchState updated, ScoreUpdate update) {
        var expected = loaded.state();
        if (!expected.matchId().equals(updated.matchId()) || !updated.matchId().equals(update.matchId())) {
            throw new IllegalArgumentException("State and update must identify the same match");
        }
        var current = matches.get(expected.matchId());
        if (current == null || current.state().processedEvents().size() != loaded.revision()) {
            return false;
        }
        var pending = new LinkedHashMap<>(current.pending());
        if (pending.putIfAbsent(update.eventId(), update) != null) {
            throw new IllegalStateException("Update already pending");
        }
        // Reemplaza estado y outbox juntos para mantener una vista consistente.
        matches.put(updated.matchId(), new Entry(updated, java.util.Collections.unmodifiableMap(pending)));
        return true;
    }

    /** Ayuda para pruebas en memoria; producción utiliza LoadedMatch explícito. */
    public boolean compareAndSet(MatchState expected, MatchState updated, ScoreUpdate update) {
        return compareAndSet(new LoadedMatch(expected, expected.processedEvents().size()), updated, update);
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
