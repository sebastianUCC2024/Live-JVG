package com.live_jgv.betting.infrastructure;

import com.live_jgv.betting.application.LeaseSourceVerifier;

/** Sin adaptador de autoridad disponible: no concede confianza implícita. */
public final class UnavailableLeaseSourceVerifier implements LeaseSourceVerifier {
    @Override public boolean verify(String matchId,long marketVersion,String sourceCheckpoint){return false;}
}
