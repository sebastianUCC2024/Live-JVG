package com.live_jgv.betting.domain;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class MarketStateTest {
    private final Instant now=Instant.parse("2026-11-15T00:00:00.123456789Z");
    private SportEvent event(String id,SportEvent.EventType type,String ref) { return new SportEvent(id,"m",type,"home",10,now,ref); }
    private MarketState review() { return MarketState.unknown("m").apply(event("var",SportEvent.EventType.VAR_CHECK,"goal"),now); }
    private ReviewClosure close(String review,long version) { return new ReviewClosure("command-"+review,"m",review,"continue",version,"operator","verified decision"); }
    private SportEvent continuation() { return event("continue",SportEvent.EventType.MATCH_CONTINUES,null); }
    @Test void unknownNeverAllowsBets() { assertFalse(MarketState.unknown("m").betsAllowed()); }
    @Test void varCheckSuspendsAndRecordsPendingReview() {
        var state=review();assertEquals(MarketState.Status.SUSPENDED,state.status());assertEquals(1,state.pendingReviews().size());assertFalse(state.betsAllowed());
    }
    @Test void goalDoesNotOpenUnknown() { assertEquals(MarketState.Status.UNKNOWN,MarketState.unknown("m").apply(event("goal",SportEvent.EventType.GOAL,null),now).status()); }
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
