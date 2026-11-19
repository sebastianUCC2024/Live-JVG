package com.live_jgv.archiver.application;

import com.live_jgv.archiver.domain.*;
import java.util.Objects;

public final class ArchiveService {
    private final ArchiveStore store;
    private final EventDecoder decoder;
    public ArchiveService(ArchiveStore store, EventDecoder decoder) {
        this.store = Objects.requireNonNull(store); this.decoder = Objects.requireNonNull(decoder);
    }
    public ArchiveOutcome archive(InputRecord record) {
        // Propaga los fallos de persistencia para impedir confirmar el offset de Kafka.
        return store.save(record, decoder.decode(record));
    }
    public HistoryPage history(String match, int page, int size) {
        if (match == null || match.isBlank() || page < 0 || size < 1 || size > 200)
            throw new IllegalArgumentException("Invalid history query");
        return store.find(match, page, size);
    }
}
