package com.live_jgv.dashboard.infrastructure.amqp;

/** Intentionally excludes payloads and parser exceptions from the public error. */
public final class InvalidScoreUpdateMessageException extends IllegalArgumentException {
    public InvalidScoreUpdateMessageException() {
        super("Invalid score update message");
    }
}
