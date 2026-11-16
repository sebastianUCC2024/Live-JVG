package com.live_jgv.betting.application;

import com.live_jgv.betting.domain.*;

public final class SuspensionService {
    private final SuspensionStore store;
    private final EventDecoder decoder;
    public SuspensionService(SuspensionStore store, EventDecoder decoder) { this.store=store;this.decoder=decoder; }
    public SuspensionStore.Outcome receive(InputRecord record) { return store.handle(record,decoder.decode(record)); }
    public MarketState status(String match) {
        if (match==null || match.isBlank()) throw new IllegalArgumentException("Match required");
        return store.status(match);
    }
    public MarketState closeReview(ReviewClosure closure) { return store.closeReview(closure); }
}
