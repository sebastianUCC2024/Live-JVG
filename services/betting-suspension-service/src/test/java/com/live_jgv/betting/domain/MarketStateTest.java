package com.live_jgv.betting.domain;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class MarketStateTest {
    @Test void proposedV2ReviewReferenceIsNotSilentlyInterpretedAsV1GoalReference() {
        var v2=new SportEvent("continue","m",SportEvent.EventType.MATCH_CONTINUES,null,10,now,"var");
        var state=review().apply(v2,now);
        assertTrue(state.pendingReviews().containsKey("var"));assertFalse(state.betsAllowed());
        assertThrows(IllegalArgumentException.class,()->state.close(close("var",2),v2,2,now));
        // Mapping a review ID instead of a goal ID requires the approved V2 adapter, not a guessed fallback.
    }
    @Test void goalSuspendsAnOpenMarketAndAddsItsOwnDurableHold() {
        var open=review().apply(continuation(),now).close(close("var",2),continuation(),2,now);
        var suspended=open.apply(event("new-goal",SportEvent.EventType.GOAL,null),now);
        assertEquals(MarketState.Status.SUSPENDED,suspended.status());assertFalse(suspended.betsAllowed());
        assertEquals("new-goal",suspended.pendingReviews().get("new-goal").referenceEventId());
        assertEquals(open.version()+1,suspended.version());
    }
    @Test void goalAndVarHoldsRequireExplicitClosuresWithoutImplicitMerging() {
        var state=MarketState.unknown("m").apply(event("goal",SportEvent.EventType.GOAL,null),now)
                .apply(event("var",SportEvent.EventType.VAR_CHECK,"goal"),now).apply(continuation(),now);
        assertEquals(2,state.pendingReviews().size());
        var first=state.close(close("var",3),continuation(),3,now);
        assertFalse(first.betsAllowed());assertTrue(first.pendingReviews().containsKey("goal"));
        assertTrue(first.close(close("goal",4),continuation(),3,now).betsAllowed());
    }
    @Test void expiredOrNotYetIssuedClosureEvidenceCannotOpenMarket() {
        var state=review().apply(continuation(),now);
        for(var issued:java.util.List.of(now.minusSeconds(10),now.plusSeconds(1))) {
            var proof=new ReviewClosure("c","m","var","continue",2,"operator","verified",issued,issued.plusSeconds(5));
            assertThrows(IllegalArgumentException.class,()->state.close(proof,continuation(),2,now));
        }
        var boundary=new ReviewClosure("c","m","var","continue",2,"operator","verified",now.minusSeconds(5),now);
        assertThrows(IllegalArgumentException.class,()->state.close(boundary,continuation(),2,now));
    }
    @Test void invalidEvidenceIntervalIsRejectedBeforeAnyClosure() {
        assertThrows(IllegalArgumentException.class,()->new ReviewClosure("c","m","var","continue",2,"o","r",now,now));
    }
    @Test void oldContinuationCannotBeUsedAfterNewBlockingEventEvenWithFreshVersion() {
        var state=review().apply(continuation(),now).apply(event("another-var",SportEvent.EventType.VAR_CHECK,"other-goal"),now);
        assertThrows(IllegalArgumentException.class,()->state.close(close("var",3),continuation(),2,now));
    }
    @Test void explicitForeignReferenceCannotCloseUnrelatedReview() {
        var foreign=event("continue",SportEvent.EventType.MATCH_CONTINUES,"other-goal");
        var state=review().apply(foreign,now);
        assertThrows(IllegalArgumentException.class,()->state.close(close("var",2),foreign,2,now));
    }
    @Test void futureContinuationOrInventedLedgerRevisionCannotOpen() {
        var state=review().apply(continuation(),now);
        assertThrows(IllegalArgumentException.class,()->state.close(close("var",2),continuation(),3,now));
        var future=new SportEvent("continue","m",SportEvent.EventType.MATCH_CONTINUES,null,10,now.plusSeconds(1),null);
        assertThrows(IllegalArgumentException.class,()->state.close(close("var",2),future,2,now));
    }
    private final Instant now=Instant.parse("2026-11-15T00:00:00.123456789Z");
    private SportEvent event(String id,SportEvent.EventType type,String ref) { return new SportEvent(id,"m",type,"home",10,now,ref); }
    private MarketState review() { return MarketState.unknown("m").apply(event("var",SportEvent.EventType.VAR_CHECK,"goal"),now); }
    private ReviewClosure close(String review,long version) { return new ReviewClosure("command-"+review,"m",review,"continue",version,"operator","verified decision",now.minusSeconds(1),now.plusSeconds(5)); }
    private SportEvent continuation() { return event("continue",SportEvent.EventType.MATCH_CONTINUES,null); }
    @Test void unknownNeverAllowsBets() { assertFalse(MarketState.unknown("m").betsAllowed()); }
    @Test void varCheckSuspendsAndRecordsPendingReview() {
        var state=review();assertEquals(MarketState.Status.SUSPENDED,state.status());assertEquals(1,state.pendingReviews().size());assertFalse(state.betsAllowed());
    }
    @Test void goalSuspendsUnknown() { assertEquals(MarketState.Status.SUSPENDED,MarketState.unknown("m").apply(event("goal",SportEvent.EventType.GOAL,null),now).status()); }
    @Test void uncorrelatedContinuationCannotOpenUnknownOrSuspended() {
        assertEquals(MarketState.Status.UNKNOWN,MarketState.unknown("m").apply(continuation(),now).status());
        assertEquals(MarketState.Status.SUSPENDED,review().apply(continuation(),now).status());
    }
    @Test void annulmentDoesNotClosePendingReview() {
        var state=review().apply(event("annul",SportEvent.EventType.GOAL_ANNULLED,"goal"),now);
        assertEquals(MarketState.Status.SUSPENDED,state.status());assertTrue(state.pendingReviews().containsKey("var"));
    }
    @Test void explicitVerifiedClosureOpensOnlyAfterLastReview() {
        var state=review().apply(continuation(),now);
        var closed=state.close(close("var",state.version()),continuation(),2,now);
        assertEquals(MarketState.Status.OPEN,closed.status());assertTrue(closed.betsAllowed());assertTrue(closed.pendingReviews().isEmpty());
    }
    @Test void multipleReviewsKeepMarketSuspendedUntilAllAreExplicitlyClosed() {
        var state=review().apply(event("var2",SportEvent.EventType.VAR_CHECK,"goal2"),now).apply(continuation(),now);
        var first=state.close(close("var",state.version()),continuation(),3,now);
        assertEquals(MarketState.Status.SUSPENDED,first.status());assertFalse(first.betsAllowed());
        assertTrue(first.close(close("var2",first.version()),continuation(),3,now).betsAllowed());
    }
    @Test void continuationReceivedBeforeVarCannotAuthorizeClosure() {
        var state=MarketState.unknown("m").apply(continuation(),now).apply(event("var",SportEvent.EventType.VAR_CHECK,null),now);
        assertThrows(IllegalArgumentException.class,()->state.close(close("var",2),continuation(),1,now));
    }
    @Test void staleExpectedRevisionRejectsClosureAfterANewEvent() {
        var state=review().apply(continuation(),now);
        assertThrows(IllegalStateException.class,()->state.close(close("var",1),continuation(),2,now));
    }
    @Test void unrelatedOrUnknownReviewCannotOpen() {
        var state=review().apply(continuation(),now);
        assertThrows(IllegalArgumentException.class,()->state.close(close("unknown",2),continuation(),2,now));
        assertThrows(IllegalArgumentException.class,()->state.close(close("var",2),event("continue",SportEvent.EventType.GOAL,null),2,now));
    }
    @Test void identityConflictInvalidatesOpenMarketAndIsIdempotentlyFailClosed() {
        var state=review().apply(continuation(),now);var open=state.close(close("var",2),continuation(),2,now);
        var conflict=open.conflict(now);assertEquals(MarketState.Status.UNKNOWN,conflict.status());assertFalse(conflict.betsAllowed());assertSame(conflict,conflict.conflict(now));
    }
    @Test void lateAnnulmentAfterClosureFailsClosedAndCannotBeReopenedByAnOldProof() {
        var state=review().apply(continuation(),now).close(close("var",2),continuation(),2,now)
            .apply(event("late-annul",SportEvent.EventType.GOAL_ANNULLED,"goal"),now);
        assertFalse(state.betsAllowed());assertTrue(state.inconsistent());
        var newer=state.apply(event("new-var",SportEvent.EventType.VAR_CHECK,null),now).apply(continuation(),now);
        assertThrows(IllegalArgumentException.class,()->newer.close(close("new-var",newer.version()),continuation(),newer.version(),now));
    }
    @Test void annulmentForAnotherGoalCannotBeClearedByAnUnrelatedReview() {
        var state=review().apply(event("foreign-annul",SportEvent.EventType.GOAL_ANNULLED,"other-goal"),now).apply(continuation(),now);
        assertTrue(state.inconsistent());assertFalse(state.betsAllowed());
        assertThrows(IllegalArgumentException.class,()->state.close(close("var",state.version()),continuation(),state.version(),now));
    }
    @Test void invalidOpenSnapshotIsRejected() {
        assertThrows(IllegalStateException.class,()->new MarketState("m",MarketState.Status.OPEN,0,null,null,false,Map.of()));
        assertThrows(IllegalStateException.class,()->new MarketState("m",MarketState.Status.OPEN,1,"e",now,true,Map.of()));
    }
    @Test void pendingReviewMapIsImmutable() { assertThrows(UnsupportedOperationException.class,()->review().pendingReviews().clear()); }
}
