package com.live_jgv.matchstate.infrastructure.memory;

import com.live_jgv.matchstate.domain.model.*;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class InMemoryMatchStateStoreTest {
    private final MatchState empty = MatchState.start("m1", "home", "away");
    private final MatchEvent goal = new MatchEvent("g1", "m1", EventType.GOAL, "home", 1, Instant.EPOCH, null);
    private final ScoreUpdate update = new ScoreUpdate("m1", 1, 0, "g1", EventType.GOAL, Instant.EPOCH, VarStatus.CLEAR, null);

    @Test
    void commitsStateAndPendingOutputTogetherAndRejectsStaleSnapshots() {
        var store = new InMemoryMatchStateStore(List.of(empty));
        var scored = empty.apply(goal);
        assertTrue(store.compareAndSet(empty, scored, update));
        assertSame(scored, store.findById("m1").orElseThrow());
        assertEquals(List.of(update), store.pendingForMatch("m1"));
        assertFalse(store.compareAndSet(empty, scored, update));
        assertEquals(1, store.pendingForMatch("m1").size());
        store.markPublished(update);
        assertTrue(store.pendingForMatch("m1").isEmpty());
        assertSame(scored, store.findById("m1").orElseThrow());
    }

    @Test
    void rejectsMismatchedAcknowledgementAndProtectsPendingCollection() {
        var store = new InMemoryMatchStateStore(List.of(empty));
        assertTrue(store.compareAndSet(empty, empty.apply(goal), update));
        assertThrows(UnsupportedOperationException.class, () -> store.pendingForMatch("m1").clear());
        var wrong = new ScoreUpdate("m1", 99, 0, "g1", EventType.GOAL, Instant.EPOCH, VarStatus.CLEAR, null);
        assertThrows(IllegalStateException.class, () -> store.markPublished(wrong));
        assertEquals(List.of(update), store.pendingForMatch("m1"));
    }

    @Test
    void newStoreDoesNotRecoverStateOrOutboxAfterRestart() {
        var before = new InMemoryMatchStateStore(List.of(empty));
        before.compareAndSet(empty, empty.apply(goal), update);
        var restarted = new InMemoryMatchStateStore(List.of(MatchState.start("m1", "home", "away")));
        assertEquals(new Score(0, 0), restarted.findById("m1").orElseThrow().score());
        assertTrue(restarted.pendingForMatch("m1").isEmpty());
    }

    @Test
    void rejectsDuplicateMatchDefinitions() {
        assertThrows(IllegalArgumentException.class, () -> new InMemoryMatchStateStore(List.of(empty, empty)));
    }
}
