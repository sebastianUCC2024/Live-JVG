package com.live_jgv.notification.application;
import com.live_jgv.notification.domain.*;
import java.time.Clock;

/**
 * Creates notification content and delegates atomic inbox/alert acceptance to NotificationStore.
 * Legacy score messages use the local clock and policy; live-alert messages retain their
 * upstream identity and content. The store owns duplicate detection and transaction boundaries.
 */
public final class ProcessScoreUpdateService {
    private final NotificationStore store;
    private final NotificationPolicy policy;
    private final Clock clock;
    public ProcessScoreUpdateService(NotificationStore store, NotificationPolicy policy, Clock clock) {
        this.store = store; this.policy = policy; this.clock = clock;
    }
    public NotificationStore.Result process(LiveAlertV1 alert) { return store.acceptLiveAlert(alert); }
    public NotificationStore.Result process(ScoreUpdate update) {
        return store.accept(update, policy.create(update, clock.instant()));
    }
}
