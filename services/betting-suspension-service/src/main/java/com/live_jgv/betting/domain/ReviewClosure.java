package com.live_jgv.betting.domain;

/** Internal, audited command. Not a Kafka DTO and not exposed as an HTTP operation. */
public record ReviewClosure(String commandId, String matchId, String reviewId,
                            String continuationEventId, long expectedVersion, String actor, String reason) {
    public ReviewClosure {
        for (String value : new String[]{commandId,matchId,reviewId,continuationEventId,actor,reason})
            if (value == null || value.isBlank()) throw new IllegalArgumentException("Closure fields required");
        if (expectedVersion < 0) throw new IllegalArgumentException("Invalid expected version");
    }
}
