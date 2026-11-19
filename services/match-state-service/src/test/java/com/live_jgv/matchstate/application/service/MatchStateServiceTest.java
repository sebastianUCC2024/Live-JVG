package com.live_jgv.matchstate.application.service;

import com.live_jgv.matchstate.domain.model.*;
import com.live_jgv.matchstate.application.port.out.MatchStateRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import java.time.Instant;
import java.util.ConcurrentModificationException;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MatchStateServiceTest {
    @Test
    void newBlankReferenceCannotCreateAnIncompatibleOutboxEntry() {
        when(repository.load("m1")).thenReturn(Optional.of(new LoadedMatch(initial, 0)));
        var incompatible = new MatchEvent("bad", "m1", EventType.GOAL, "home", 1, Instant.EPOCH, " ");
        assertThrows(InvalidMatchEventException.class, () -> service.process(incompatible));
        verify(repository).load("m1");
        verifyNoMoreInteractions(repository);
    }

    @Test
    void historicalBlankReferenceIsNotRewrittenOrPublishedAgain() {
        var historical = new MatchEvent("old", "m1", EventType.GOAL, "home", 1, Instant.EPOCH, " ");
        var state = initial.apply(historical);
        when(repository.load("m1")).thenReturn(Optional.of(new LoadedMatch(state, 1)));
        assertSame(state, service.process(historical));
        assertEquals(" ", state.processedEvents().get("old").referenceEventId());
        verify(repository).load("m1");
        verifyNoMoreInteractions(repository);
    }
    private MatchStateRepository repository;
    private MatchStateService service;
    private final MatchState initial = MatchState.start("m1", "home", "away");
    private final MatchEvent goal = new MatchEvent("g1", "m1", EventType.GOAL, "home", 42, Instant.EPOCH, null);

    @BeforeEach
    void setUp() {
        repository = mock(MatchStateRepository.class);
        service = new MatchStateService(repository);
    }

    @Test
    void loadsAppliesAndAtomicallyStoresStateAndHistory() {
        when(repository.load("m1")).thenReturn(Optional.of(new LoadedMatch(initial, 0)));
        when(repository.compareAndSet(eq(new LoadedMatch(initial, 0)), any(), any())).thenReturn(true);
        var updated = service.process(goal);
        var captured = ArgumentCaptor.forClass(MatchState.class);
        var order = inOrder(repository);
        order.verify(repository).load("m1");
        order.verify(repository).compareAndSet(eq(new LoadedMatch(initial, 0)), captured.capture(), any());
        assertSame(updated, captured.getValue());
        assertEquals(new Score(1, 0), updated.score());
        assertEquals(goal, updated.processedEvents().get("g1"));
        assertEquals(new Score(0, 0), initial.score());
    }

    @Test
    void duplicateDoesNotWriteAgain() {
        var scored = initial.apply(goal);
        when(repository.load("m1")).thenReturn(Optional.of(new LoadedMatch(scored, 1)));
        assertSame(scored, service.process(goal));
        verify(repository).load("m1");
        verifyNoMoreInteractions(repository);
    }

    @Test
    void rejectsUnknownMatchWithoutInferringTeamsFromAnEvent() {
        when(repository.load("m1")).thenReturn(Optional.empty());
        assertThrows(IllegalArgumentException.class, () -> service.process(goal));
        verify(repository).load("m1");
        verifyNoMoreInteractions(repository);
    }

    @Test
    void invalidReferenceDoesNotWriteState() {
        when(repository.load("m1")).thenReturn(Optional.of(new LoadedMatch(initial, 0)));
        var annulment = new MatchEvent("a1", "m1", EventType.GOAL_ANNULLED, null, 43, Instant.EPOCH, "g1");
        assertThrows(InvalidMatchEventException.class, () -> service.process(annulment));
        verify(repository).load("m1");
        verifyNoMoreInteractions(repository);
    }

    @Test
    void rejectsRepositoryReturningAnotherMatch() {
        when(repository.load("m1")).thenReturn(Optional.of(new LoadedMatch(MatchState.start("other", "home", "away"), 0)));
        assertThrows(InvalidMatchEventException.class, () -> service.process(goal));
        verify(repository).load("m1");
        verifyNoMoreInteractions(repository);
    }

    @Test
    void reportsConcurrentWriteConflictWithoutClaimingSuccess() {
        when(repository.load("m1")).thenReturn(Optional.of(new LoadedMatch(initial, 0)));
        when(repository.compareAndSet(eq(new LoadedMatch(initial, 0)), any(), any())).thenReturn(false);
        assertThrows(ConcurrentModificationException.class, () -> service.process(goal));
        assertTrue(initial.processedEvents().isEmpty());
    }

    @Test
    void propagatesStorageFailureAndKeepsLoadedSnapshotUnchanged() {
        when(repository.load("m1")).thenReturn(Optional.of(new LoadedMatch(initial, 0)));
        var failure = new IllegalStateException("Storage unavailable");
        when(repository.compareAndSet(eq(new LoadedMatch(initial, 0)), any(), any())).thenThrow(failure);
        assertSame(failure, assertThrows(IllegalStateException.class, () -> service.process(goal)));
        assertTrue(initial.processedEvents().isEmpty());
    }

    @Test
    void rejectsNullInputWithoutStorageAccess() {
        assertThrows(IllegalArgumentException.class, () -> service.process(null));
        verifyNoInteractions(repository);
    }
}
