package com.live_jgv.matchstate.infrastructure.jdbc;

import com.live_jgv.matchstate.domain.model.*;
import com.live_jgv.matchstate.infrastructure.config.MessagingConfiguration;
import java.sql.*;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.*;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Drives the actual JDBC extractor/rebuilder with a large simulated cursor. */
class JdbcHistoryReplayTest {
    @Test @SuppressWarnings("unchecked")
    void corruptSequencesIdentitiesRevisionsAndRulesStillFailClosed() throws Exception {
        for (String corruption : java.util.List.of("sequence","identity","revision","rules","unapproved-v2")) {
            var jdbc=mock(JdbcTemplate.class);var manager=mock(PlatformTransactionManager.class);
            when(manager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
            var rows=mock(ResultSet.class);when(rows.next()).thenReturn(true,false);
            when(rows.getString("home_team_id")).thenReturn("h");when(rows.getString("away_team_id")).thenReturn("a");
            when(rows.getString("event_id")).thenReturn("g");
            when(rows.getLong("application_seq")).thenReturn(corruption.equals("sequence")?2L:1L);
            when(rows.getLong("revision")).thenReturn(corruption.equals("revision")?2L:1L);
            when(rows.getInt("rules_version")).thenReturn(corruption.equals("rules")?99:corruption.equals("unapproved-v2")?2:1);
            var mapper=new MessagingConfiguration().messagingObjectMapper();
            when(rows.getString("event_payload")).thenReturn(mapper.writeValueAsString(new MatchEvent(
                    corruption.equals("identity")?"different":"g","m",EventType.GOAL,"h",1,Instant.EPOCH,null)));
            doAnswer(call->((ResultSetExtractor<?>)call.getArgument(1)).extractData(rows))
                    .when(jdbc).query(any(PreparedStatementCreator.class),any(ResultSetExtractor.class));
            var store=new JdbcMatchStateStore(jdbc,new TransactionTemplate(manager),mapper,"updates","score.{matchId}");
            assertThrows(IllegalStateException.class,()->store.load("m"),corruption);
            verify(manager).rollback(any());
        }
    }
    @Test @SuppressWarnings("unchecked")
    void largeOrderedCursorPreservesAllEventsAndOldVarReferences() throws Exception {
        int count = 5000;
        var mapper = new MessagingConfiguration().messagingObjectMapper();
        var jdbc = mock(JdbcTemplate.class);
        var manager = mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        doAnswer(call -> {
            var connection = mock(Connection.class); var statement = mock(PreparedStatement.class);
            when(connection.prepareStatement(anyString())).thenReturn(statement);
            ((PreparedStatementCreator)call.getArgument(0)).createPreparedStatement(connection);
            verify(statement).setFetchSize(256); verify(statement).setString(1,"m");
            var position = new AtomicInteger(); var rows = mock(ResultSet.class);
            when(rows.next()).thenAnswer(c -> position.incrementAndGet() <= count);
            when(rows.getString("home_team_id")).thenReturn("h");when(rows.getString("away_team_id")).thenReturn("a");
            when(rows.getLong("revision")).thenReturn((long)count);when(rows.getInt("rules_version")).thenReturn(1);
            when(rows.getLong("application_seq")).thenAnswer(c -> (long)position.get());
            when(rows.getString("event_id")).thenAnswer(c -> "e"+position.get());
            when(rows.getString("event_payload")).thenAnswer(c -> {
                int n=position.get();
                return mapper.writeValueAsString(new MatchEvent("e"+n,"m",n==1?EventType.GOAL:n==count?EventType.GOAL_ANNULLED:EventType.MATCH_CONTINUES,
                        n==1?"h":null,1,Instant.EPOCH,n==count?"e1":null));
            });
            return ((ResultSetExtractor<?>)call.getArgument(1)).extractData(rows);
        }).when(jdbc).query(any(PreparedStatementCreator.class),any(ResultSetExtractor.class));
        var store=new JdbcMatchStateStore(jdbc,new TransactionTemplate(manager),mapper,"updates","score.{matchId}");
        var loaded=store.load("m").orElseThrow();
        assertEquals(count,loaded.revision());assertEquals(count,loaded.state().processedEvents().size());
        assertEquals(new Score(0,0),loaded.state().score());assertTrue(loaded.state().annulledGoalIds().contains("e1"));
        verify(manager).commit(any());
    }
}
