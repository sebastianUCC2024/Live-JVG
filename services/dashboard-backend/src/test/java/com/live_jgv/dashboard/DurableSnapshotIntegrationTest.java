package com.live_jgv.dashboard;
import com.live_jgv.dashboard.infrastructure.jdbc.ScoreProjection;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.live_jgv.dashboard.application.port.out.MatchSnapshotPublisher;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import static org.junit.jupiter.api.Assertions.*;

/** Opt in only against a disposable database; never writes the configured MVP match. */
@org.junit.jupiter.api.Tag("external-integration")
@org.junit.jupiter.api.Tag("postgres-integration")
@org.junit.jupiter.api.extension.ExtendWith(com.live_jgv.test.DurableIntegrationSafety.class)
@SpringBootTest(properties={
    "spring.datasource.url=${DASHBOARD_INTEGRATION_DB_URL}",
    "spring.datasource.username=${DASHBOARD_TEST_DB_USERNAME}",
    "spring.datasource.password=${DASHBOARD_TEST_DB_PASSWORD}",
    "spring.rabbitmq.listener.simple.auto-startup=false", "spring.rabbitmq.dynamic=false",
    "management.health.rabbit.enabled=false", "live.match-id=integration-test-catalog"
})
class DurableSnapshotIntegrationTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager manager;
    @Autowired ObjectMapper mapper;
    @Autowired MatchSnapshotPublisher publisher;

    @Test @SuppressWarnings("unchecked")
    void twentyThousandHistoricalEventsKeepOldActiveGoalAndAnnulmentOutsideWindow() {
        String match="history-"+java.util.UUID.randomUUID();
        var projection=new ScoreProjection(jdbc,manager,mapper,publisher,match,"home","away","Home","Away");
        projection.receive(message(match,"g1",1,"GOAL",null));
        projection.receive(message(match,"g2",2,"GOAL",null));
        projection.receive(message(match,"a1",1,"GOAL_ANNULLED","g1"));
        // One bulk SQL insert, not 20,000 network round trips. Only in disposable opt-in DB.
        jdbc.update("""
                INSERT INTO dashboard.inbox(match_id,event_id,payload)
                SELECT ?, 'history-'||n, json_build_object('match_id',?, 'event_id','history-'||n,
                    'event_type','MATCH_CONTINUES','home_score',1,'away_score',0,'var_status','CLEAR',
                    'updated_at','2026-11-15T00:00:00Z')::text
                FROM generate_series(1,20000) n ORDER BY n
                """,match,match);
        var view=projection.view(match);
        var recent=(java.util.List<com.fasterxml.jackson.databind.JsonNode>)view.get("events");
        var goals=(java.util.List<com.fasterxml.jackson.databind.JsonNode>)view.get("active_goals");
        assertEquals(30,recent.size()); assertEquals("history-20000",recent.get(0).get("event_id").asText());
        assertEquals("history-19971",recent.get(29).get("event_id").asText());
        assertEquals(1,goals.size()); assertEquals("g2",goals.get(0).get("event_id").asText());
        assertTrue(projection.operationProcessed(match,"g1"));
        assertEquals(20003,jdbc.queryForObject("SELECT count(*) FROM dashboard.inbox WHERE match_id=?",Integer.class,match));
    }

    @Test void committedSnapshotRecoversAndOldDuplicatesCannotRegressIt() {
        String match="projection-"+java.util.UUID.randomUUID();
        var projection=new ScoreProjection(jdbc,manager,mapper,publisher,match,"home","away","Home","Away");
        Message goal=message(match,"goal",1,"GOAL",null);
        projection.receive(goal);
        var first=projection.snapshot(match).orElseThrow();
        projection.receive(message(match,"annul",0,"GOAL_ANNULLED","goal"));
        var annulled=projection.snapshot(match).orElseThrow();
        assertTrue(annulled.revision()>first.revision());
        projection.receive(goal);
        assertEquals(annulled,projection.snapshot(match).orElseThrow());
        assertEquals(2,jdbc.queryForObject("SELECT count(*) FROM dashboard.inbox WHERE match_id=?",Integer.class,match));
        var recovered=new ScoreProjection(jdbc,manager,mapper,publisher,match,"home","away","Home","Away");
        assertEquals(annulled,recovered.snapshot(match).orElseThrow());
        assertEquals(0,recovered.view(match).get("home_score"));
        assertTrue(recovered.snapshot("unknown-"+match).isEmpty());
    }

    @Test void invalidMessagesNeverReachTheCommittedInbox() {
        String match="invalid-"+java.util.UUID.randomUUID();
        var projection=new ScoreProjection(jdbc,manager,mapper,publisher,match,"home","away","Home","Away");
        assertThrows(RuntimeException.class,()->projection.receive(new Message("{}".getBytes(java.nio.charset.StandardCharsets.UTF_8))));
        assertTrue(projection.snapshot(match).isEmpty());
    }

    private Message message(String match,String event,int score,String type,String reference) {
        String json="{\"match_id\":\""+match+"\",\"event_id\":\""+event+"\",\"home_score\":"+score+
            ",\"away_score\":0,\"event_type\":\""+type+"\",\"var_status\":\"CLEAR\",\"updated_at\":\"2026-11-15T05:00:00Z\",\"reference_event_id\":"+
            (reference==null?"null":"\""+reference+"\"")+"}";
        return new Message(json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}
