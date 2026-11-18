package com.live_jgv.betting.academic;
import com.live_jgv.betting.infrastructure.JdbcSuspensionStore;
import com.live_jgv.betting.domain.*;
import com.live_jgv.betting.application.SuspensionStore;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;
import java.time.*;import java.util.*;
import static org.junit.jupiter.api.Assertions.*;import static org.mockito.Mockito.*;
class AcademicAtomicOutboxTest {
 JdbcTemplate jdbc;PlatformTransactionManager manager;JdbcSuspensionStore store;
 InputRecord input=new InputRecord("academic-test/1","match_events",0,1,1,new byte[]{1},new byte[]{2},"[]");
 SportEvent event(SportEvent.EventType t){return new SportEvent("event","match",t,"home",10,Instant.EPOCH,t==SportEvent.EventType.GOAL_ANNULLED?"original-goal":null);}
 @BeforeEach void setup(){jdbc=mock(JdbcTemplate.class);manager=mock(PlatformTransactionManager.class);
  when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
  when(jdbc.update(anyString(),any(Object[].class))).thenReturn(1);
  when(jdbc.queryForMap(contains("market_state"),eq("match"))).thenReturn(Map.of("match_id","match","status","SUSPENDED","version",1L,"last_event_id","old","updated_at",Instant.EPOCH.toString(),"inconsistent",false));
  when(jdbc.queryForList(anyString(),any(Object[].class))).thenReturn(List.of());
  store=new JdbcSuspensionStore(jdbc,new TransactionTemplate(manager),Clock.fixed(Instant.EPOCH,ZoneOffset.UTC),true,"betting_commands");
 }
 @Test void allFourNewEventsProduceAcademicCommandEvenWithoutMarketTransition(){for(var t:SportEvent.EventType.values()){
   clearInvocations(jdbc,manager);assertEquals(SuspensionStore.Outcome.APPLIED,store.handle(input,DecodedInput.valid(event(t),"image")));
   var order=inOrder(jdbc,manager);order.verify(jdbc).update(contains("INSERT INTO betting_control.processed_event"),any(Object[].class));
   order.verify(jdbc).update(contains("INSERT INTO betting_control.academic_outbox"),any(Object[].class));order.verify(manager).commit(any());
   verify(jdbc,never()).update(contains("INSERT INTO betting_control.suspension_outbox"),any(Object[].class));
 }}
 @Test void annulmentPreservesOriginalGoalAndTransportEvidence(){store.handle(input,DecodedInput.valid(event(SportEvent.EventType.GOAL_ANNULLED),"image"));
  verify(jdbc).update(contains("INSERT INTO betting_control.academic_outbox"),anyString(),eq("match"),eq("event"),eq(2L),eq("RESUME_BETS"),contains("\"schema_version\":1"),eq(input.value()),eq("original-goal"),eq(input.sourceId()),eq(input.topic()),eq(0),eq(1L),eq("[]"),eq("betting_commands"));
 }
 @Test void failedAcademicWriteRollsBackProcessedEvent(){when(jdbc.update(contains("INSERT INTO betting_control.academic_outbox"),any(Object[].class))).thenReturn(0);
  assertThrows(IllegalStateException.class,()->store.handle(input,DecodedInput.valid(event(SportEvent.EventType.GOAL),"image")));verify(manager).rollback(any());verify(manager,never()).commit(any());}
 @Test void duplicateHasNoSecondAcademicCommand(){when(jdbc.queryForList(contains("canonical_payload FROM betting_control.processed_event"),eq("match"),eq("event"))).thenReturn(List.of(Map.of("canonical_payload","image")));
  assertEquals(SuspensionStore.Outcome.DUPLICATE,store.handle(input,DecodedInput.valid(event(SportEvent.EventType.GOAL),"image")));verify(jdbc,never()).update(contains("academic_outbox"),any(Object[].class));}
 @Test void conflictingEventDoesNotCreateCommand(){when(jdbc.queryForList(contains("canonical_payload FROM betting_control.processed_event"),eq("match"),eq("event"))).thenReturn(List.of(Map.of("canonical_payload","other")));
  assertEquals(SuspensionStore.Outcome.IDENTITY_CONFLICT,store.handle(input,DecodedInput.valid(event(SportEvent.EventType.GOAL),"image")));verify(jdbc,never()).update(contains("academic_outbox"),any(Object[].class));}
 @Test void invalidEventHasNoAcademicWrite(){store.handle(input,DecodedInput.invalid(DecodedInput.Reason.INVALID_EVENT));verify(jdbc,never()).update(contains("academic_outbox"),any(Object[].class));}
 @Test void defaultOffDoesNotWriteAcademicTables(){new JdbcSuspensionStore(jdbc,new TransactionTemplate(manager),Clock.systemUTC()).handle(input,DecodedInput.valid(event(SportEvent.EventType.GOAL),"image"));verify(jdbc,never()).update(contains("academic_outbox"),any(Object[].class));}
}
