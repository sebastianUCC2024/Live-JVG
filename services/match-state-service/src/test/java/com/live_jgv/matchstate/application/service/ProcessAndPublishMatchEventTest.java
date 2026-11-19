package com.live_jgv.matchstate.application.service;

import com.live_jgv.matchstate.domain.model.*;
import com.live_jgv.matchstate.application.port.out.ScoreUpdatePublisher;
import com.live_jgv.matchstate.infrastructure.memory.InMemoryMatchStateStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ProcessAndPublishMatchEventTest {
    private InMemoryMatchStateStore store;
    private ScoreUpdatePublisher publisher;
    private ProcessAndPublishMatchEvent service;
    private final MatchEvent goal = new MatchEvent("g1", "m1", EventType.GOAL, "home", 1, Instant.EPOCH, null);

    @BeforeEach
    void setUp() {
        store = new InMemoryMatchStateStore(List.of(MatchState.start("m1", "home", "away")));
        publisher = mock(ScoreUpdatePublisher.class);
        service = new ProcessAndPublishMatchEvent(new MatchStateService(store), store, publisher);
    }

    @Test
    void publishesOnceForDuplicateGoalAndDuplicateAnnulment() {
        var scored = service.process(goal);
        assertSame(scored, service.process(goal));
        var annulment = new MatchEvent("a1", "m1", EventType.GOAL_ANNULLED, null, 2, Instant.EPOCH, "g1");
        var annulled = service.process(annulment);
        assertSame(annulled, service.process(annulment));
        assertEquals(new Score(0, 0), annulled.score());
        verify(publisher, times(2)).publish(any());
        assertTrue(store.pendingForMatch("m1").isEmpty());
    }

    @Test
    void publicationFailureKeepsPendingUpdateAndRetryDoesNotApplyGoalAgain() {
        doThrow(new IllegalStateException("broker unavailable")).doNothing().when(publisher).publish(any());
        assertThrows(IllegalStateException.class, () -> service.process(goal));
        assertEquals(new Score(1, 0), store.findById("m1").orElseThrow().score());
        var pending = store.pendingForMatch("m1").get(0);
        service.process(goal);
        assertEquals(new Score(1, 0), store.findById("m1").orElseThrow().score());
        verify(publisher, times(2)).publish(same(pending));
        assertTrue(store.pendingForMatch("m1").isEmpty());
    }

    @Test
    void flushesEarlierPendingUpdateBeforeAcceptingNextEvent() {
        doThrow(new IllegalStateException()).doNothing().when(publisher).publish(any());
        assertThrows(IllegalStateException.class, () -> service.process(goal));
        var next = new MatchEvent("g2", "m1", EventType.GOAL, "away", 2, Instant.EPOCH, null);
        service.process(next);
        var updates = org.mockito.ArgumentCaptor.forClass(ScoreUpdate.class);
        verify(publisher, times(3)).publish(updates.capture());
        assertEquals(List.of("g1", "g1", "g2"), updates.getAllValues().stream().map(ScoreUpdate::eventId).toList());
        assertEquals(new Score(1, 1), store.findById("m1").orElseThrow().score());
    }

    @Test
    void invalidEventChangesNeitherStateNorPendingPublications() {
        var invalid = new MatchEvent("g1", "m1", EventType.GOAL, "unknown", 1, Instant.EPOCH, null);
        assertThrows(InvalidMatchEventException.class, () -> service.process(invalid));
        assertEquals(new Score(0, 0), store.findById("m1").orElseThrow().score());
        assertTrue(store.pendingForMatch("m1").isEmpty());
        verifyNoInteractions(publisher);
    }

    @Test void stalledPublicationDoesNotBlockAnotherMatchOnTheSameService() throws Exception {
        var both = new InMemoryMatchStateStore(List.of(MatchState.start("m1","home","away"),MatchState.start("m2","home","away")));
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        doAnswer(call -> {
            if (call.getArgument(0,ScoreUpdate.class).matchId().equals("m1")) {
                entered.countDown();assertTrue(release.await(5,java.util.concurrent.TimeUnit.SECONDS));
            }
            return null;
        }).when(publisher).publish(any());
        var lane = new ProcessAndPublishMatchEvent(new MatchStateService(both),both,publisher);
        var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var first=pool.submit(()->lane.process(goal));
            assertTrue(entered.await(5,java.util.concurrent.TimeUnit.SECONDS));
            var second=pool.submit(()->lane.process(new MatchEvent("g2","m2",EventType.GOAL,"home",1,Instant.EPOCH,null)));
            assertEquals(new Score(1,0),second.get(5,java.util.concurrent.TimeUnit.SECONDS).score());
            release.countDown();first.get(5,java.util.concurrent.TimeUnit.SECONDS);
        } finally { release.countDown();pool.shutdownNow(); }
    }

    @Test void drainsEveryBatchBeforeCompletingRecovery() {
        var memory=new InMemoryMatchStateStore(List.of(MatchState.start("m1","home","away")));
        var processor=new MatchStateService(memory);
        for(int i=0;i<350;i++) processor.process(new MatchEvent("e"+i,"m1",EventType.MATCH_CONTINUES,null,1,Instant.EPOCH,null));
        var batched=new com.live_jgv.matchstate.application.port.out.ScoreUpdateOutbox() {
            public List<ScoreUpdate> pendingForMatch(String match){return memory.pendingForMatch(match).stream().limit(128).toList();}
            public void markPublished(ScoreUpdate update){memory.markPublished(update);}
        };
        var lane=new ProcessAndPublishMatchEvent(processor,batched,publisher);
        lane.recoverPendingMatch("m1");
        assertTrue(memory.pendingForMatch("m1").isEmpty());
        verify(publisher,times(350)).publish(any());
    }
}
