package com.live_jgv.archiver.application;

import com.live_jgv.archiver.domain.*;

/** Retorna después de confirmar el evento o su cuarentena persistente. */
public interface ArchiveStore {
    ArchiveOutcome save(InputRecord record, DecodedInput input);
    HistoryPage find(String matchId, int page, int size);
}
