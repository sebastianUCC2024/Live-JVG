package com.live_jgv.dashboard.application.service;

import com.live_jgv.dashboard.domain.model.*;
import java.time.Instant;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ProcessScoreUpdateServiceTest {
    private final ScoreUpdate update = new ScoreUpdate("m", 0, 1, "e", EventType.GOAL_ANNULLED,
            Instant.EPOCH, VarStatus.CLEAR, "goal-1");

    @Test
    void forwardsTheAuthoritativeSnapshotWithoutRecalculating() {
        var published = new ArrayList<ScoreUpdate>();
        new ProcessScoreUpdateService(published::add).process(update);
        assertEquals(1, published.size());
        assertSame(update, published.get(0));
    }

    @Test
    void rejectsNullBeforeInvokingOutputPort() {
        var published = new ArrayList<ScoreUpdate>();
        var service = new ProcessScoreUpdateService(published::add);
        assertThrows(InvalidScoreUpdateException.class, () -> service.process(null));
        assertTrue(published.isEmpty());
    }

    @Test
    void propagatesDeliveryFailureToCaller() {
        var failure = new IllegalStateException("Output unavailable");
        var service = new ProcessScoreUpdateService(value -> { throw failure; });
        assertSame(failure, assertThrows(IllegalStateException.class, () -> service.process(update)));
    }

    @Test
    void requiresAnOutputAdapter() {
        assertThrows(NullPointerException.class, () -> new ProcessScoreUpdateService(null));
    }
}
