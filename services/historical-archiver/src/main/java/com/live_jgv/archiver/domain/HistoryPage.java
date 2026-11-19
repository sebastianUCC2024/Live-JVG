package com.live_jgv.archiver.domain;

import java.time.Instant;
import java.util.List;

public record HistoryPage(List<Entry> content, int page, int size, long totalElements, long totalPages) {
    public HistoryPage { content = List.copyOf(content); }
    public record Entry(long sequence, String eventId, String matchId, String eventType,
                        String teamId, int minute, Instant timestamp, String referenceEventId,
                        Instant receivedAt) { }
}
