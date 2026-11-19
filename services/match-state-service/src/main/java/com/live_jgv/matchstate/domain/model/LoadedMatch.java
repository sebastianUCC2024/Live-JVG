package com.live_jgv.matchstate.domain.model;

import java.util.Objects;

/** Revisión de almacenamiento independiente de la identidad del objeto Java. */
public record LoadedMatch(MatchState state, long revision) {
    public LoadedMatch {
        Objects.requireNonNull(state);
        if (revision < 0 || revision != state.processedEvents().size()) {
            throw new IllegalArgumentException("Revision must match accepted history length");
        }
    }
}
