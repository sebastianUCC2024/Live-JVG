package com.live_jgv.dashboard.domain.model;

import java.util.Objects;

/** Revision is local to streamId, not a sequence provided by DEV 1. */
public record MatchSnapshot(String streamId, long revision, ScoreUpdate update) {
    public MatchSnapshot {
        if (streamId == null || streamId.isBlank() || revision < 1) {
            throw new IllegalArgumentException("Stream identity and positive revision are required");
        }
        Objects.requireNonNull(update, "update");
    }
}
