package com.live_jgv.matchstate.infrastructure.rabbit;

public final class ScoreUpdatePublicationException extends RuntimeException {
    public ScoreUpdatePublicationException(String message) { super(message); }
    public ScoreUpdatePublicationException(String message, Throwable cause) { super(message, cause); }
}
