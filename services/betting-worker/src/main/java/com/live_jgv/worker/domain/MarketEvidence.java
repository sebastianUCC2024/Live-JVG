package com.live_jgv.worker.domain;

import java.time.Instant;
import java.util.Objects;

/** La evidencia proviene del adaptador de autoridad, nunca del comando recibido. */
public record MarketEvidence(String matchId,String authority,String epoch,long revision,Status status,
                             Instant issuedAt,Instant expiresAt) {
    public enum Status { OPEN, SUSPENDED, UNKNOWN }
    public MarketEvidence {
        if(matchId==null||matchId.isBlank()||revision<0) throw new IllegalArgumentException("Invalid evidence scope");
        Objects.requireNonNull(status);
        if(revision==0) {
            if(status!=Status.UNKNOWN||authority!=null||epoch!=null||issuedAt!=null||expiresAt!=null)
                throw new IllegalArgumentException("Unavailable evidence must remain unknown");
        } else if(authority==null||authority.isBlank()||epoch==null||epoch.isBlank()||issuedAt==null||expiresAt==null||!expiresAt.isAfter(issuedAt))
            throw new IllegalArgumentException("Authority, epoch and finite validity required");
    }
    public static MarketEvidence unavailable(String match) { return new MarketEvidence(match,null,null,0,Status.UNKNOWN,null,null); }
    public boolean available() { return revision>0; }
}
