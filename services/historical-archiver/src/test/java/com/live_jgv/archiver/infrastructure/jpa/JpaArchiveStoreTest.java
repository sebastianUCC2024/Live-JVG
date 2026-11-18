package com.live_jgv.archiver.infrastructure.jpa;

import com.live_jgv.archiver.domain.*;
import org.junit.jupiter.api.*;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.transaction.*;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.test.util.ReflectionTestUtils;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.AdditionalMatchers.aryEq;

class JpaArchiveStoreTest {
    ArchivedEventRepository events;InputReceiptRepository receipts;JpaArchiveStore store;TestTransactions transactions;
    InputReceiptEntity receipt;
    final InputRecord record=new InputRecord("source","match_events",0,1,123,new byte[]{1},new byte[]{2},"[]");
    final SportEvent goal=new SportEvent("goal","match",SportEvent.EventType.GOAL,"home",10,Instant.EPOCH,null);
    final DecodedInput valid=DecodedInput.valid(goal,"canonical");
    @BeforeEach void prepare() {
        events=mock(ArchivedEventRepository.class);receipts=mock(InputReceiptRepository.class);transactions=new TestTransactions();
        store=new JpaArchiveStore(events,receipts,transactions);receipt=new InputReceiptEntity();
        when(receipts.insertPending(any(),any(),anyInt(),anyLong(),anyLong(),any(),any(),any())).thenReturn(1);
        when(receipts.lock(any())).thenReturn(Optional.of(receipt));
        when(events.insertIfAbsent(any(),any(),any(),any(),anyInt(),any(),any(),any(),any())).thenReturn(1);
    }
    @Test void nativeInsertAndManagedReceiptCompleteBeforeCommit() {
        assertEquals(ArchiveOutcome.STORED,store.save(record,valid));
        var order=inOrder(receipts,events);
        order.verify(receipts).insertPending(eq("source"),eq("match_events"),eq(0),eq(1L),eq(123L),aryEq(record.key()),aryEq(record.value()),eq("[]"));
        order.verify(receipts).lock(new InputReceiptId("source","match_events",0,1));
        order.verify(events).insertIfAbsent("goal","match","GOAL","home",10,Instant.EPOCH.toString(),null,record.value(),"canonical");
        order.verify(receipts).flush();
        assertEquals(ArchiveOutcome.STORED,receipt.outcome());
        assertEquals("match",ReflectionTestUtils.getField(receipt,"matchId"));
        assertEquals("goal",ReflectionTestUtils.getField(receipt,"eventId"));
        assertEquals(1,transactions.commits);assertEquals(0,transactions.rollbacks);
    }
    @Test void identicalEventAtAnotherOffsetIsDuplicateButDifferentCanonicalIsConflict() {
        when(events.insertIfAbsent(any(),any(),any(),any(),anyInt(),any(),any(),any(),any())).thenReturn(0);
        var prior=new ArchivedEventEntity();ReflectionTestUtils.setField(prior,"canonicalPayload","canonical");
        when(events.findByMatchIdAndEventId("match","goal")).thenReturn(Optional.of(prior));
        assertEquals(ArchiveOutcome.DUPLICATE,store.save(record,valid));
        assertEquals(ArchiveOutcome.IDENTITY_CONFLICT,store.save(record,DecodedInput.valid(goal,"changed")));
        assertEquals("EVENT_ID_REUSED",ReflectionTestUtils.getField(receipt,"reasonCode"));
    }
    @Test void samePositionReturnsOriginalOutcomeWithoutAnotherEventOrUpdate() {
        priorReceipt();
        assertEquals(ArchiveOutcome.STORED,store.save(record,valid));
        verifyNoInteractions(events);verify(receipts,never()).flush();
    }
    @Test void reusedPositionWithChangedEvidenceRollsBack() {
        priorReceipt();
        var changed=new InputRecord("source","match_events",0,1,123,new byte[]{1},new byte[]{9},"[]");
        assertThrows(IllegalStateException.class,()->store.save(changed,valid));
        assertEquals(1,transactions.rollbacks);assertEquals(0,transactions.commits);verifyNoInteractions(events);
    }
    @Test void invalidInputKeepsRawReceiptWithoutArchivedEvent() {
        assertEquals(ArchiveOutcome.QUARANTINED,store.save(record,DecodedInput.invalid(DecodedInput.Reason.MALFORMED_JSON)));
        verifyNoInteractions(events);assertNull(ReflectionTestUtils.getField(receipt,"eventId"));
        assertEquals("MALFORMED_JSON",ReflectionTestUtils.getField(receipt,"reasonCode"));
        verify(receipts).flush();assertEquals(1,transactions.commits);
    }
    @Test void flushFailureEscapesAndRollsBackBeforeListenerCanReturn() {
        doThrow(new org.springframework.dao.DataAccessResourceFailureException("failure")).when(receipts).flush();
        assertThrows(org.springframework.dao.DataAccessResourceFailureException.class,()->store.save(record,valid));
        assertEquals(1,transactions.rollbacks);assertEquals(0,transactions.commits);
    }
    @Test void historyUsesOrmAndRepeatableReadSnapshotForCountAndRows() {
        var entity=mock(ArchivedEventEntity.class);
        var row=new HistoryPage.Entry(1,"goal","match","GOAL","home",10,Instant.EPOCH,null,Instant.EPOCH);
        when(entity.historyEntry()).thenReturn(row);when(events.countByMatchId("match")).thenReturn(3L);
        when(events.history(eq("match"),any())).thenReturn(List.of(entity));
        var history=store.find("match",1,2);
        assertEquals(List.of(row),history.content());assertEquals(2,history.totalPages());assertEquals(3,history.totalElements());
        assertEquals(TransactionDefinition.ISOLATION_REPEATABLE_READ,transactions.isolation);assertTrue(transactions.readOnly);
        var order=inOrder(events);order.verify(events).countByMatchId("match");
        order.verify(events).history("match",org.springframework.data.domain.PageRequest.of(1,2));
    }
    @Test void largeOffsetsRetainTheExistingLongPaginationSemantics() {
        when(events.countByMatchId("match")).thenReturn(3L);
        when(events.historyAtOffset("match",429496729400L,200)).thenReturn(List.of());
        var history=store.find("match",Integer.MAX_VALUE,200);
        assertTrue(history.content().isEmpty());assertEquals(3,history.totalElements());
        verify(events).historyAtOffset("match",429496729400L,200);verify(events,never()).history(any(),any());
    }
    @Test void refusesNonJpaTransactionManager() {
        assertThrows(IllegalArgumentException.class,()->new JpaArchiveStore(events,receipts,mock(PlatformTransactionManager.class)));
    }
    private void priorReceipt() {
        when(receipts.insertPending(any(),any(),anyInt(),anyLong(),anyLong(),any(),any(),any())).thenReturn(0);
        ReflectionTestUtils.setField(receipt,"brokerTimestamp",123L);ReflectionTestUtils.setField(receipt,"originalKey",record.key());
        ReflectionTestUtils.setField(receipt,"originalPayload",record.value());ReflectionTestUtils.setField(receipt,"originalHeaders","[]");
        ReflectionTestUtils.setField(receipt,"outcome","STORED");
    }
    /** Callback-boundary double only; PostgreSQL tests separately verify real transaction semantics. */
    static class TestTransactions extends JpaTransactionManager {
        int commits,rollbacks,isolation;boolean readOnly;
        @Override protected Object doGetTransaction(){return new Object();}
        @Override protected boolean isExistingTransaction(Object transaction){return false;}
        @Override protected void doBegin(Object transaction,TransactionDefinition definition){isolation=definition.getIsolationLevel();readOnly=definition.isReadOnly();}
        @Override protected void doCommit(DefaultTransactionStatus status){commits++;}
        @Override protected void doRollback(DefaultTransactionStatus status){rollbacks++;}
        @Override protected void doCleanupAfterCompletion(Object transaction){ }
    }
}
