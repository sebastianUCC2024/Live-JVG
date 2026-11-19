package com.live_jgv.notification.application;
import com.live_jgv.notification.domain.*;
import java.util.List;
import java.util.Optional;

public interface NotificationStore {
    /** Guarda inbox y alerta opcional de forma atómica antes de retornar. */
    Result accept(ScoreUpdate update, Optional<LiveAlert> alert);
    default Result acceptLiveAlert(com.live_jgv.notification.domain.LiveAlertV1 alert) {
        throw new com.live_jgv.notification.application.StorageUnavailableException();
    }
    AlertPage query(String matchId, String after, int limit);
    enum Result { CREATED, SUPPRESSED, DUPLICATE }
    record AlertPage(List<LiveAlert> items, String nextCursor, boolean hasMore) { }
}
