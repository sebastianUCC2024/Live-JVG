package com.live_jgv.dashboard.application.service;

import com.live_jgv.dashboard.domain.model.InvalidScoreUpdateException;
import com.live_jgv.dashboard.domain.model.ScoreUpdate;
import com.live_jgv.dashboard.domain.port.in.ProcessScoreUpdate;
import com.live_jgv.dashboard.domain.port.out.DashboardUpdatePublisher;
import java.util.Objects;

public final class ProcessScoreUpdateService implements ProcessScoreUpdate {
    private final DashboardUpdatePublisher publisher;

    public ProcessScoreUpdateService(DashboardUpdatePublisher publisher) {
        this.publisher = Objects.requireNonNull(publisher, "publisher is required");
    }

    @Override
    public void process(ScoreUpdate update) {
        if (update == null) {
            throw new InvalidScoreUpdateException("Score update is required");
        }
        publisher.publish(update);
    }
}
