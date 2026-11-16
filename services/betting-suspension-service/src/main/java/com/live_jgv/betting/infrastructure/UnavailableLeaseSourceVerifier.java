package com.live_jgv.betting.infrastructure;

import com.live_jgv.betting.application.LeaseSourceVerifier;

/** No approved authority adapter exists yet. No network, credentials, signatures or implicit trust. */
public final class UnavailableLeaseSourceVerifier implements LeaseSourceVerifier {
    @Override public boolean verify(String matchId,long marketVersion,String sourceCheckpoint){return false;}
}
