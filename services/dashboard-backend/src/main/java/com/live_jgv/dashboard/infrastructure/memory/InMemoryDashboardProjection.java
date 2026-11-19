package com.live_jgv.dashboard.infrastructure.memory;

import com.live_jgv.dashboard.domain.model.InvalidScoreUpdateException;
import com.live_jgv.dashboard.domain.model.ScoreUpdate;
import com.live_jgv.dashboard.domain.model.EventType;
import com.live_jgv.dashboard.domain.model.VarStatus;
import com.live_jgv.dashboard.domain.model.MatchSnapshot;
import com.live_jgv.dashboard.application.port.out.MatchSnapshotPublisher;
import com.live_jgv.dashboard.application.port.out.MatchSnapshotRepository;
import com.live_jgv.dashboard.application.port.out.DashboardUpdatePublisher;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/** Proyección en memoria de una instancia; procesa eventos según su llegada. */
public final class InMemoryDashboardProjection implements DashboardUpdatePublisher, MatchSnapshotRepository {
    private final Map<EventKey, EventContent> processed = new HashMap<>();
    private final Map<String, MatchSnapshot> latest = new HashMap<>();
    private final int maxEvents;
    private final MatchSnapshotPublisher publisher;
    private final String streamId = java.util.UUID.randomUUID().toString();
    private long revision;

    public InMemoryDashboardProjection(int maxEvents) {
        this(maxEvents, snapshot -> { });
    }

    public InMemoryDashboardProjection(int maxEvents, MatchSnapshotPublisher publisher) {
        if (maxEvents < 1) {
            throw new IllegalArgumentException("maxEvents must be positive");
        }
        this.maxEvents = maxEvents;
        this.publisher = java.util.Objects.requireNonNull(publisher);
    }

    @Override
    public synchronized void publish(ScoreUpdate update) {
        var key = new EventKey(update.matchId(), update.eventId());
        var content = new EventContent(update.homeScore(), update.awayScore(), update.eventType(),
                update.varStatus(), update.referenceEventId());
        var previous = processed.get(key);
        if (previous != null) {
            if (!previous.equals(content)) {
                throw new InvalidScoreUpdateException("Conflicting event identity");
            }
            return;
        }
        if (processed.size() >= maxEvents) {
            throw new IllegalStateException("Projection capacity reached");
        }
        var snapshot = new MatchSnapshot(streamId, revision + 1, update);
        // Registra la deduplicación después de aceptar la publicación.
        // El listener puede reintentar si falla el adaptador de salida.
        publisher.publish(snapshot);
        processed.put(key, content);
        latest.put(update.matchId(), snapshot);
        revision++;
    }

    public synchronized Optional<ScoreUpdate> findByMatchId(String matchId) {
        return findSnapshot(matchId).map(MatchSnapshot::update);
    }

    @Override
    public synchronized Optional<MatchSnapshot> findSnapshot(String matchId) {
        return Optional.ofNullable(latest.get(matchId));
    }

    private record EventKey(String matchId, String eventId) { }

    // El replay puede cambiar updated_at porque se genera durante el procesamiento.
    // Un duplicado con otro timestamp no reemplaza el último marcador.
    private record EventContent(int homeScore, int awayScore, EventType eventType,
            VarStatus varStatus, String referenceEventId) { }
}
