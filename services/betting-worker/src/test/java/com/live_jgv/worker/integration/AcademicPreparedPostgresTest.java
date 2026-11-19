package com.live_jgv.worker.integration;
import org.junit.jupiter.api.Test;import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.flywaydb.core.Flyway;import org.springframework.jdbc.core.JdbcTemplate;import org.springframework.jdbc.datasource.DataSourceTransactionManager;import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;
/** PREPARED, NOT RUN. Separate explicit approval and fresh PostgreSQL17 endpoint required. */
@EnabledIfEnvironmentVariable(named="DEV3_RUN_ACADEMIC_POSTGRES_TESTS",matches="true")
class AcademicPreparedPostgresTest {
 @Test void academicPersistenceRestartDedupAndRollback() {var ds=IsolatedPostgres.approvedFreshDatabase();var jdbc=new JdbcTemplate(ds);
 Flyway.configure().dataSource(ds).schemas("betting_worker").defaultSchema("betting_worker").cleanDisabled(true).load().migrate();
 var tx=new TransactionTemplate(new DataSourceTransactionManager(ds));var store=new com.live_jgv.worker.academic.JdbcAcademicCommandStore(jdbc,tx);
  var command=new com.live_jgv.worker.academic.AcademicCommand(1,"command","match","goal",com.live_jgv.worker.academic.AcademicCommand.CommandType.SUSPEND_BETS,java.time.Instant.EPOCH);
  var raw=new com.live_jgv.worker.domain.TransportMessage("academic/1","command","","betting_commands",command.json().getBytes(java.nio.charset.StandardCharsets.UTF_8),"{}");
  assertEquals(com.live_jgv.worker.academic.AcademicCommandStore.Outcome.RECORDED,store.record(command,raw));
  var restarted=new com.live_jgv.worker.academic.JdbcAcademicCommandStore(jdbc,tx);
  assertEquals(com.live_jgv.worker.academic.AcademicCommandStore.Outcome.DUPLICATE,restarted.record(command,raw));
  assertEquals("SIMULATED_SUSPENDED",restarted.find("command").orElseThrow().simulatedStatus());
  assertEquals(2,restarted.find("command").orElseThrow().deliveryAttempts());
  assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM betting_worker.operation WHERE status='ACCEPTED'",Integer.class));
  assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM betting_worker.operation_authorization",Integer.class));
  jdbc.execute("ALTER TABLE betting_worker.academic_delivery ADD CONSTRAINT reject_future_test CHECK(command_id <> 'failure')");
  var fail=new com.live_jgv.worker.academic.AcademicCommand(1,"failure","match","annul",com.live_jgv.worker.academic.AcademicCommand.CommandType.RESUME_BETS,java.time.Instant.EPOCH);
  assertThrows(org.springframework.dao.DataAccessException.class,()->restarted.record(fail,raw));
  assertTrue(restarted.find("failure").isEmpty());
 }
}
