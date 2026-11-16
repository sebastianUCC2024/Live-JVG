package com.live_jgv.worker.infrastructure;

import com.live_jgv.worker.application.*;
import com.live_jgv.worker.domain.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;
import org.springframework.dao.DataAccessResourceFailureException;
import java.time.*;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

class JdbcWorkerStoreTest {
    private JdbcTemplate jdbc;private PlatformTransactionManager tx;private SuspensionEvidencePort source;private JdbcWorkerStore store;
    private final Instant now=Instant.parse("2026-11-15T00:00:00Z");
    private final SimulatedCommand command=new SimulatedCommand("c","o","m","sim-user",now);
    private final DecodedCommand decoded=new DecodedCommand(command,"canonical",null);
    private final TransportMessage message=new TransportMessage("source","broker-id","x","k",new byte[]{1},"{}");
    private Map<String,Object> guard(MarketEvidence.Status status,long revision){
        var row=new HashMap<String,Object>();row.put("match_id","m");row.put("guard_version",revision);row.put("evidence_revision",revision);
        row.put("frozen",false);row.put("status",status.name());row.put("authority",revision==0?null:"a");row.put("epoch",revision==0?null:"e");
        row.put("issued_at",revision==0?null:now.minusSeconds(1).toString());row.put("expires_at",revision==0?null:now.plusSeconds(3).toString());return row;
    }
    @BeforeEach void setup(){
        jdbc=mock(JdbcTemplate.class);tx=mock(PlatformTransactionManager.class);source=mock(SuspensionEvidencePort.class);
        when(tx.getTransaction(any())).thenReturn(new SimpleTransactionStatus());when(jdbc.update(anyString(),any(Object[].class))).thenReturn(1);
        when(jdbc.queryForMap(contains("market_guard"),eq("m"))).thenReturn(guard(MarketEvidence.Status.UNKNOWN,0));
        when(source.fetch("m")).thenReturn(new MarketEvidence("m","a","e",1,MarketEvidence.Status.OPEN,now.minusSeconds(1),now.plusSeconds(3)));
        store=new JdbcWorkerStore(jdbc,new TransactionTemplate(tx),source,new AcceptancePolicy(true,Duration.ofSeconds(5),Duration.ofMinutes(5)),Clock.fixed(now,ZoneOffset.UTC));
    }
    @Test void commandEvidenceHistoryAndOperationCommitAtomicallyAfterMarketLock(){
        assertEquals(WorkerStore.Outcome.PROCESSED,store.handle(message,decoded));
        var order=inOrder(jdbc,source,tx);order.verify(tx).getTransaction(any());
        order.verify(jdbc).update(contains("INSERT INTO betting_worker.command_inbox"),any(Object[].class));
        order.verify(jdbc).update(contains("INSERT INTO betting_worker.market_guard"),eq("m"));
        order.verify(jdbc).queryForMap(contains("FOR UPDATE"),eq("m"));
        order.verify(jdbc).update(contains("INSERT INTO betting_worker.operation("),any(Object[].class));
        order.verify(jdbc).update(contains("operation_transition"),any(Object[].class));order.verify(source).fetch("m");
        order.verify(jdbc).update(contains("UPDATE betting_worker.market_guard"),any(Object[].class));
        order.verify(jdbc).update(contains("operation_evidence"),any(Object[].class));
        order.verify(jdbc).update(contains("UPDATE betting_worker.operation SET"),eq("BLOCKED"),eq("ATOMIC_AUTHORIZATION_REQUIRED"),eq(1L),anyString(),eq("o"),eq(0L));
        order.verify(jdbc).update(contains("operation_transition"),any(Object[].class));
        order.verify(jdbc).update(contains("SET outcome"),eq("PROCESSED"),eq("c"));order.verify(tx).commit(any());
    }
    @Test void explicitAtomicGrantIsRequiredBeforeAcceptedAndCommit(){
        var atomic=mock(AtomicAuthorizationPort.class);var session=mock(AtomicAuthorizationPort.Session.class);
        when(atomic.lock(command)).thenReturn(session);when(session.authoritative()).thenReturn(true);
        when(session.evidence()).thenReturn(new MarketEvidence("m","postgres-atomic:test","epoch",3,MarketEvidence.Status.OPEN,now.minusSeconds(1),now.plusSeconds(3)));
        when(session.recordGrant("canonical")).thenReturn(new AtomicAuthorizationPort.Grant(true,"ATOMIC_POSTGRES_AUTHORIZED"));
        var atomicStore=new JdbcWorkerStore(jdbc,new TransactionTemplate(tx),source,new AcceptancePolicy(true,Duration.ofSeconds(5),Duration.ofMinutes(5)),Clock.fixed(now,ZoneOffset.UTC),atomic);
        atomicStore.handle(message,decoded);verifyNoInteractions(source);
        var order=inOrder(atomic,jdbc,session,tx);order.verify(atomic).lock(command);
        order.verify(jdbc).queryForMap(contains("market_guard"),eq("m"));order.verify(session).recordGrant("canonical");
        order.verify(jdbc).update(contains("UPDATE betting_worker.operation SET"),eq("ACCEPTED"),eq("ATOMIC_POSTGRES_AUTHORIZED"),eq(1L),anyString(),eq("o"),eq(0L));order.verify(tx).commit(any());
    }
    @Test void atomicSessionCannotResetLocalGuardRevisionOnAnIdenticalProof(){
        var atomic=mock(AtomicAuthorizationPort.class);var session=mock(AtomicAuthorizationPort.Session.class);
        when(atomic.lock(command)).thenReturn(session);when(session.authoritative()).thenReturn(true);
        when(session.evidence()).thenReturn(new MarketEvidence("m","a","e",1,MarketEvidence.Status.OPEN,now.minusSeconds(1),now.plusSeconds(3)));
        when(session.recordGrant("canonical")).thenReturn(new AtomicAuthorizationPort.Grant(true,"ATOMIC_POSTGRES_AUTHORIZED"));
        var row=guard(MarketEvidence.Status.OPEN,1);row.put("guard_version",8L);
        when(jdbc.queryForMap(contains("market_guard"),eq("m"))).thenReturn(row);
        new JdbcWorkerStore(jdbc,new TransactionTemplate(tx),source,new AcceptancePolicy(true,Duration.ofSeconds(5),Duration.ofMinutes(5)),Clock.fixed(now,ZoneOffset.UTC),atomic).handle(message,decoded);
        verify(jdbc,never()).update(contains("UPDATE betting_worker.market_guard"),any(Object[].class));
    }
    @Test void deniedAtomicGrantCannotBecomeAccepted(){
        var atomic=mock(AtomicAuthorizationPort.class);var session=mock(AtomicAuthorizationPort.Session.class);
        when(atomic.lock(command)).thenReturn(session);when(session.authoritative()).thenReturn(true);
        when(session.evidence()).thenReturn(new MarketEvidence("m","postgres-atomic:test","epoch",3,MarketEvidence.Status.OPEN,now.minusSeconds(1),now.plusSeconds(3)));
        when(session.recordGrant("canonical")).thenReturn(new AtomicAuthorizationPort.Grant(false,"AUTHORITY_CHANGED_OR_EXPIRED"));
        new JdbcWorkerStore(jdbc,new TransactionTemplate(tx),source,new AcceptancePolicy(true,Duration.ofSeconds(5),Duration.ofMinutes(5)),Clock.fixed(now,ZoneOffset.UTC),atomic).handle(message,decoded);
        verify(jdbc).update(contains("UPDATE betting_worker.operation SET"),eq("BLOCKED"),eq("AUTHORITY_CHANGED_OR_EXPIRED"),eq(1L),anyString(),eq("o"),eq(0L));
    }
    @Test void failedStatusLookupProducesDurableBlockedNotAcceptance(){
        when(source.fetch("m")).thenThrow(new IllegalStateException("unavailable"));store.handle(message,decoded);
        verify(jdbc).update(contains("UPDATE betting_worker.operation SET"),eq("BLOCKED"),eq("STATE_UNAVAILABLE_OR_UNKNOWN"),eq(1L),anyString(),eq("o"),eq(0L));verify(tx).commit(any());
    }
    @Test void failedLookupCannotFallBackToPersistedOpenEvenBeforeExpiry(){
        when(jdbc.queryForMap(contains("market_guard"),eq("m"))).thenReturn(guard(MarketEvidence.Status.OPEN,3));
        when(source.fetch("m")).thenThrow(new IllegalStateException("source unavailable"));store.handle(message,decoded);
        verify(jdbc).update(contains("UPDATE betting_worker.operation SET"),eq("BLOCKED"),eq("STATE_UNAVAILABLE_OR_UNKNOWN"),eq(1L),anyString(),eq("o"),eq(0L));
        verify(jdbc,never()).update(contains("UPDATE betting_worker.market_guard"),any(Object[].class));
    }
    @Test void persistedSuspensionRejectsAnOlderOpenProof(){
        when(jdbc.queryForMap(contains("market_guard"),eq("m"))).thenReturn(guard(MarketEvidence.Status.SUSPENDED,2));store.handle(message,decoded);
        verify(jdbc,never()).update(contains("UPDATE betting_worker.market_guard"),any(Object[].class));
        verify(jdbc).update(contains("UPDATE betting_worker.operation SET"),eq("BLOCKED"),eq("STALE_MARKET_REVISION"),eq(1L),anyString(),eq("o"),eq(0L));
    }
    @Test void exactDuplicateNeverExecutesAgainOrQueriesSuspension(){
        when(jdbc.update(contains("INSERT INTO betting_worker.command_inbox"),any(Object[].class))).thenReturn(0);
        when(jdbc.queryForMap(contains("command_inbox"),eq("c"))).thenReturn(Map.of("canonical_payload","canonical","outcome","PROCESSED"));
        assertEquals(WorkerStore.Outcome.DUPLICATE,store.handle(message,decoded));verifyNoInteractions(source);
        verify(jdbc,never()).update(contains("INSERT INTO betting_worker.operation("),any(Object[].class));
    }
    @Test void commandIdentityConflictPreservesOriginalOperation(){
        when(jdbc.update(contains("INSERT INTO betting_worker.command_inbox"),any(Object[].class))).thenReturn(0);
        when(jdbc.queryForMap(contains("command_inbox"),eq("c"))).thenReturn(Map.of("canonical_payload","different","outcome","PROCESSED"));
        assertEquals(WorkerStore.Outcome.IDENTITY_CONFLICT,store.handle(message,decoded));verify(jdbc).update(contains("message_audit"),any(Object[].class));verifyNoInteractions(source);
    }
    @Test void differentCommandCannotCreateAnotherOperationWithSameIdentity(){
        when(jdbc.update(contains("INSERT INTO betting_worker.operation("),any(Object[].class))).thenReturn(0);
        assertEquals(WorkerStore.Outcome.IDENTITY_CONFLICT,store.handle(message,decoded));verifyNoInteractions(source);
        verify(jdbc).update(contains("SET outcome"),eq("IDENTITY_CONFLICT"),eq("c"));
    }
    @Test void invalidMessageHasDurableAuditBeforeAckWithoutOperation(){
        assertEquals(WorkerStore.Outcome.QUARANTINED,store.handle(message,DecodedCommand.invalid("MALFORMED_JSON")));
        var order=inOrder(jdbc,tx);order.verify(jdbc).update(contains("message_audit"),any(Object[].class));order.verify(tx).commit(any());verifyNoInteractions(source);
    }
    @Test void failedQuarantineCannotBeAcknowledged(){
        when(jdbc.update(contains("message_audit"),any(Object[].class))).thenThrow(new DataAccessResourceFailureException("down"));
        assertThrows(DataAccessResourceFailureException.class,()->store.handle(message,DecodedCommand.invalid("INVALID")));verify(tx).rollback(any());verify(tx,never()).commit(any());
    }
    @Test void zeroRowQuarantineWriteCannotBeMistakenForDurableDisposition(){
        when(jdbc.update(contains("message_audit"),any(Object[].class))).thenReturn(0);
        assertThrows(IllegalStateException.class,()->store.handle(message,DecodedCommand.invalid("INVALID")));
        verify(tx).rollback(any());verify(tx,never()).commit(any());
    }
    @Test void postgresEvidenceFailureRollsBackEverything(){
        when(jdbc.update(contains("INSERT INTO betting_worker.operation_evidence"),any(Object[].class))).thenThrow(new DataAccessResourceFailureException("down"));
        assertThrows(DataAccessResourceFailureException.class,()->store.handle(message,decoded));verify(tx).rollback(any());verify(tx,never()).commit(any());
    }
    @Test void optimisticOperationConflictRollsBackAcceptance(){
        when(jdbc.update(contains("UPDATE betting_worker.operation SET"),any(Object[].class))).thenReturn(0);
        assertThrows(IllegalStateException.class,()->store.handle(message,decoded));verify(tx).rollback(any());verify(tx,never()).commit(any());
    }
    @Test void concurrentGuardVersionChangeCannotCommitAcceptance(){
        when(jdbc.update(contains("UPDATE betting_worker.market_guard"),any(Object[].class))).thenReturn(0);
        assertThrows(IllegalStateException.class,()->store.handle(message,decoded));verify(tx).rollback(any());
    }
    @Test void finalReceiptMustCommitTogetherWithOperation(){
        when(jdbc.update(contains("SET outcome"),any(Object[].class))).thenReturn(0);
        assertThrows(IllegalStateException.class,()->store.handle(message,decoded));verify(tx).rollback(any());
    }
    @Test void lateProviderReplyIsEvaluatedAtDecisionTimeNotAtStartOfTransaction(){
        Clock clock=mock(Clock.class);when(clock.instant()).thenReturn(now,now.plusSeconds(10));
        var delayed=new JdbcWorkerStore(jdbc,new TransactionTemplate(tx),source,new AcceptancePolicy(true,Duration.ofSeconds(5),Duration.ofMinutes(5)),clock);
        delayed.handle(message,decoded);
        verify(jdbc).update(contains("UPDATE betting_worker.operation SET"),eq("BLOCKED"),eq("STALE_OR_INVALID_EVIDENCE_TIME"),eq(1L),eq(now.plusSeconds(10).toString()),eq("o"),eq(0L));
    }
    @Test void freshAdapterCanReadPersistedTerminalOperationWithoutSourceOrCache(){
        when(jdbc.queryForList(contains("FROM betting_worker.operation"),eq("o"))).thenReturn(List.of(Map.of(
            "operation_id","o","command_id","c","match_id","m","simulated_user_id","private-user","status","BLOCKED","reason","SUSPENDED","version",1L,
            "command_timestamp",now.toString(),"received_at",now.toString(),"updated_at",now.toString())));
        var fresh=new JdbcWorkerStore(jdbc,new TransactionTemplate(tx),source,new AcceptancePolicy(false,Duration.ofSeconds(5),Duration.ofMinutes(5)),Clock.systemUTC());
        assertEquals(Operation.State.BLOCKED,fresh.find("o").orElseThrow().state());verifyNoInteractions(source);
    }
}
