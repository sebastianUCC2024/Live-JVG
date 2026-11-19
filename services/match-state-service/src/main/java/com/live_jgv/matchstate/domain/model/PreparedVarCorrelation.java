package com.live_jgv.matchstate.domain.model;

import java.util.HashMap;
import java.util.Map;

/** Correlación VAR V2 para pruebas locales; no activa cambios en el protocolo V1. */
public final class PreparedVarCorrelation {
    private final VarReviews reviews;
    private final Map<String, MatchEvent> events;

    private PreparedVarCorrelation(VarReviews reviews, Map<String, MatchEvent> events) {
        this.reviews = reviews;
        this.events = Map.copyOf(events);
    }

    public static PreparedVarCorrelation empty() {
        return new PreparedVarCorrelation(VarReviews.empty(), Map.of());
    }

    public PreparedVarCorrelation apply(MatchEvent event) {
        if (event == null) throw new IllegalArgumentException("Event required");
        if (!events.isEmpty() && !events.values().iterator().next().matchId().equals(event.matchId())) {
            throw new IllegalArgumentException("Different match");
        }
        var previous = events.get(event.eventId());
        if (previous != null) {
            if (!previous.equals(event)) throw new IllegalArgumentException("Conflicting event identity");
            return this;
        }
        var next = reviews;
        switch (event.eventType()) {
            case GOAL -> { /* No calcula el marcador ni autoriza operaciones del mercado. */ }
            case VAR_CHECK -> {
                if (event.referenceEventId() != null) {
                    var goal = events.get(event.referenceEventId());
                    if (goal == null || goal.eventType() != EventType.GOAL) {
                        throw new IllegalArgumentException("Unknown or non-goal opening reference");
                    }
                }
                next = reviews.open(event.eventId());
            }
            case MATCH_CONTINUES -> {
                // No deduce correlaciones por tiempo ni por cantidad de revisiones abiertas.
                next = reviews.close(event.referenceEventId());
            }
            case MATCH_FINISHED -> { }
            case GOAL_ANNULLED -> throw new UnsupportedOperationException("Annulment mapping requires team approval");
        }
        var accepted = new HashMap<>(events);
        accepted.put(event.eventId(), event);
        return new PreparedVarCorrelation(next, accepted);
    }

    public static PreparedVarCorrelation replay(Iterable<MatchEvent> events) {
        var state = empty();
        for (var event : events) state = state.apply(event);
        return state;
    }

    public VarReviews reviews() { return reviews; }
    public Map<String, MatchEvent> events() { return events; }
}
