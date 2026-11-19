package com.live_jgv.betting.application;

import com.live_jgv.betting.domain.*;

public interface SuspensionStore {
    enum Outcome { APPLIED, DUPLICATE, QUARANTINED, IDENTITY_CONFLICT }
    Outcome handle(InputRecord record, DecodedInput input);
    MarketState status(String matchId);
    /** Contrato interno de confianza; no habilita llamadas externas. */
    MarketState closeReview(ReviewClosure closure);
    /** Adaptador V2 preparado, sin conexión con listeners externos. */
    MarketState closeReviewV2(ReviewClosure closure);
}
