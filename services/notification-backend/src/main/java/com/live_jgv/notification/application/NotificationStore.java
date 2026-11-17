package com.live_jgv.notification.application;
import com.live_jgv.notification.domain.*;
import java.util.List;
import java.util.Optional;

public interface NotificationStore {
    /** Atomically persists inbox + optional alert; returns only after commit. */
    Result accept(ScoreUpdate update, Optional<LiveAlert> alert);
    default Result acceptLiveAlert(com.live_jgv.notification.domain.LiveAlertV1 alert) {
        throw new com.live_jgv.notification.application.StorageUnavailableException();
    }
    AlertPage query(String matchId, String after, int limit);
    enum Result { CREATED, SUPPRESSED, DUPLICATE }
    record AlertPage(List<LiveAlert> items, String nextCursor, boolean hasMore) { }
}
