package com.live_jgv.worker.domain;

import java.time.*;

/** Valida la versión persistida y su evidencia vigente; no sustituye un bloqueo distribuido. */
public final class AcceptancePolicy {
    public record Decision(Operation.State state,String reason,MarketGuard guard) { }
    private final boolean enabled;
    private final Duration evidenceAge;
    private final Duration commandAge;
    public AcceptancePolicy(boolean enabled,Duration evidenceAge,Duration commandAge) {
        if(evidenceAge.isNegative()||evidenceAge.isZero()||commandAge.isNegative()||commandAge.isZero())
            throw new IllegalArgumentException("Positive freshness limits required");
        this.enabled=enabled;this.evidenceAge=evidenceAge;this.commandAge=commandAge;
    }
    private static Decision blocked(String reason,MarketGuard guard) { return new Decision(Operation.State.BLOCKED,reason,guard); }
    public Decision decide(SimulatedCommand command,MarketEvidence observed,MarketGuard previous,Instant now) {
        if(!command.matchId().equals(observed.matchId())||!command.matchId().equals(previous.evidence().matchId()))
            return new Decision(Operation.State.FAILED,"EVIDENCE_SCOPE_FAILURE",previous);
        if(command.timestamp().isAfter(now)||command.timestamp().isBefore(now.minus(commandAge)))
            return new Decision(Operation.State.REJECTED,"COMMAND_TIMESTAMP_OUT_OF_RANGE",previous);
        if(previous.frozen()) return blocked("EVIDENCE_RECONCILIATION_REQUIRED",previous);
        if(!observed.available()) return blocked("STATE_UNAVAILABLE_OR_UNKNOWN",previous);
        var stored=previous.evidence();
        if(stored.available()) {
            if(!stored.authority().equals(observed.authority())||!stored.epoch().equals(observed.epoch()))
                return blocked("EVIDENCE_AUTHORITY_CHANGED",new MarketGuard(stored,true,Math.addExact(previous.version(),1)));
            if(observed.revision()<stored.revision()) return blocked("STALE_MARKET_REVISION",previous);
            if(observed.revision()==stored.revision()&&!observed.equals(stored))
                return blocked("EVIDENCE_IDENTITY_CONFLICT",new MarketGuard(stored,true,Math.addExact(previous.version(),1)));
        }
        var next=stored.equals(observed)?previous:new MarketGuard(observed,false,Math.addExact(previous.version(),1));
        if(observed.status()!=MarketEvidence.Status.OPEN) return blocked("MARKET_"+observed.status().name(),next);
        if(observed.issuedAt().isAfter(now)||!observed.expiresAt().isAfter(now)
                ||observed.issuedAt().isBefore(now.minus(evidenceAge))
                ||Duration.between(observed.issuedAt(),observed.expiresAt()).compareTo(evidenceAge)>0)
            return blocked("STALE_OR_INVALID_EVIDENCE_TIME",next);
        if(!enabled) return blocked("ACCEPTANCE_INTEGRATION_DISABLED",next);
        return new Decision(Operation.State.ACCEPTED,"SIMULATED_ACCEPTANCE",next);
    }
}
