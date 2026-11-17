package com.live_jgv.betting.integration;
import org.junit.jupiter.api.Test;import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.flywaydb.core.Flyway;import org.springframework.jdbc.core.JdbcTemplate;import org.springframework.jdbc.datasource.DataSourceTransactionManager;import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;
/** PREPARED, NOT RUN. Separate explicit approval and fresh PostgreSQL17 endpoint required. */
@EnabledIfEnvironmentVariable(named="DEV3_RUN_ACADEMIC_POSTGRES_TESTS",matches="true")
class AcademicPreparedPostgresTest {
 @Test void academicPersistenceRestartDedupAndRollback() {var ds=IsolatedPostgres.approvedFreshDatabase();var jdbc=new JdbcTemplate(ds);
 Flyway.configure().dataSource(ds).schemas("betting_control").defaultSchema("betting_control").cleanDisabled(true).load().migrate();
 var tx=new TransactionTemplate(new DataSourceTransactionManager(ds));var store=new com.live_jgv.betting.infrastructure.JdbcSuspensionStore(jdbc,tx,java.time.Clock.systemUTC(),true,"betting_commands");
  var raw=new com.live_jgv.betting.domain.InputRecord("academic/1","academic-test",0,0,0,new byte[]{1},new byte[]{2},"[]");
  var goal=new com.live_jgv.betting.domain.SportEvent("goal","match",com.live_jgv.betting.domain.SportEvent.EventType.GOAL,"home",10,java.time.Instant.EPOCH,null);
  var annul=new com.live_jgv.betting.domain.SportEvent("annul","match",com.live_jgv.betting.domain.SportEvent.EventType.GOAL_ANNULLED,"home",11,java.time.Instant.EPOCH,"goal");
  store.handle(raw,com.live_jgv.betting.domain.DecodedInput.valid(goal,"goal-image"));
  var second=new com.live_jgv.betting.domain.InputRecord("academic/1","academic-test",0,1,0,new byte[]{1},new byte[]{3},"[]");
  store.handle(second,com.live_jgv.betting.domain.DecodedInput.valid(annul,"annul-image"));
  assertEquals(2,jdbc.queryForObject("SELECT count(*) FROM betting_control.academic_outbox",Integer.class));
  assertEquals("goal",jdbc.queryForObject("SELECT source_reference_event_id FROM betting_control.academic_outbox WHERE source_event_id='annul'",String.class));
  var restarted=new com.live_jgv.betting.infrastructure.JdbcSuspensionStore(jdbc,tx,java.time.Clock.systemUTC(),true,"betting_commands");
  restarted.handle(second,com.live_jgv.betting.domain.DecodedInput.valid(annul,"annul-image"));
  assertEquals(2,jdbc.queryForObject("SELECT count(*) FROM betting_control.academic_outbox",Integer.class));
  assertFalse(restarted.status("match").betsAllowed());
  assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM betting_control.authorization_lease",Integer.class));
  jdbc.execute("ALTER TABLE betting_control.academic_outbox ADD CONSTRAINT reject_future_test CHECK(source_event_id <> 'failure')");
  var failure=new com.live_jgv.betting.domain.SportEvent("failure","match",com.live_jgv.betting.domain.SportEvent.EventType.GOAL,"home",12,java.time.Instant.EPOCH,null);
  var third=new com.live_jgv.betting.domain.InputRecord("academic/1","academic-test",0,2,0,new byte[]{1},new byte[]{4},"[]");
  assertThrows(org.springframework.dao.DataAccessException.class,()->restarted.handle(third,com.live_jgv.betting.domain.DecodedInput.valid(failure,"failure-image")));
  assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM betting_control.processed_event WHERE event_id='failure'",Integer.class));
  assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM betting_control.input_receipt WHERE record_offset=2",Integer.class));
 }
}
