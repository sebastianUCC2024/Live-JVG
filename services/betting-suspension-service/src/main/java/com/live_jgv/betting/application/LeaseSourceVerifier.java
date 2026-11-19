package com.live_jgv.betting.application;

/** Verifica la vigencia de la fuente; un checkpoint aislado no demuestra actualidad. */
@FunctionalInterface
public interface LeaseSourceVerifier {
    /** Se ejecuta con el mercado bloqueado y debe tener una duración acotada. */
    boolean verify(String matchId,long marketVersion,String sourceCheckpoint);
}
