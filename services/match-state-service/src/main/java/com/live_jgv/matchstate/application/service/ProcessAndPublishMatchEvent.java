package com.live_jgv.matchstate.application.service;

import com.live_jgv.matchstate.domain.model.MatchEvent;
import com.live_jgv.matchstate.domain.model.MatchState;
import com.live_jgv.matchstate.domain.port.ProcessMatchEvent;
import com.live_jgv.matchstate.domain.port.ScoreUpdateOutbox;
import com.live_jgv.matchstate.domain.port.ScoreUpdatePublisher;
import java.util.Objects;

/** Coordinates processing and delivery; business rules remain in MatchState. */
public final class ProcessAndPublishMatchEvent implements ProcessMatchEvent {
    private final ProcessMatchEvent processor;
    private final ScoreUpdateOutbox outbox;
    private final ScoreUpdatePublisher publisher;

    public ProcessAndPublishMatchEvent(ProcessMatchEvent processor, ScoreUpdateOutbox outbox,
                                      ScoreUpdatePublisher publisher) {
        this.processor = Objects.requireNonNull(processor);
        this.outbox = Objects.requireNonNull(outbox);
        this.publisher = Objects.requireNonNull(publisher);
    }

    // One local processing lane; this is not a distributed lock.
    @Override
    public synchronized MatchState process(MatchEvent event) {
        Objects.requireNonNull(event, "Event is required");
        flush(event.matchId());
        var state = processor.process(event);
        flush(event.matchId());
        return state;
    }

    private void flush(String matchId) {
        for (var update : outbox.pendingForMatch(matchId)) {
            publisher.publish(update);
            outbox.markPublished(update);
        }
    }
}
