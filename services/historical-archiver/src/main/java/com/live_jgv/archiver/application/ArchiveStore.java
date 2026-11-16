package com.live_jgv.archiver.application;

import com.live_jgv.archiver.domain.*;

/** Save must commit the event or durable quarantine before returning. */
public interface ArchiveStore {
    ArchiveOutcome save(InputRecord record, DecodedInput input);
    HistoryPage find(String matchId, int page, int size);
}
