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
        store.closeReview(new ReviewClosure("closure","it-match","var","continue",2,"test-operator","explicit test correlation"));
        assertTrue(new JdbcSuspensionStore(jdbc,tx,Clock.systemUTC()).status("it-match").betsAllowed());
        assertEquals(2,jdbc.queryForObject("SELECT count(*) FROM betting_control.suspension_outbox WHERE published_at IS NULL",Integer.class));
        var annul=new SportEvent("late-annul","it-match",SportEvent.EventType.GOAL_ANNULLED,null,10,Instant.EPOCH,"goal");
        store.handle(raw(3),DecodedInput.valid(annul,"late-annul-image"));assertFalse(store.status("it-match").betsAllowed());
    }
    private InputRecord raw(long offset){return new InputRecord("it/1","events",0,offset,0,new byte[]{1},new byte[]{2},"[]");}
}
