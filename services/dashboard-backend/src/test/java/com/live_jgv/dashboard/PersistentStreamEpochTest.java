package com.live_jgv.dashboard;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.live_jgv.dashboard.domain.port.out.MatchSnapshotPublisher;
import java.sql.ResultSet;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.*;
import org.springframework.transaction.PlatformTransactionManager;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** JDBC simulation of persisted epoch; migration against PostgreSQL is tested separately, opt-in. */
class PersistentStreamEpochTest {
    private ScoreProjection projection(JdbcTemplate jdbc) {
        when(jdbc.queryForMap(anyString(),eq("m"))).thenReturn(Map.of("home_team_id","h","away_team_id","a"));
        return new ScoreProjection(jdbc,mock(PlatformTransactionManager.class),new ObjectMapper(),
                mock(MatchSnapshotPublisher.class),"m","h","a","Home","Away");
    }

    @Test @SuppressWarnings("unchecked")
    void restartKeepsEpochAndExplicitRestoreRotationAllowsResetRevision() throws Exception {
        var jdbc = mock(JdbcTemplate.class);
        var epoch = new AtomicReference<>(UUID.randomUUID());
        var revision = new AtomicLong(9000);
        doAnswer(call -> {
            String sql = call.getArgument(0);
            assertTrue(sql.contains("dashboard.stream_epoch"));
            assertTrue(sql.contains("LIMIT 1"));
            var rows = mock(ResultSet.class); when(rows.next()).thenReturn(true,false);
            when(rows.getString("epoch")).thenReturn(epoch.get().toString());
            when(rows.getLong("seq")).thenReturn(revision.get());
            when(rows.getString("payload")).thenReturn("{\"match_id\":\"m\",\"event_id\":\"g\",\"event_type\":\"GOAL\",\"home_score\":1,\"away_score\":0,\"var_status\":\"CLEAR\",\"updated_at\":\"2026-11-15T00:00:00Z\"}");
            return ((ResultSetExtractor<?>)call.getArgument(1)).extractData(rows);
        }).when(jdbc).query(anyString(),any(ResultSetExtractor.class),eq("m"));
        var first = projection(jdbc).snapshot("m").orElseThrow();
        var restarted = projection(jdbc).snapshot("m").orElseThrow();
        assertEquals("durable-"+epoch.get()+"-m",first.streamId());
        assertEquals(first,restarted);
        epoch.set(UUID.randomUUID()); revision.set(1);
        var restored = projection(jdbc).snapshot("m").orElseThrow();
        assertNotEquals(first.streamId(),restored.streamId());
        assertEquals(1,restored.revision());
        assertEquals(restored,projection(jdbc).snapshot("m").orElseThrow());
        verify(jdbc,never()).update(contains("stream_epoch"),any(Object[].class));
    }

    @Test @SuppressWarnings("unchecked")
    void missingEpochFailsClosedAndAnEmptyInboxHasNoInventedSnapshot() throws Exception {
        var jdbc=mock(JdbcTemplate.class);var projection=projection(jdbc);
        doAnswer(call->{var rows=mock(ResultSet.class);when(rows.next()).thenReturn(false);
            return ((ResultSetExtractor<?>)call.getArgument(1)).extractData(rows);
        }).when(jdbc).query(anyString(),any(ResultSetExtractor.class),eq("m"));
        assertThrows(IllegalStateException.class,()->projection.snapshot("m"));
        doAnswer(call->{var rows=mock(ResultSet.class);when(rows.next()).thenReturn(true);
            when(rows.getString("epoch")).thenReturn(UUID.randomUUID().toString());
            return ((ResultSetExtractor<?>)call.getArgument(1)).extractData(rows);
        }).when(jdbc).query(anyString(),any(ResultSetExtractor.class),eq("m"));
        assertTrue(projection.snapshot("m").isEmpty());
    }
}
