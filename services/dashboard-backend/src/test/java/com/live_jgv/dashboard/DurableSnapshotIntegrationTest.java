package com.live_jgv.dashboard;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.live_jgv.dashboard.domain.port.out.MatchSnapshotPublisher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.amqp.core.Message;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import static org.junit.jupiter.api.Assertions.*;

/** Opt in only against a disposable database; never writes the configured MVP match. */
@EnabledIfEnvironmentVariable(named="DASHBOARD_INTEGRATION_DB_URL", matches=".+")
@SpringBootTest(properties={
    "spring.datasource.url=${DASHBOARD_INTEGRATION_DB_URL}",
    "spring.rabbitmq.listener.simple.auto-startup=false", "spring.rabbitmq.dynamic=false",
    "management.health.rabbit.enabled=false", "live.match-id=integration-test-catalog"
})
class DurableSnapshotIntegrationTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager manager;
    @Autowired ObjectMapper mapper;
    @Autowired MatchSnapshotPublisher publisher;

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
