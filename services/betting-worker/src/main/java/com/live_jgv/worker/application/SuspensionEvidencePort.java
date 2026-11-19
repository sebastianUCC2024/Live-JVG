package com.live_jgv.worker.application;

import com.live_jgv.worker.domain.MarketEvidence;

/** El adaptador debe verificar autoridad, vigencia y revisiones monotónicas. */
@FunctionalInterface
public interface SuspensionEvidencePort {
    MarketEvidence fetch(String matchId);
}
