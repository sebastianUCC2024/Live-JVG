package com.live_jgv.betting.application;

import com.live_jgv.betting.domain.*;

public interface SuspensionStore {
    enum Outcome { APPLIED, DUPLICATE, QUARANTINED, IDENTITY_CONFLICT }
    Outcome handle(InputRecord record, DecodedInput input);
    MarketState status(String matchId);
    /** Trusted internal interface; no wire contract currently authorizes a caller. */
    MarketState closeReview(ReviewClosure closure);
}
