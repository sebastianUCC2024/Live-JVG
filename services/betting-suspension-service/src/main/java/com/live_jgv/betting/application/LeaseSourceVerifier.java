package com.live_jgv.betting.application;

/** Internal trust boundary. An opaque checkpoint alone is never proof of source freshness. */
@FunctionalInterface
public interface LeaseSourceVerifier {
    /** Called under the market lock. Implementations require approval and bounded execution. */
    boolean verify(String matchId,long marketVersion,String sourceCheckpoint);
}
