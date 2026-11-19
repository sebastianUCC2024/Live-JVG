package com.live_jgv.matchstate.domain.model;

import java.util.UUID;
import java.util.Objects;

public record ReplayRequest(UUID replayId, UUID caseId, String actor, String reason, long expectedRevision) {
    public ReplayRequest {
        Objects.requireNonNull(replayId); Objects.requireNonNull(caseId);
        if (actor == null || actor.isBlank() || reason == null || reason.isBlank() || expectedRevision < 0) {
            throw new IllegalArgumentException("Replay requires actor, reason and nonnegative expected revision");
        }
    }
}
