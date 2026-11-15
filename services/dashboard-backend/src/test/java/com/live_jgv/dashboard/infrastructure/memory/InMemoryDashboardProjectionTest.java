package com.live_jgv.dashboard.infrastructure.memory;

import com.live_jgv.dashboard.domain.model.*;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class InMemoryDashboardProjectionTest {
    private ScoreUpdate update(String match, String event, int score) {
        return new ScoreUpdate(match, score, 0, event, EventType.GOAL, Instant.EPOCH, VarStatus.CLEAR, null);
    }

    @Test
    void duplicateDoesNotRegressTheLatestSnapshot() {
        var projection = new InMemoryDashboardProjection(2);
        var first = update("m", "e1", 1);
        var second = update("m", "e2", 2);
        projection.publish(first);
        projection.publish(second);
        projection.publish(first);
        assertSame(second, projection.findByMatchId("m").orElseThrow());
    }

    @Test
    void timestampOnlyReplayIsIgnoredWithoutReplacingTheLatestSnapshot() {
        var projection = new InMemoryDashboardProjection(2);
        var first = update("m", "e1", 1);
        var second = update("m", "e2", 2);
        projection.publish(first);
        projection.publish(new ScoreUpdate("m", 1, 0, "e1", EventType.GOAL,
                Instant.EPOCH.plusSeconds(5), VarStatus.CLEAR, null));
        assertSame(first, projection.findByMatchId("m").orElseThrow());
        projection.publish(second);
        projection.publish(new ScoreUpdate("m", 1, 0, "e1", EventType.GOAL,
                Instant.EPOCH.plusSeconds(10), VarStatus.CLEAR, null));
        assertSame(second, projection.findByMatchId("m").orElseThrow());
    }

    @Test
    void timestampChangesDoNotHideConflictsInOtherFields() {
        var projection = new InMemoryDashboardProjection(10);
        var original = update("m", "e", 1);
        projection.publish(original);
        var later = Instant.EPOCH.plusSeconds(10);
        var conflicts = java.util.List.of(
                new ScoreUpdate("m", 2, 0, "e", EventType.GOAL, later, VarStatus.CLEAR, null),
                new ScoreUpdate("m", 1, 1, "e", EventType.GOAL, later, VarStatus.CLEAR, null),
                new ScoreUpdate("m", 1, 0, "e", EventType.MATCH_CONTINUES, later, VarStatus.CLEAR, null),
                new ScoreUpdate("m", 1, 0, "e", EventType.GOAL, later, VarStatus.IN_PROGRESS, null),
                new ScoreUpdate("m", 1, 0, "e", EventType.GOAL, later, VarStatus.CLEAR, "different"));
        for (var conflict : conflicts) {
            assertThrows(InvalidScoreUpdateException.class, () -> projection.publish(conflict));
            assertSame(original, projection.findByMatchId("m").orElseThrow());
        }
    }

    @Test
    void conflictingDuplicateDoesNotMutateState() {
        var projection = new InMemoryDashboardProjection(10);
        var original = update("m", "e", 1);
        projection.publish(original);
        assertThrows(InvalidScoreUpdateException.class, () -> projection.publish(update("m", "e", 2)));
        assertSame(original, projection.findByMatchId("m").orElseThrow());
    }

    @Test
    void deduplicationIsScopedByMatch() {
        var projection = new InMemoryDashboardProjection(10);
        projection.publish(update("m1", "e", 1));
        projection.publish(update("m2", "e", 3));
        assertEquals(1, projection.findByMatchId("m1").orElseThrow().homeScore());
        assertEquals(3, projection.findByMatchId("m2").orElseThrow().homeScore());
    }

    @Test
    void capacityRejectsNewEventsWithoutEvictingDeduplicationOrUpdatingState() {
        var projection = new InMemoryDashboardProjection(1);
        var original = update("m", "e1", 1);
        projection.publish(original);
        assertThrows(IllegalStateException.class, () -> projection.publish(update("m", "e2", 2)));
        projection.publish(original);
        assertSame(original, projection.findByMatchId("m").orElseThrow());
    }

    @Test
    void restartDoesNotRecoverState() {
        var original = new InMemoryDashboardProjection(1);
        original.publish(update("m", "e", 1));
        assertTrue(new InMemoryDashboardProjection(1).findByMatchId("m").isEmpty());
        assertThrows(IllegalArgumentException.class, () -> new InMemoryDashboardProjection(0));
    }
}
