package com.live_jgv.matchstate.infrastructure.jdbc;

import com.live_jgv.matchstate.infrastructure.config.MessagingConfiguration;
import java.sql.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class JdbcPublicationSessionTest {
    @Test void pendingPayloadAndAcknowledgementUseOnlyTheOwningAutocommitConnection() throws Exception {
        var source=mock(DataSource.class);var connection=mock(Connection.class);
        when(source.getConnection()).thenReturn(connection);when(connection.getAutoCommit()).thenReturn(true);
        String payload="{\"match_id\":\"m\",\"event_id\":\"g\",\"event_type\":\"GOAL\",\"home_score\":1,\"away_score\":0,\"var_status\":\"CLEAR\",\"updated_at\":\"2026-11-15T00:00:00Z\"}";
        when(connection.prepareStatement(anyString())).thenAnswer(call->{
            String sql=call.getArgument(0);var statement=mock(PreparedStatement.class);
            when(statement.executeQuery()).thenAnswer(c->{
                var rows=mock(ResultSet.class);when(rows.next()).thenReturn(true,false);
                when(rows.getBoolean(1)).thenReturn(true);
                when(rows.getString(1)).thenReturn(payload);when(rows.getString(2)).thenReturn("updates");
                when(rows.getString(3)).thenReturn("score.m");when(rows.getLong(4)).thenReturn(1L);return rows;
            });
            when(statement.executeUpdate()).thenReturn(1);
            if(sql.contains("score_outbox") && !sql.startsWith("UPDATE")) assertTrue(sql.contains("NOT published"));
            return statement;
        });
        var transactions=mock(TransactionTemplate.class);
        var store=new JdbcMatchStateStore(new JdbcTemplate(source),transactions,
                new MessagingConfiguration().messagingObjectMapper(),"updates","score.{matchId}");
        store.withPublicationLock("m",()->{
            var update=store.pendingForMatch("m").get(0);
            assertEquals(payload,store.storedPublication(update).orElseThrow().payload());
            store.markPublished(update);
        });
        verify(source,times(1)).getConnection();verify(connection).close();
        verify(connection,never()).setAutoCommit(false);verifyNoInteractions(transactions);
        verify(connection).prepareStatement(contains("LIMIT 128"));
        verify(connection).prepareStatement("SELECT pg_advisory_unlock(?)");
    }
}
