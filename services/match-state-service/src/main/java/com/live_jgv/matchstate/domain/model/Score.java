package com.live_jgv.matchstate.domain.model;

public record Score(int home, int away) {
    public Score {
        if (home < 0 || away < 0) {
            throw new IllegalArgumentException("Score must not be negative");
        }
    }
}
