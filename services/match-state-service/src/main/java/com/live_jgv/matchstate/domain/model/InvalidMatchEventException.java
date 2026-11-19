package com.live_jgv.matchstate.domain.model;

/** Rechazo de negocio que no altera el estado ni el historial aceptado. */
public final class InvalidMatchEventException extends IllegalArgumentException {
    public enum Reason { DOMAIN_INVALID, ID_CONFLICT, UNKNOWN_TEAM, WAITING_REFERENCE, REFERENCE_NOT_GOAL, GOAL_ALREADY_ANNULLED }
    private final Reason reason;
    public InvalidMatchEventException(String message) { this(Reason.DOMAIN_INVALID, message); }
    public InvalidMatchEventException(Reason reason, String message) { super(message); this.reason = reason; }
    public Reason reason() { return reason; }
}
