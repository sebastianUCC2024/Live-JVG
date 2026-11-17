package com.live_jgv.notification.application;
import com.live_jgv.notification.domain.*;
import java.time.Clock;

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
