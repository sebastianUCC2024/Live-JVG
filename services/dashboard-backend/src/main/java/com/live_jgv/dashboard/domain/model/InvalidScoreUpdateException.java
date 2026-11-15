package com.live_jgv.dashboard.domain.model;

public final class InvalidScoreUpdateException extends IllegalArgumentException {
    public InvalidScoreUpdateException(String message) {
        super(message);
    }
}
