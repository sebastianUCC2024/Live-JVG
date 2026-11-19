package com.live_jgv.worker.domain;

import java.util.Objects;

/** Protege la revisión local; cambiar de autoridad exige reconciliación. */
public record MarketGuard(MarketEvidence evidence,boolean frozen,long version) {
    public MarketGuard { Objects.requireNonNull(evidence);if(version<0)throw new IllegalArgumentException("Invalid fence version"); }
    public static MarketGuard unknown(String match) { return new MarketGuard(MarketEvidence.unavailable(match),false,0); }
}
