package com.live_jgv.betting.application;

import com.live_jgv.betting.domain.*;

public interface SuspensionStore {
    enum Outcome { APPLIED, DUPLICATE, QUARANTINED, IDENTITY_CONFLICT }
    Outcome handle(InputRecord record, DecodedInput input);
    MarketState status(String matchId);
    /** Trusted internal interface; no wire contract currently authorizes a caller. */
    MarketState closeReview(ReviewClosure closure);
    /** Prepared internal V2 adapter. It is not connected to an external listener. */
    MarketState closeReviewV2(ReviewClosure closure);
}
