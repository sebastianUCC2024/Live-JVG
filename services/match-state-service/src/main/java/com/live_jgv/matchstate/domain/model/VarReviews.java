package com.live_jgv.matchstate.domain.model;

import java.util.HashMap;
import java.util.Map;

/** Internal correlation primitive. No wire protocol or V1 aggregate activation. */
public final class VarReviews {
    private final Map<String, Boolean> reviews;

    private VarReviews(Map<String, Boolean> reviews) {
        this.reviews = Map.copyOf(reviews);
    }

    public static VarReviews empty() { return new VarReviews(Map.of()); }

    public VarReviews open(String reviewId) {
        requireId(reviewId);
        // Identity survives closure: a redelivered opening cannot reopen it.
        if (reviews.containsKey(reviewId)) return this;
        var next = new HashMap<>(reviews);
        next.put(reviewId, true);
        return new VarReviews(next);
    }

    public VarReviews close(String reviewId) {
        requireId(reviewId);
        if (!reviews.containsKey(reviewId)) {
            throw new IllegalArgumentException("Unknown review; retain input for explicit resolution");
        }
        if (!reviews.get(reviewId)) return this;
        var next = new HashMap<>(reviews);
        next.put(reviewId, false);
        return new VarReviews(next);
    }

    public VarStatus status() {
        return reviews.containsValue(true) ? VarStatus.IN_PROGRESS : VarStatus.CLEAR;
    }

    public Map<String, Boolean> reviews() { return reviews; }

    private static void requireId(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Review ID required");
    }
}
