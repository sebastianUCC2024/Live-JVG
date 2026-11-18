package com.live_jgv.betting.infrastructure;

import com.live_jgv.betting.domain.*;
import com.live_jgv.betting.application.SuspensionStore;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;
import org.springframework.dao.DataAccessResourceFailureException;
import java.time.*;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

class JdbcSuspensionStoreTest {
    private void prepareV2Closure() {
        prepareClosure();
        when(jdbc.queryForList(contains("SELECT * FROM betting_control.processed_event"),eq("m"),eq("continue")))
                .thenReturn(List.of(Map.of("event_type","MATCH_CONTINUES","minute",10,"event_timestamp",now.toString(),"applied_version",2L,"reference_event_id","var")));
    }
    private String v2Image() throws Exception {
        var image=new TreeMap<String,Object>();image.put("reference_mode","V2_REVIEW");image.put("closure",closure());
        return new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules()
                .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).writeValueAsString(image);
    }
    @Test void v2ClosureUsesSameLockTransactionAndStoresExplicitReferenceMode() {
        prepareV2Closure();assertTrue(store.closeReviewV2(closure()).betsAllowed());
        verify(jdbc).update(contains("INSERT INTO betting_control.closure_command"),eq("m"),eq("cmd"),contains("\"reference_mode\":\"V2_REVIEW\""),eq("var"),eq("continue"),eq("operator"),eq("verified"),eq(3L));
        verify(jdbc).update(contains("UPDATE betting_control.pending_review"),eq("continue"),eq("cmd"),eq("m"),eq("var"));
        verify(manager).commit(any());
    }
    @Test void duplicateV2ClosureAfterNewSuspensionCannotReopen() throws Exception {
        prepareV2Closure();var current=row("SUSPENDED",4);current.put("last_event_id","new-goal");
        when(jdbc.queryForMap(contains("market_state"),eq("m"))).thenReturn(current);
        when(jdbc.queryForList(contains("canonical_request FROM betting_control.closure_command"),eq("m"),eq("cmd")))
                .thenReturn(List.of(Map.of("canonical_request",v2Image())));
        assertEquals(4,store.closeReviewV2(closure()).version());
        assertFalse(store.closeReviewV2(closure()).betsAllowed());
        verify(jdbc,never()).update(contains("UPDATE betting_control.pending_review"),any(Object[].class));
        verify(jdbc,never()).update(contains("suspension_outbox"),any(Object[].class));
    }
    @Test void sameCommandCannotChangeReferenceModeAcrossReplays() throws Exception {
        prepareV2Closure();
        when(jdbc.queryForList(contains("canonical_request FROM betting_control.closure_command"),eq("m"),eq("cmd")))
                .thenReturn(List.of(Map.of("canonical_request",v2Image())));
        assertThrows(IllegalArgumentException.class,()->store.closeReview(closure()));
        verify(manager).rollback(any());
    }
    @Test void v2CannotFallbackToLegacyNullReference() {
        prepareClosure();assertThrows(IllegalArgumentException.class,()->store.closeReviewV2(closure()));
        verify(manager).rollback(any());verify(jdbc,never()).update(contains("closure_command("),any(Object[].class));
    }
    @Test void goalStoresBlockingEvidenceAndSuspendedOutboxBeforeCommit() {
        var goal=new SportEvent("goal","m",SportEvent.EventType.GOAL,"home",10,now,null);
        store.handle(record,DecodedInput.valid(goal,"goal-image"));
        verify(jdbc).update(contains("INSERT INTO betting_control.pending_review"),eq("m"),eq("goal"),eq("goal"),eq(1L));
        verify(jdbc).update(contains("suspension_outbox"),anyString(),eq("m"),eq(1L),eq("BETTING_SUSPENDED"),contains("\"bets_allowed\":false"));
        verify(manager).commit(any());
    }
    @Test void missingClosureEvidenceWriteCannotCommitReopening() {
        prepareClosure();when(jdbc.update(contains("INSERT INTO betting_control.closure_command"),any(Object[].class))).thenReturn(0);
        assertThrows(IllegalStateException.class,()->store.closeReview(closure()));
        verify(manager).rollback(any());verify(jdbc,never()).update(contains("UPDATE betting_control.pending_review"),any(Object[].class));
    }
    @Test void missingOutboxWriteRollsBackReopeningAndItsEvidence() {
        prepareClosure();when(jdbc.update(contains("INSERT INTO betting_control.suspension_outbox"),any(Object[].class))).thenReturn(0);
        assertThrows(IllegalStateException.class,()->store.closeReview(closure()));
        verify(manager).rollback(any());verify(manager,never()).commit(any());
    }
    @Test void lateContinuationTimestampCannotCloseReview() {
        prepareClosure();when(jdbc.queryForList(contains("SELECT event_timestamp"),eq("m"),eq("var")))
                .thenReturn(List.of(Map.of("event_timestamp",now.plusSeconds(1).toString())));
        assertThrows(IllegalArgumentException.class,()->store.closeReview(closure()));
        verify(manager).rollback(any());verify(jdbc,never()).update(contains("closure_command("),any(Object[].class));
    }
    @Test void expiredProofRollsBackWithoutPublishingResumption() {
        prepareClosure();var expired=new ReviewClosure("cmd","m","var","continue",2,"o","r",now.minusSeconds(2),now.minusSeconds(1));
        assertThrows(IllegalArgumentException.class,()->store.closeReview(expired));
        verify(manager).rollback(any());verify(jdbc,never()).update(contains("suspension_outbox"),any(Object[].class));
    }
    private JdbcTemplate jdbc;private PlatformTransactionManager manager;private JdbcSuspensionStore store;
    private final Instant now=Instant.parse("2026-11-15T00:00:00Z");
    private final InputRecord record=new InputRecord("s","match_events",0,7,1,new byte[]{'m'},new byte[]{1},"[]");
    private SportEvent event(String id,SportEvent.EventType type) { return new SportEvent(id,"m",type,null,10,now,null); }
    private DecodedInput valid() { return DecodedInput.valid(event("var",SportEvent.EventType.VAR_CHECK),"canonical"); }
    private Map<String,Object> row(String status,long version) {
        var row=new HashMap<String,Object>();row.put("match_id","m");row.put("status",status);row.put("version",version);
        row.put("last_event_id",version==0?null:"var");row.put("updated_at",version==0?null:now.toString());row.put("inconsistent",false);return row;
    }
    @BeforeEach void setup() {
        jdbc=mock(JdbcTemplate.class);manager=mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());store=new JdbcSuspensionStore(jdbc,new TransactionTemplate(manager),Clock.fixed(now,ZoneOffset.UTC));
        when(jdbc.update(anyString(),any(Object[].class))).thenReturn(1);
        when(jdbc.queryForMap(contains("market_state"),eq("m"))).thenReturn(row("UNKNOWN",0));
        when(jdbc.queryForList(anyString(),any(Object[].class))).thenReturn(List.of());
    }
    @Test void rowLockPrecedesIdentityCheckAndStateHistoryOutboxCommitTogether() {
        assertEquals(SuspensionStore.Outcome.APPLIED,store.handle(record,valid()));
        var order=inOrder(jdbc,manager);order.verify(manager).getTransaction(any());
        order.verify(jdbc).update(contains("INSERT INTO betting_control.input_receipt"),any(Object[].class));
        order.verify(jdbc).update(contains("INSERT INTO betting_control.market_state"),eq("m"));
        order.verify(jdbc).queryForMap(contains("FOR UPDATE"),eq("m"));
        order.verify(jdbc).queryForList(contains("pending_review"),eq("m"));
        order.verify(jdbc).queryForList(contains("processed_event"),eq("m"),eq("var"));
        order.verify(jdbc).update(contains("INSERT INTO betting_control.processed_event"),any(Object[].class));
        order.verify(jdbc).update(contains("INSERT INTO betting_control.pending_review"),any(Object[].class));
        order.verify(jdbc).update(contains("UPDATE betting_control.market_state"),any(Object[].class));
        order.verify(jdbc).update(contains("transition_history"),any(Object[].class));
        order.verify(jdbc).update(contains("suspension_outbox"),any(Object[].class));
        order.verify(jdbc).update(contains("UPDATE betting_control.input_receipt"),any(Object[].class));order.verify(manager).commit(any());
    }
    @Test void wireContinuationNeverClosesReviewOrCreatesResumedMessage() {
        when(jdbc.queryForMap(contains("market_state"),eq("m"))).thenReturn(row("SUSPENDED",1));
        when(jdbc.queryForList(contains("FROM betting_control.pending_review"),eq("m")))
            .thenReturn(List.of(Map.of("review_id","var","reference_event_id","goal","opened_version",1L,"blocking_type","VAR_CHECK")));
        store.handle(record,DecodedInput.valid(event("continue",SportEvent.EventType.MATCH_CONTINUES),"continue-image"));
        verify(jdbc,never()).update(contains("UPDATE betting_control.pending_review"),any(Object[].class));
        verify(jdbc,never()).update(contains("suspension_outbox"),any(Object[].class));
        verify(jdbc).update(contains("UPDATE betting_control.market_state"),eq("SUSPENDED"),eq(2L),eq("continue"),anyString(),eq(false),eq("m"),eq(1L));
    }
    @Test void realDecoderAliasReplayNeverCreatesAnotherTransitionOrAcademicCommand() {
        String old="{\"event_id\":\"annul\",\"match_id\":\"m\",\"event_type\":\"GOAL_ANNULLED\",\"minute\":10,\"timestamp\":\"2026-11-15T00:00:00Z\",\"reference_event_id\":\"goal\"}";
        var decoder=new JsonEventDecoder();
        java.util.function.BiFunction<String,Long,InputRecord> input=(wire,offset)->new InputRecord("s","match_events",0,offset,123,new byte[]{'m'},wire.getBytes(java.nio.charset.StandardCharsets.UTF_8),"[]");
        var legacy=input.apply(old,1L);var alias=input.apply(old.replace("reference_event_id","annuls_event_id"),2L);
        when(jdbc.queryForList(contains("canonical_payload FROM betting_control.processed_event"),eq("m"),eq("annul")))
            .thenReturn(List.of(Map.of("canonical_payload",decoder.decode(legacy).canonicalPayload())));
        var academic=new JdbcSuspensionStore(jdbc,new TransactionTemplate(manager),Clock.fixed(now,ZoneOffset.UTC),true,"betting_commands");
        assertEquals(SuspensionStore.Outcome.DUPLICATE,academic.handle(alias,decoder.decode(alias)));
        verify(jdbc,never()).update(contains("UPDATE betting_control.market_state"),any(Object[].class));
        verify(jdbc,never()).update(contains("INSERT INTO betting_control.processed_event"),any(Object[].class));
        verify(jdbc,never()).update(contains("academic_outbox"),any(Object[].class));
        verify(jdbc).update(contains("INSERT INTO betting_control.input_receipt"),eq("s"),eq("match_events"),eq(0),eq(2L),eq(123L),org.mockito.AdditionalMatchers.aryEq(alias.key()),org.mockito.AdditionalMatchers.aryEq(alias.value()),eq("[]"));
    }
    @Test void exactDuplicateHasNoNewRevisionTransitionOrOutbox() {
        when(jdbc.queryForList(contains("canonical_payload FROM betting_control.processed_event"),eq("m"),eq("var")))
            .thenReturn(List.of(Map.of("canonical_payload","canonical")));
        assertEquals(SuspensionStore.Outcome.DUPLICATE,store.handle(record,valid()));
        verify(jdbc,never()).update(contains("UPDATE betting_control.market_state"),any(Object[].class));
        verify(jdbc,never()).update(contains("suspension_outbox"),any(Object[].class));
    }
    @Test void identityConflictBlocksAndNeverReplacesOriginalEvent() {
        when(jdbc.queryForMap(contains("market_state"),eq("m"))).thenReturn(row("OPEN",3));
        when(jdbc.queryForList(contains("canonical_payload FROM betting_control.processed_event"),eq("m"),eq("var")))
            .thenReturn(List.of(Map.of("canonical_payload","different")));
        assertEquals(SuspensionStore.Outcome.IDENTITY_CONFLICT,store.handle(record,valid()));
        verify(jdbc).update(contains("UPDATE betting_control.market_state"),eq("UNKNOWN"),eq(4L),any(),any(),eq(true),eq("m"),eq(3L));
        verify(jdbc,never()).update(contains("INSERT INTO betting_control.processed_event"),any(Object[].class));
    }
    @Test void invalidInputIsQuarantinedWithoutTouchingAMarket() {
        assertEquals(SuspensionStore.Outcome.QUARANTINED,store.handle(record,DecodedInput.invalid(DecodedInput.Reason.INVALID_EVENT)));
        verify(jdbc,never()).queryForMap(contains("market_state"),any(Object[].class));verify(manager).commit(any());
    }
    @Test void outboxInsertionFailureRollsBackEventStateAndReceipt() {
        when(jdbc.update(contains("INSERT INTO betting_control.suspension_outbox"),any(Object[].class)))
            .thenThrow(new DataAccessResourceFailureException("down"));
        assertThrows(DataAccessResourceFailureException.class,()->store.handle(record,valid()));
        verify(manager).rollback(any());verify(manager,never()).commit(any());
    }
    @Test void optimisticRevisionMismatchRollsBackConcurrentChange() {
        when(jdbc.update(contains("UPDATE betting_control.market_state"),any(Object[].class))).thenReturn(0);
        assertThrows(IllegalStateException.class,()->store.handle(record,valid()));
        verify(manager).rollback(any());verify(jdbc,never()).update(contains("suspension_outbox"),any(Object[].class));
    }
    @Test void finalizingQuarantineMustSucceedBeforeCommit() {
        when(jdbc.update(contains("UPDATE betting_control.input_receipt"),any(Object[].class))).thenReturn(0);
        assertThrows(IllegalStateException.class,()->store.handle(record,DecodedInput.invalid(DecodedInput.Reason.INVALID_EVENT)));
        verify(manager).rollback(any());verify(manager,never()).commit(any());
    }
    @Test void identicalSourcePositionIsIdempotentWithoutMarketMutation() {
        when(jdbc.update(contains("INSERT INTO betting_control.input_receipt"),any(Object[].class))).thenReturn(0);
        when(jdbc.queryForMap(contains("input_receipt"),any(Object[].class))).thenReturn(Map.of("original_key",record.key(),"original_payload",record.value(),"original_headers","[]","broker_timestamp",1L,"outcome","APPLIED"));
        assertEquals(SuspensionStore.Outcome.APPLIED,store.handle(record,valid()));
        verify(jdbc,never()).queryForMap(contains("market_state"),any(Object[].class));
    }
    @Test void sourcePositionCollisionFailsWithoutOverwritingEvidence() {
        when(jdbc.update(contains("INSERT INTO betting_control.input_receipt"),any(Object[].class))).thenReturn(0);
        when(jdbc.queryForMap(contains("input_receipt"),any(Object[].class))).thenReturn(Map.of("original_key",new byte[]{3},"original_payload",record.value(),"original_headers","[]","broker_timestamp",1L,"outcome","APPLIED"));
        assertThrows(IllegalStateException.class,()->store.handle(record,valid()));verify(manager).rollback(any());
    }
    @Test void unknownReadDoesNotInsertOrAuthorizeMarket() {
        assertFalse(store.status("unknown").betsAllowed());verify(jdbc,never()).update(anyString(),any(Object[].class));
    }
    @Test void freshAdapterReadsDurableStateAndPendingReviewsInsteadOfLocalCache() {
        when(jdbc.queryForList(contains("FROM betting_control.market_state"),eq("m"))).thenReturn(List.of(row("SUSPENDED",3)));
        when(jdbc.queryForList(contains("FROM betting_control.pending_review"),eq("m")))
            .thenReturn(List.of(Map.of("review_id","var","reference_event_id","goal","opened_version",1L,"blocking_type","VAR_CHECK")));
        assertEquals(1,new JdbcSuspensionStore(jdbc,new TransactionTemplate(manager),Clock.systemUTC()).status("m").pendingReviews().size());
    }
    private ReviewClosure closure() { return new ReviewClosure("cmd","m","var","continue",2,"operator","verified",now.minusSeconds(1),now.plusSeconds(5)); }
    private void prepareClosure() {
        var current=row("SUSPENDED",2);current.put("last_event_id","continue");
        when(jdbc.queryForMap(contains("market_state"),eq("m"))).thenReturn(current);
        when(jdbc.queryForList(contains("FROM betting_control.pending_review"),eq("m")))
            .thenReturn(List.of(Map.of("review_id","var","reference_event_id","goal","opened_version",1L,"blocking_type","VAR_CHECK")));
        when(jdbc.queryForList(contains("SELECT * FROM betting_control.processed_event"),eq("m"),eq("continue")))
            .thenReturn(List.of(Map.of("event_type","MATCH_CONTINUES","minute",10,"event_timestamp",now.toString(),"applied_version",2L)));
        when(jdbc.queryForList(contains("SELECT event_timestamp"),eq("m"),eq("var")))
            .thenReturn(List.of(Map.of("event_timestamp",now.toString())));
    }
    @Test void trustedClosureValidatesLedgerAndProducesResumptionInSameTransaction() {
        prepareClosure();assertTrue(store.closeReview(closure()).betsAllowed());
        verify(jdbc).update(contains("closure_command"),any(Object[].class));
        verify(jdbc).update(contains("UPDATE betting_control.pending_review"),any(Object[].class));
        verify(jdbc).update(contains("suspension_outbox"),anyString(),eq("m"),eq(3L),eq("BETTING_RESUMED"),contains("\"bets_allowed\":true"));verify(manager).commit(any());
    }
    @Test void duplicateClosureCommandDoesNotCloseTwiceEvenAfterFurtherEvents() throws Exception {
        prepareClosure();String canonical=new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules().disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).writeValueAsString(closure());
        when(jdbc.queryForList(contains("canonical_request FROM betting_control.closure_command"),eq("m"),eq("cmd")))
            .thenReturn(List.of(Map.of("canonical_request",canonical)));
        store.closeReview(closure());verify(jdbc,never()).update(contains("UPDATE betting_control.pending_review"),any(Object[].class));
    }
    @Test void missingContinuationCannotCloseAndRollsBackCommand() {
        assertThrows(IllegalArgumentException.class,()->store.closeReview(closure()));
        verify(jdbc,never()).update(contains("UPDATE betting_control.pending_review"),any(Object[].class));verify(manager).rollback(any());
    }
    @Test void concurrentlyClosedReviewRollsBackAndCannotPublishResumption() {
        prepareClosure();when(jdbc.update(contains("UPDATE betting_control.pending_review"),any(Object[].class))).thenReturn(0);
        assertThrows(IllegalStateException.class,()->store.closeReview(closure()));
        verify(manager).rollback(any());verify(jdbc,never()).update(contains("suspension_outbox"),any(Object[].class));
    }
    @Test void changedClosureCommandIdentityCannotOpen() {
        prepareClosure();when(jdbc.queryForList(contains("canonical_request FROM betting_control.closure_command"),eq("m"),eq("cmd")))
            .thenReturn(List.of(Map.of("canonical_request","different")));
        assertThrows(IllegalArgumentException.class,()->store.closeReview(closure()));verify(manager).rollback(any());
    }
}
