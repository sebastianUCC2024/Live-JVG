package com.live_jgv.matchstate.application.service;

import com.live_jgv.matchstate.domain.model.MatchEvent;
import com.live_jgv.matchstate.domain.model.MatchState;
import com.live_jgv.matchstate.application.port.in.ProcessMatchEvent;
import com.live_jgv.matchstate.application.port.out.ScoreUpdateOutbox;
import com.live_jgv.matchstate.application.port.out.ScoreUpdatePublisher;
import java.util.Objects;

/** Coordina procesamiento y entrega; las reglas del partido permanecen en MatchState. */
public final class ProcessAndPublishMatchEvent implements ProcessMatchEvent {
    private final ProcessMatchEvent processor;
    private final ScoreUpdateOutbox outbox;
    private final ScoreUpdatePublisher publisher;
    private final java.util.concurrent.ConcurrentMap<String, Object> lanes = new java.util.concurrent.ConcurrentHashMap<>();

    public ProcessAndPublishMatchEvent(ProcessMatchEvent processor, ScoreUpdateOutbox outbox,
                                      ScoreUpdatePublisher publisher) {
        this.processor = Objects.requireNonNull(processor);
        this.outbox = Objects.requireNonNull(outbox);
        this.publisher = Objects.requireNonNull(publisher);
    }

    @Override
    public MatchState process(MatchEvent event) {
        Objects.requireNonNull(event, "Event is required");
        synchronized (lanes.computeIfAbsent(event.matchId(), ignored -> new Object())) {
            flush(event.matchId());
            var state = processor.process(event);
            flush(event.matchId());
            return state;
        }
    }

    private void flush(String matchId) {
        outbox.withPublicationLock(matchId, () -> {
            while (true) {
                var pending = outbox.pendingForMatch(matchId);
                if (pending.isEmpty()) return;
                for (var update : pending) {
                    var stored = outbox.storedPublication(update);
                    if (stored.isPresent()) {
                        publisher.publishStored(stored.get());
                    } else {
                        publisher.publish(update);
                    }
                    outbox.markPublished(update);
                }
            }
        });
    }

    /** Recupera pendientes con el mismo bloqueo, sin aplicar de nuevo los eventos. */
    public void recoverPendingMatch(String matchId) {
        synchronized (lanes.computeIfAbsent(matchId, ignored -> new Object())) {
            flush(matchId);
        }
    }
}
