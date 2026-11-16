package com.live_jgv.worker.application;

import com.live_jgv.worker.domain.MarketEvidence;

/** Future adapter must authenticate authority, bound time and provide monotonic epoch/revision evidence. */
@FunctionalInterface
public interface SuspensionEvidencePort {
    MarketEvidence fetch(String matchId);
}
