package com.live_jgv.matchstate.domain.model;

/** A semantic rejection; no state or processed-event history has been changed. */
public final class InvalidMatchEventException extends IllegalArgumentException {
    public InvalidMatchEventException(String message) { super(message); }
}
