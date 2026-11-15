package com.live_jgv.matchstate.application.service;

import com.live_jgv.matchstate.domain.model.*;
import com.live_jgv.matchstate.domain.port.MatchStateRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import java.time.Instant;
import java.util.ConcurrentModificationException;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MatchStateServiceTest {
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
        when(repository.findById("m1")).thenReturn(Optional.of(initial));
        when(repository.compareAndSet(eq(initial), any(), any())).thenReturn(true);
        var updated = service.process(goal);
        var captured = ArgumentCaptor.forClass(MatchState.class);
        var order = inOrder(repository);
        order.verify(repository).findById("m1");
        order.verify(repository).compareAndSet(same(initial), captured.capture(), any());
        assertSame(updated, captured.getValue());
        assertEquals(new Score(1, 0), updated.score());
        assertEquals(goal, updated.processedEvents().get("g1"));
        assertEquals(new Score(0, 0), initial.score());
    }

    @Test
    void duplicateDoesNotWriteAgain() {
        var scored = initial.apply(goal);
        when(repository.findById("m1")).thenReturn(Optional.of(scored));
        assertSame(scored, service.process(goal));
        verify(repository).findById("m1");
        verifyNoMoreInteractions(repository);
    }

    @Test
    void rejectsUnknownMatchWithoutInferringTeamsFromAnEvent() {
        when(repository.findById("m1")).thenReturn(Optional.empty());
        assertThrows(IllegalArgumentException.class, () -> service.process(goal));
        verify(repository).findById("m1");
        verifyNoMoreInteractions(repository);
    }

    @Test
    void invalidReferenceDoesNotWriteState() {
        when(repository.findById("m1")).thenReturn(Optional.of(initial));
        var annulment = new MatchEvent("a1", "m1", EventType.GOAL_ANNULLED, null, 43, Instant.EPOCH, "g1");
        assertThrows(InvalidMatchEventException.class, () -> service.process(annulment));
        verify(repository).findById("m1");
        verifyNoMoreInteractions(repository);
    }

    @Test
    void rejectsRepositoryReturningAnotherMatch() {
        when(repository.findById("m1")).thenReturn(Optional.of(MatchState.start("other", "home", "away")));
        assertThrows(InvalidMatchEventException.class, () -> service.process(goal));
        verify(repository).findById("m1");
        verifyNoMoreInteractions(repository);
    }

    @Test
    void reportsConcurrentWriteConflictWithoutClaimingSuccess() {
        when(repository.findById("m1")).thenReturn(Optional.of(initial));
        when(repository.compareAndSet(eq(initial), any(), any())).thenReturn(false);
        assertThrows(ConcurrentModificationException.class, () -> service.process(goal));
        assertTrue(initial.processedEvents().isEmpty());
    }

    @Test
    void propagatesStorageFailureAndKeepsLoadedSnapshotUnchanged() {
        when(repository.findById("m1")).thenReturn(Optional.of(initial));
        var failure = new IllegalStateException("Storage unavailable");
        when(repository.compareAndSet(eq(initial), any(), any())).thenThrow(failure);
        assertSame(failure, assertThrows(IllegalStateException.class, () -> service.process(goal)));
        assertTrue(initial.processedEvents().isEmpty());
    }

    @Test
    void rejectsNullInputWithoutStorageAccess() {
        assertThrows(IllegalArgumentException.class, () -> service.process(null));
        verifyNoInteractions(repository);
    }
}
