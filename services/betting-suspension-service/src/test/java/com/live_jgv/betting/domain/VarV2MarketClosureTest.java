package com.live_jgv.betting.domain;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import static org.junit.jupiter.api.Assertions.*;

class VarV2MarketClosureTest {
    private final Instant now=Instant.parse("2026-11-16T00:00:00Z");
    private SportEvent event(String id,SportEvent.EventType type,String reference){
        return new SportEvent(id,"m",type,type==SportEvent.EventType.GOAL?"home":null,10,now,reference);
    }
    private MarketState reviews(){
        return MarketState.unknown("m").apply(event("r1",SportEvent.EventType.VAR_CHECK,"g1"),now)
                .apply(event("r2",SportEvent.EventType.VAR_CHECK,"g2"),now);
    }
    private ReviewClosure command(String review,String continuation,long version){
        return new ReviewClosure("close-"+review,"m",review,continuation,version,"operator","verified internal fixture",now,now.plusSeconds(5));
    }
    @Test void eachV2ContinuationClosesOnlyItsReferencedReview(){
        var c1=event("c1",SportEvent.EventType.MATCH_CONTINUES,"r1");
        var state=reviews().apply(c1,now).close(command("r1","c1",3),c1,3,now,ReviewClosure.ReferenceMode.V2_REVIEW);
        assertEquals(MarketState.Status.SUSPENDED,state.status());assertEquals(java.util.Set.of("r2"),state.pendingReviews().keySet());
        var c2=event("c2",SportEvent.EventType.MATCH_CONTINUES,"r2");
        var open=state.apply(c2,now).close(command("r2","c2",5),c2,5,now,ReviewClosure.ReferenceMode.V2_REVIEW);
        assertTrue(open.betsAllowed());assertEquals(6,open.version());
    }
    @Test void closingVarCannotReleaseIndependentGoalHold(){
        var c=event("c",SportEvent.EventType.MATCH_CONTINUES,"r1");
        var state=MarketState.unknown("m").apply(event("g1",SportEvent.EventType.GOAL,null),now)
                .apply(event("r1",SportEvent.EventType.VAR_CHECK,"g1"),now).apply(c,now);
        var closed=state.close(command("r1","c",3),c,3,now,ReviewClosure.ReferenceMode.V2_REVIEW);
        assertFalse(closed.betsAllowed());assertEquals(java.util.Set.of("g1"),closed.pendingReviews().keySet());
        assertEquals(SportEvent.EventType.GOAL,closed.pendingReviews().get("g1").blockingType());
    }
    @Test void v2CannotTreatGoalIdentityAsVarRevision(){
        var c=event("c",SportEvent.EventType.MATCH_CONTINUES,"g1");
        var state=MarketState.unknown("m").apply(event("g1",SportEvent.EventType.GOAL,null),now).apply(c,now);
        assertThrows(IllegalArgumentException.class,()->state.close(command("g1","c",2),c,2,now,ReviewClosure.ReferenceMode.V2_REVIEW));
    }
    @Test void absentGoalAndOtherReviewReferencesCannotFallbackInV2(){
        for(String reference:new String[]{null,"g1","r2","missing"}){
            var c=event("c",SportEvent.EventType.MATCH_CONTINUES,reference);var state=reviews().apply(c,now);
            assertThrows(IllegalArgumentException.class,()->state.close(command("r1","c",3),c,3,now,ReviewClosure.ReferenceMode.V2_REVIEW));
        }
    }
    @Test void wireContinuationDoesNotCloseEvenWithMatchingV2Reference(){
        var state=reviews().apply(event("c",SportEvent.EventType.MATCH_CONTINUES,"r1"),now);
        assertEquals(2,state.pendingReviews().size());assertFalse(state.betsAllowed());
    }
    @Test void newReviewMakesOldV2ContinuationUnusable(){
        var c=event("c",SportEvent.EventType.MATCH_CONTINUES,"r1");
        var state=reviews().apply(c,now).apply(event("r3",SportEvent.EventType.VAR_CHECK,"g3"),now);
        assertThrows(IllegalArgumentException.class,()->state.close(command("r1","c",4),c,3,now,ReviewClosure.ReferenceMode.V2_REVIEW));
        assertThrows(IllegalStateException.class,()->state.close(command("r1","c",3),c,3,now,ReviewClosure.ReferenceMode.V2_REVIEW));
    }
    @Test void expiredV2EvidenceAndCrossMatchContinuationFailClosed(){
        var c=event("c",SportEvent.EventType.MATCH_CONTINUES,"r1");var state=reviews().apply(c,now);
        assertThrows(IllegalArgumentException.class,()->state.close(command("r1","c",3),c,3,now.plusSeconds(5),ReviewClosure.ReferenceMode.V2_REVIEW));
        var foreign=new SportEvent("c","other",SportEvent.EventType.MATCH_CONTINUES,null,10,now,"r1");
        assertThrows(IllegalArgumentException.class,()->state.close(command("r1","c",3),foreign,3,now,ReviewClosure.ReferenceMode.V2_REVIEW));
    }
    @Test void v1GoalReferenceRemainsSupportedOnlyInItsExplicitMode(){
        var c=event("c",SportEvent.EventType.MATCH_CONTINUES,"g1");var state=reviews().apply(c,now);
        assertEquals(1,state.close(command("r1","c",3),c,3,now).pendingReviews().size());
        assertThrows(IllegalArgumentException.class,()->state.close(command("r1","c",3),c,3,now,ReviewClosure.ReferenceMode.V2_REVIEW));
    }
}
