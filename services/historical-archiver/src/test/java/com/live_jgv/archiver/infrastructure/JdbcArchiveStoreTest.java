package com.live_jgv.archiver.infrastructure;

import com.live_jgv.archiver.domain.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.*;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;
import org.springframework.dao.DataAccessResourceFailureException;
import org.mockito.ArgumentCaptor;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class JdbcArchiveStoreTest {
    private JdbcTemplate jdbc;
    private PlatformTransactionManager manager;
    private JdbcArchiveStore store;
    private final InputRecord record=new InputRecord("s","match_events",0,7,123,
            "m".getBytes(StandardCharsets.UTF_8),"raw".getBytes(StandardCharsets.UTF_8),"[]");
    private final SportEvent event=new SportEvent("e","m",SportEvent.EventType.GOAL,"h",10,Instant.parse("2026-11-15T00:00:00.123456789Z"),null);
    private final DecodedInput valid=DecodedInput.valid(event,"canonical");
    @BeforeEach void setup() {
        jdbc=mock(JdbcTemplate.class);manager=mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        store=new JdbcArchiveStore(jdbc,new TransactionTemplate(manager));
        when(jdbc.update(anyString(),any(Object[].class))).thenReturn(1);
    }
    @Test void commitsRawEventAndReceiptTogether() {
        assertEquals(ArchiveOutcome.STORED,store.save(record,valid));
        var order=inOrder(jdbc,manager);order.verify(manager).getTransaction(any());
        order.verify(jdbc).update(contains("INSERT INTO archiver.input_receipt"),any(Object[].class));
        order.verify(jdbc).update(contains("INSERT INTO archiver.archived_event"),eq("e"),eq("m"),eq("GOAL"),eq("h"),eq(10),eq(event.timestamp().toString()),isNull(),aryEq(record.value()),eq("canonical"));
        order.verify(jdbc).update(contains("UPDATE archiver.input_receipt"),any(Object[].class));
        order.verify(manager).commit(any());verify(manager,never()).rollback(any());
    }
    @Test void duplicateAtANewOffsetDoesNotCreateAnotherEvent() {
        when(jdbc.update(contains("INSERT INTO archiver.archived_event"),any(Object[].class))).thenReturn(0);
        when(jdbc.queryForObject(contains("SELECT canonical_payload"),eq(String.class),eq("m"),eq("e"))).thenReturn("canonical");
        assertEquals(ArchiveOutcome.DUPLICATE,store.save(record,valid));verify(manager).commit(any());
    }
    @Test void reusedEventIdWithDifferentContentIsDurablyFlagged() {
        when(jdbc.update(contains("INSERT INTO archiver.archived_event"),any(Object[].class))).thenReturn(0);
        when(jdbc.queryForObject(contains("SELECT canonical_payload"),eq(String.class),eq("m"),eq("e"))).thenReturn("other");
        assertEquals(ArchiveOutcome.IDENTITY_CONFLICT,store.save(record,valid));
        verify(jdbc).update(contains("UPDATE archiver.input_receipt"),eq("IDENTITY_CONFLICT"),eq("m"),eq("e"),eq("EVENT_ID_REUSED"),eq("s"),eq("match_events"),eq(0),eq(7L));
    }
    @Test void sameEventIdInAnotherMatchIsAnIndependentArchiveIdentity() {
        var other=new SportEvent("e","other",SportEvent.EventType.GOAL,"h",10,event.timestamp(),null);
        assertEquals(ArchiveOutcome.STORED,store.save(record,DecodedInput.valid(other,"other-canonical")));
        verify(jdbc).update(contains("ON CONFLICT (match_id,event_id)"),eq("e"),eq("other"),any(),any(),any(),any(),any(),any(),any());
    }
    @Test void malformedRecordCommitsEvidenceWithoutAttemptingToInsertAnEvent() {
        assertEquals(ArchiveOutcome.QUARANTINED,store.save(record,DecodedInput.invalid(DecodedInput.Reason.MALFORMED_JSON)));
        verify(jdbc,never()).update(contains("INSERT INTO archiver.archived_event"),any(Object[].class));verify(manager).commit(any());
    }
    @Test void postgresFailureRollsBackReceiptAndEventAndEscapes() {
        when(jdbc.update(contains("INSERT INTO archiver.archived_event"),any(Object[].class)))
            .thenThrow(new DataAccessResourceFailureException("unavailable"));
        assertThrows(DataAccessResourceFailureException.class,()->store.save(record,valid));
        verify(manager).rollback(any());verify(manager,never()).commit(any());
    }
    @Test void quarantineFinalizationFailureRollsBackAndDoesNotAcknowledge() {
        when(jdbc.update(contains("UPDATE archiver.input_receipt"),any(Object[].class))).thenReturn(0);
        assertThrows(IllegalStateException.class,()->store.save(record,DecodedInput.invalid(DecodedInput.Reason.INVALID_EVENT)));
        verify(manager).rollback(any());verify(manager,never()).commit(any());
    }
    @Test void redeliveryOfSameSourcePositionReusesDurableOutcome() {
        priorReceipt(record.value());assertEquals(ArchiveOutcome.STORED,store.save(record,valid));
        verify(jdbc,never()).update(contains("INSERT INTO archiver.archived_event"),any(Object[].class));
    }
    @Test void changedSourcePositionCannotOverwriteOriginalEvidence() {
        priorReceipt(new byte[]{1});assertThrows(IllegalStateException.class,()->store.save(record,valid));
        verify(manager).rollback(any());verify(jdbc,never()).update(contains("UPDATE archiver.input_receipt"),any(Object[].class));
    }
    private void priorReceipt(byte[] value) {
        when(jdbc.update(contains("INSERT INTO archiver.input_receipt"),any(Object[].class))).thenReturn(0);
        when(jdbc.queryForMap(contains("FOR UPDATE"),any(Object[].class))).thenReturn(Map.of(
                "original_key",record.key(),"original_payload",value,"original_headers","[]","broker_timestamp",123L,"outcome","STORED"));
    }
    @Test void paginationUsesBoundSqlLimitOffsetAndRepeatableReadSnapshot() throws Exception {
        when(jdbc.queryForObject(contains("count(*)"),eq(Long.class),eq("m"))).thenReturn(5L);
        when(jdbc.query(contains("ORDER BY seq ASC LIMIT ? OFFSET ?"),any(RowMapper.class),eq("m"),eq(2),eq(2L)))
            .thenAnswer(call->{
                RowMapper<HistoryPage.Entry> mapper=call.getArgument(1);var rs=mock(ResultSet.class);
                when(rs.getLong("seq")).thenReturn(3L);when(rs.getString("event_id")).thenReturn("e");
                when(rs.getString("event_timestamp")).thenReturn(event.timestamp().toString());
                when(rs.getTimestamp("received_at")).thenReturn(Timestamp.from(Instant.EPOCH));
                return List.of(mapper.mapRow(rs,0));
            });
        var page=store.find("m",1,2);assertEquals(3,page.totalPages());assertEquals(5,page.totalElements());
        assertEquals(3,page.content().get(0).sequence());assertEquals(event.timestamp(),page.content().get(0).timestamp());
        var definition=ArgumentCaptor.forClass(TransactionDefinition.class);verify(manager).getTransaction(definition.capture());
        assertTrue(definition.getValue().isReadOnly());assertEquals(TransactionDefinition.ISOLATION_REPEATABLE_READ,definition.getValue().getIsolationLevel());
    }
    @Test void highPageDoesNotOverflowAnIntegerOffset() {
        when(jdbc.queryForObject(anyString(),eq(Long.class),eq("m"))).thenReturn(0L);
        when(jdbc.query(anyString(),any(RowMapper.class),eq("m"),eq(200),eq(429496729400L))).thenReturn(List.of());
        assertEquals(0,store.find("m",Integer.MAX_VALUE,200).totalPages());
    }
    private static byte[] aryEq(byte[] value) { return org.mockito.AdditionalMatchers.aryEq(value); }
}
