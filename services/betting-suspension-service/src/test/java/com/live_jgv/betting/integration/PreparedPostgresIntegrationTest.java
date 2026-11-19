package com.live_jgv.betting.integration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.flywaydb.core.Flyway;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.*;
import static org.junit.jupiter.api.Assertions.*;

import com.live_jgv.betting.domain.*;
import com.live_jgv.betting.application.SuspensionStore;
import com.live_jgv.betting.infrastructure.JdbcSuspensionStore;

@EnabledIfEnvironmentVariable(named="DEV3_RUN_POSTGRES_TESTS",matches="true")
class PreparedPostgresIntegrationTest {
    @Test void stateReviewClosureDedupAndOutboxSurviveNewAdapter() {
        var ds=IsolatedPostgres.approvedFreshDatabase();var jdbc=new JdbcTemplate(ds);
        Flyway.configure().dataSource(ds).schemas("betting_control").defaultSchema("betting_control").cleanDisabled(true).load().migrate();
        var tx=new TransactionTemplate(new DataSourceTransactionManager(ds));var store=new JdbcSuspensionStore(jdbc,tx,Clock.systemUTC());
        var varEvent=new SportEvent("var","it-match",SportEvent.EventType.VAR_CHECK,null,10,Instant.EPOCH,"goal");
        assertEquals(SuspensionStore.Outcome.APPLIED,store.handle(raw(0),DecodedInput.valid(varEvent,"var-image")));
        assertEquals(SuspensionStore.Outcome.DUPLICATE,store.handle(raw(1),DecodedInput.valid(varEvent,"var-image")));
        var continuation=new SportEvent("continue","it-match",SportEvent.EventType.MATCH_CONTINUES,null,10,Instant.EPOCH,null);
        store.handle(raw(2),DecodedInput.valid(continuation,"continue-image"));
        assertFalse(store.status("it-match").betsAllowed());
        store.closeReview(new ReviewClosure("closure","it-match","var","continue",2,"test-operator","explicit test correlation",Instant.now().minusSeconds(1),Instant.now().plusSeconds(5)));
        assertTrue(new JdbcSuspensionStore(jdbc,tx,Clock.systemUTC()).status("it-match").betsAllowed());
        assertEquals(2,jdbc.queryForObject("SELECT count(*) FROM betting_control.suspension_outbox WHERE published_at IS NULL",Integer.class));
        var annul=new SportEvent("late-annul","it-match",SportEvent.EventType.GOAL_ANNULLED,null,10,Instant.EPOCH,"goal");
        store.handle(raw(3),DecodedInput.valid(annul,"late-annul-image"));assertFalse(store.status("it-match").betsAllowed());

        // Separate market: a GOAL and its VAR create distinct internal holds, both requiring explicit closure.
        var goal=new SportEvent("goal2","goal-market",SportEvent.EventType.GOAL,"home",10,Instant.EPOCH,null);
        store.handle(raw(4),DecodedInput.valid(goal,"goal2-image"));
        assertEquals(SuspensionStore.Outcome.DUPLICATE,store.handle(raw(5),DecodedInput.valid(goal,"goal2-image")));
        assertEquals(1,store.status("goal-market").version());
        var check=new SportEvent("var2","goal-market",SportEvent.EventType.VAR_CHECK,null,10,Instant.EPOCH,"goal2");
        store.handle(raw(6),DecodedInput.valid(check,"var2-image"));
        var resume=new SportEvent("continue2","goal-market",SportEvent.EventType.MATCH_CONTINUES,null,10,Instant.EPOCH,null);
        store.handle(raw(7),DecodedInput.valid(resume,"continue2-image"));
        var recovered=new JdbcSuspensionStore(jdbc,tx,Clock.systemUTC());
        assertEquals(2,recovered.status("goal-market").pendingReviews().size());
        var expired=new ReviewClosure("expired","goal-market","goal2","continue2",3,"operator","test",Instant.now().minusSeconds(10),Instant.now().minusSeconds(5));
        assertThrows(IllegalArgumentException.class,()->recovered.closeReview(expired));
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM betting_control.closure_command WHERE command_id='expired'",Integer.class));
        var first=new ReviewClosure("close-goal","goal-market","goal2","continue2",3,"operator","test",Instant.now().minusSeconds(1),Instant.now().plusSeconds(30));
        assertFalse(recovered.closeReview(first).betsAllowed());
        assertEquals(4,recovered.closeReview(first).version()); // Idempotent, no second transition.
        var last=new ReviewClosure("close-var","goal-market","var2","continue2",4,"operator","test",Instant.now().minusSeconds(1),Instant.now().plusSeconds(30));
        assertTrue(recovered.closeReview(last).betsAllowed());
        assertEquals(2,jdbc.queryForObject("SELECT count(*) FROM betting_control.suspension_outbox WHERE match_id='goal-market'",Integer.class));
        assertEquals(2,jdbc.queryForObject("SELECT count(*) FROM betting_control.closure_command WHERE match_id='goal-market'",Integer.class));
        // Synthetic checkpoint only: this does not certify Kafka catch-up or permit real acceptance.
        var issuer=new com.live_jgv.betting.infrastructure.JdbcOpenLeaseIssuer(jdbc,tx,(match,version,checkpoint)->true); // Explicit synthetic verifier, never production wiring.
        issuer.issue(new com.live_jgv.betting.infrastructure.JdbcOpenLeaseIssuer.Request("goal-market",5,1000,"test-only","operator","isolated fixture"));
        var nextGoal=new SportEvent("next-goal","goal-market",SportEvent.EventType.GOAL,"away",11,Instant.EPOCH,null);
        recovered.handle(raw(8),DecodedInput.valid(nextGoal,"next-goal-image"));
        var authority=jdbc.queryForMap("SELECT * FROM betting_control.lock_market_authority(?::text)","goal-market");
        assertEquals("SUSPENDED",authority.get("status"));
        assertEquals(1L,((Number)authority.get("pending_reviews")).longValue());
        assertEquals(6L,((Number)authority.get("market_version")).longValue());
        assertEquals(5L,((Number)authority.get("lease_version")).longValue());
        // Explicit internal V2 mode: two review references close separately, leaving the independent GOAL.
        var v2Goal=new SportEvent("vg","v2-market",SportEvent.EventType.GOAL,"home",10,Instant.EPOCH,null);
        recovered.handle(raw(9),DecodedInput.valid(v2Goal,"vg-image"));
        var r1=new SportEvent("vr1","v2-market",SportEvent.EventType.VAR_CHECK,null,10,Instant.EPOCH,"vg");
        var r2=new SportEvent("vr2","v2-market",SportEvent.EventType.VAR_CHECK,null,10,Instant.EPOCH,"vg");
        recovered.handle(raw(10),DecodedInput.valid(r1,"vr1-image"));
        recovered.handle(raw(11),DecodedInput.valid(r2,"vr2-image"));
        var vc1=new SportEvent("vc1","v2-market",SportEvent.EventType.MATCH_CONTINUES,null,10,Instant.EPOCH,"vr1");
        recovered.handle(raw(12),DecodedInput.valid(vc1,"vc1-image"));
        var close1=new ReviewClosure("v2-close1","v2-market","vr1","vc1",4,"operator","internal V2 test",Instant.now().minusSeconds(1),Instant.now().plusSeconds(30));
        assertEquals(2,recovered.closeReviewV2(close1).pendingReviews().size());
        assertEquals(5,recovered.closeReviewV2(close1).version());
        var vc2=new SportEvent("vc2","v2-market",SportEvent.EventType.MATCH_CONTINUES,null,10,Instant.EPOCH,"vr2");
        recovered.handle(raw(13),DecodedInput.valid(vc2,"vc2-image"));
        var close2=new ReviewClosure("v2-close2","v2-market","vr2","vc2",6,"operator","internal V2 test",Instant.now().minusSeconds(1),Instant.now().plusSeconds(30));
        var v2Final=recovered.closeReviewV2(close2);
        assertFalse(v2Final.betsAllowed());assertEquals(java.util.Set.of("vg"),v2Final.pendingReviews().keySet());
        assertEquals(SportEvent.EventType.GOAL,new JdbcSuspensionStore(jdbc,tx,Clock.systemUTC()).status("v2-market").pendingReviews().get("vg").blockingType());
        assertEquals(2,jdbc.queryForObject("SELECT count(*) FROM betting_control.closure_command WHERE match_id='v2-market'",Integer.class));
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM betting_control.suspension_outbox WHERE match_id='v2-market' AND event_type='BETTING_RESUMED'",Integer.class));
    }
    private InputRecord raw(long offset){return new InputRecord("it/1","events",0,offset,0,new byte[]{1},new byte[]{2},"[]");}
}
