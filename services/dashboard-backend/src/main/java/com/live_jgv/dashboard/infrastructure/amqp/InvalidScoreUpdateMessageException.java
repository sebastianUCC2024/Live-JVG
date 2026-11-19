package com.live_jgv.dashboard.infrastructure.amqp;

/** El error público omite payloads y detalles del parser. */
public final class InvalidScoreUpdateMessageException extends IllegalArgumentException {
    public InvalidScoreUpdateMessageException() {
        super("Invalid score update message");
    }
}
