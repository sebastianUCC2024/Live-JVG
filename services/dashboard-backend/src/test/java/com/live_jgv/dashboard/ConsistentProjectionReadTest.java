package com.live_jgv.dashboard;
import com.live_jgv.dashboard.infrastructure.jdbc.ScoreProjection;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.live_jgv.dashboard.application.port.out.MatchSnapshotPublisher;
import java.sql.ResultSet;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.*;
import org.springframework.transaction.PlatformTransactionManager;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Simulates statement-level MVCC and concurrent commits; never opens PostgreSQL. */
class ConsistentProjectionReadTest {
    record Image(int score,String event) { }
    private final ObjectMapper mapper=new ObjectMapper();
    @Test @SuppressWarnings("unchecked")
    void emptyHistoryKeepsTheExistingRestShapeAndUnknownMatchRemainsNotFound() throws Exception {
        var jdbc=mock(JdbcTemplate.class);
        when(jdbc.queryForMap(anyString(),eq("m"))).thenReturn(Map.of("home_team_id","h","away_team_id","a"));
        var projection=new ScoreProjection(jdbc,mock(PlatformTransactionManager.class),mapper,
                mock(MatchSnapshotPublisher.class),"m","h","a","Home","Away");
        doAnswer(call->{
            var rs=mock(ResultSet.class);when(rs.next()).thenReturn(true,false);
            when(rs.getString("match_data")).thenReturn("{\"match_id\":\"m\",\"home_score\":0,\"away_score\":0,\"last_event_id\":null}");
            when(rs.getString("history_data")).thenReturn("[]");
            when(rs.getString("goals_data")).thenReturn("[]");
            return ((ResultSetExtractor<?>)call.getArgument(1)).extractData(rs);
        }).when(jdbc).query(anyString(),any(ResultSetExtractor.class),eq("m"));
        var view=projection.view("m");assertEquals(0,view.get("home_score"));assertNull(view.get("last_event_id"));
        assertEquals(List.of(),view.get("events"));assertEquals(List.of(),view.get("active_goals"));
        doAnswer(call->{var rs=mock(ResultSet.class);when(rs.next()).thenReturn(false);
            return ((ResultSetExtractor<?>)call.getArgument(1)).extractData(rs);
        }).when(jdbc).query(anyString(),any(ResultSetExtractor.class),eq("missing"));
        assertThrows(NoSuchElementException.class,()->projection.view("missing"));
    }

    private ResultSet row(Image image) throws Exception {
        var rs=mock(ResultSet.class);when(rs.next()).thenReturn(true,false);
        when(rs.getString("match_data")).thenReturn(mapper.writeValueAsString(Map.of(
                "match_id","m","home_score",image.score(),"away_score",0,"last_event_id",image.event())));
        String payload=mapper.writeValueAsString(Map.of("event_id",image.event(),"event_type","GOAL","home_score",image.score()));
        when(rs.getString("history_data")).thenReturn(mapper.writeValueAsString(List.of(payload)));
        when(rs.getString("goals_data")).thenReturn(mapper.writeValueAsString(List.of(payload)));
        return rs;
    }

    @Test @SuppressWarnings("unchecked")
    void commitBetweenStatementStartAndResultConsumptionCannotMixRevisions() throws Exception {
        var jdbc=mock(JdbcTemplate.class);
        when(jdbc.queryForMap(anyString(),eq("m"))).thenReturn(Map.of("home_team_id","h","away_team_id","a"));
        var projection=new ScoreProjection(jdbc,mock(PlatformTransactionManager.class),mapper,
                mock(MatchSnapshotPublisher.class),"m","h","a","Home","Away");
        clearInvocations(jdbc);
        var database=new AtomicReference<>(new Image(1,"g1"));
        var captured=new CountDownLatch(1);var committed=new CountDownLatch(1);
        doAnswer(call->{
            var image=database.get();captured.countDown();
            assertTrue(committed.await(3,TimeUnit.SECONDS));
            return ((ResultSetExtractor<?>)call.getArgument(1)).extractData(row(image));
        }).when(jdbc).query(anyString(),any(ResultSetExtractor.class),eq("m"));
        var pool=Executors.newSingleThreadExecutor();
        try {
            var reading=pool.submit(()->projection.view("m"));
            assertTrue(captured.await(3,TimeUnit.SECONDS));
            database.set(new Image(2,"g2"));committed.countDown();
            var view=reading.get(3,TimeUnit.SECONDS);
            assertEquals(1,view.get("home_score"));assertEquals("g1",view.get("last_event_id"));
            var events=(List<com.fasterxml.jackson.databind.JsonNode>)view.get("events");
            assertEquals("g1",events.get(0).get("event_id").asText());
            assertEquals(1,events.get(0).get("home_score").asInt());
            var sql=org.mockito.ArgumentCaptor.forClass(String.class);
            verify(jdbc).query(sql.capture(),any(ResultSetExtractor.class),eq("m"));
            assertTrue(sql.getValue().contains("dashboard.matches"));assertTrue(sql.getValue().contains("dashboard.inbox"));
            assertFalse(sql.getValue().toUpperCase(Locale.ROOT).contains("FOR UPDATE"));
            verifyNoMoreInteractions(jdbc);
            assertEquals(2,projection.view("m").get("home_score"));
        }finally{committed.countDown();pool.shutdownNow();}
    }
}
