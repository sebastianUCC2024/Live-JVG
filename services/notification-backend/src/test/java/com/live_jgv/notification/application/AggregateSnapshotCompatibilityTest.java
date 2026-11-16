package com.live_jgv.notification.application;

import com.live_jgv.notification.domain.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Synthetic aggregate snapshots, not an implementation or approval of VAR P2 rules. */
class AggregateSnapshotCompatibilityTest {
    private ScoreUpdate update(String id,ScoreUpdate.EventType type,ScoreUpdate.VarStatus status,String reference,int score,Instant time) {
        return new ScoreUpdate("fixture-match",id,type,score,0,status,time,reference);
    }
    @Test void twoReviewsPartialClosureAnnulmentAndFinalClosureUseOnlySuppliedAggregate() {
        var policy=new NotificationPolicy();
        var input=List.of(
            update("goal-1",ScoreUpdate.EventType.GOAL,ScoreUpdate.VarStatus.IN_PROGRESS,null,2,Instant.EPOCH),
            update("review-1",ScoreUpdate.EventType.VAR_CHECK,ScoreUpdate.VarStatus.IN_PROGRESS,"goal-1",2,Instant.EPOCH),
            update("review-2",ScoreUpdate.EventType.VAR_CHECK,ScoreUpdate.VarStatus.IN_PROGRESS,"goal-2",2,Instant.EPOCH),
            update("partial-close",ScoreUpdate.EventType.MATCH_CONTINUES,ScoreUpdate.VarStatus.IN_PROGRESS,"review-1",2,Instant.EPOCH),
            update("annulment",ScoreUpdate.EventType.GOAL_ANNULLED,ScoreUpdate.VarStatus.IN_PROGRESS,"goal-1",1,Instant.EPOCH),
            update("final-close",ScoreUpdate.EventType.MATCH_CONTINUES,ScoreUpdate.VarStatus.CLEAR,"review-2",1,Instant.EPOCH));
        for(var snapshot:input) {
            var result=policy.create(snapshot,Instant.EPOCH);
            if(snapshot.eventType()==ScoreUpdate.EventType.MATCH_CONTINUES) {
                assertTrue(result.isEmpty(),"A continuation must not introduce a new visible alert");
            } else {
                var alert=result.orElseThrow();
                assertEquals(snapshot,alert.source());
                assertTrue(alert.content().message().contains("Revisión VAR en curso"));
                assertFalse(alert.content().message().contains("confirmado"));
                assertTrue(alert.content().message().contains(snapshot.homeScore()+"–0"));
            }
        }
    }
    @Test void lateTimestampIsForwardedInArrivalOrderWithoutInventingARevisionOrChangingIdentity() {
        var store=mock(NotificationStore.class);
        var clock=Clock.fixed(Instant.parse("2026-11-16T20:00:00Z"),ZoneOffset.UTC);
        var service=new ProcessScoreUpdateService(store,new NotificationPolicy(),clock);
        var recent=update("recent",ScoreUpdate.EventType.GOAL,ScoreUpdate.VarStatus.IN_PROGRESS,null,2,Instant.ofEpochSecond(20));
        var late=update("late",ScoreUpdate.EventType.GOAL_ANNULLED,ScoreUpdate.VarStatus.IN_PROGRESS,"original-goal",1,Instant.ofEpochSecond(10));
        service.process(recent);service.process(late);
        var order=inOrder(store);
        order.verify(store).accept(eq(recent),argThat(value->value.isPresent()&&value.get().source().equals(recent)));
        order.verify(store).accept(eq(late),argThat(value->value.isPresent()&&value.get().source().equals(late)
                &&value.get().createdAt().equals(clock.instant())&&!value.get().content().message().contains("confirmado")));
        verifyNoMoreInteractions(store);
    }
}
