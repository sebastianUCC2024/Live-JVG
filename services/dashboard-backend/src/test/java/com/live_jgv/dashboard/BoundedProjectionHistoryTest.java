package com.live_jgv.dashboard;

import com.fasterxml.jackson.databind.*;
import com.live_jgv.dashboard.domain.port.out.MatchSnapshotPublisher;
import java.sql.ResultSet;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.*;
import org.springframework.transaction.PlatformTransactionManager;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Verifies bounded JDBC transfer/REST assembly; does not execute PostgreSQL SQL. */
class BoundedProjectionHistoryTest {
    @Test @SuppressWarnings("unchecked")
    void onlyRecentThirtyAreTransferredAndOldActiveGoalsRemainAvailable() throws Exception {
        var mapper = new ObjectMapper();
        var jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForMap(anyString(), eq("m"))).thenReturn(Map.of("home_team_id","h","away_team_id","a"));
        var projection = new ScoreProjection(jdbc,mock(PlatformTransactionManager.class),mapper,
                mock(MatchSnapshotPublisher.class),"m","h","a","Home","Away");
        clearInvocations(jdbc);
        var recent = new ArrayList<String>();
        // Model the database's bounded result for a ledger of 20,000 entries.
        for (int seq = 19971; seq <= 20000; seq++) recent.add(mapper.writeValueAsString(Map.of(
                "event_id","e"+seq,"event_type","MATCH_CONTINUES","updated_at","2026-11-15T00:00:00Z")));
        String oldGoal = mapper.writeValueAsString(Map.of("event_id","g1","event_type","GOAL"));
        doAnswer(call -> {
            String sql = call.getArgument(0);
            assertTrue(sql.contains("ORDER BY seq DESC LIMIT 30"));
            assertTrue(sql.contains("NOT EXISTS")); assertTrue(sql.contains("a.seq>g.seq"));
            var rows = mock(ResultSet.class); when(rows.next()).thenReturn(true,false);
            when(rows.getString("match_data")).thenReturn("{\"match_id\":\"m\",\"home_score\":1,\"var_status\":\"CLEAR\"}");
            when(rows.getString("history_data")).thenReturn(mapper.writeValueAsString(recent));
            when(rows.getString("goals_data")).thenReturn(mapper.writeValueAsString(List.of(oldGoal)));
            return ((ResultSetExtractor<?>)call.getArgument(1)).extractData(rows);
        }).when(jdbc).query(anyString(),any(ResultSetExtractor.class),eq("m"));
        var view = projection.view("m");
        var events = (List<JsonNode>)view.get("events");
        assertEquals(30,events.size()); assertEquals("e20000",events.get(0).get("event_id").asText());
        assertEquals("e19971",events.get(29).get("event_id").asText());
        assertEquals("g1",((List<JsonNode>)view.get("active_goals")).get(0).get("event_id").asText());
        verify(jdbc).query(anyString(),any(ResultSetExtractor.class),eq("m")); verifyNoMoreInteractions(jdbc);
    }
}
