package com.live_jgv.worker.integration;

import com.live_jgv.worker.application.*;
import com.live_jgv.worker.domain.*;
import com.live_jgv.worker.infrastructure.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.flywaydb.core.Flyway;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** Prepared real two-connection tests. Never enabled by default and never starts infrastructure. */
@EnabledIfEnvironmentVariable(named="DEV3_RUN_ATOMIC_POSTGRES_TESTS",matches="true")
class AtomicPostgresConcurrencyTest {
    @Test void lostDatabaseConnectionAfterGrantCannotCommitAcceptance() {
        String m=openMarket();lease(m);var c=command(m);var live=authority();
        AtomicAuthorizationPort interrupted=cmd->{var session=live.lock(cmd);return new AtomicAuthorizationPort.Session(){
            public boolean authoritative(){return session.authoritative();}
            public MarketEvidence evidence(){return session.evidence();}
            public String refusal(){return session.refusal();}
            public AtomicAuthorizationPort.Grant recordGrant(String canonical){
                var grant=session.recordGrant(canonical);assertTrue(grant.allowed());
                // Kill only this test transaction's own connection, never another session or server.
                client.execute("SELECT pg_terminate_backend(pg_backend_pid())");
                return grant;
            }
        };};
        assertThrows(RuntimeException.class,()->process(store(interrupted),c));
        assertEquals(0,owner.queryForObject("SELECT count(*) FROM betting_worker.operation WHERE operation_id=?",Integer.class,c.operationId()));
        assertEquals(0,owner.queryForObject("SELECT count(*) FROM betting_worker.operation_authorization WHERE operation_id=?",Integer.class,c.operationId()));
        assertEquals(0,owner.queryForObject("SELECT count(*) FROM betting_worker.command_inbox WHERE command_id=?",Integer.class,c.commandId()));
    }
    @Test void workerCannotWriteAuthorityOrForgeGrantDirectly() {
        for(String statement:List.of("UPDATE betting_control.market_state SET status='OPEN' WHERE FALSE",
                "DELETE FROM betting_worker.operation_authorization WHERE FALSE")) {
            var failure=assertThrows(org.springframework.dao.DataAccessException.class,()->client.update(statement));
            boolean denied=false;
            for(Throwable cause=failure;cause!=null;cause=cause.getCause())
                if(cause instanceof java.sql.SQLException sql && "42501".equals(sql.getSQLState()))denied=true;
            assertTrue(denied,"Expected PostgreSQL insufficient_privilege, not an unrelated SQL error");
        }
    }
    @Test void simultaneousIdenticalCommandsCommitOneOperationAndOneGrant() throws Exception {
        String m=openMarket();lease(m);var c=command(m);var start=new CountDownLatch(1);
        var a=pool.submit(()->{await(start);return process(store(authority()),c);});
        var b=pool.submit(()->{await(start);return process(store(authority()),c);});start.countDown();
        var outcomes=List.of(a.get(5,TimeUnit.SECONDS),b.get(5,TimeUnit.SECONDS));
        assertTrue(outcomes.contains(WorkerStore.Outcome.PROCESSED));assertTrue(outcomes.contains(WorkerStore.Outcome.DUPLICATE));
        assertEquals(1,owner.queryForObject("SELECT count(*) FROM betting_worker.operation WHERE operation_id=?",Integer.class,c.operationId()));
        assertEquals(1,owner.queryForObject("SELECT count(*) FROM betting_worker.operation_authorization WHERE operation_id=?",Integer.class,c.operationId()));
        assertEquals(2L,owner.queryForObject("SELECT delivery_attempts FROM betting_worker.command_inbox WHERE command_id=?",Long.class,c.commandId()));
        assertEquals("ACCEPTED",state(c.operationId()));
    }
    private static JdbcTemplate owner,client;
    private static DriverManagerDataSource ownerDs,clientDs;
    private static String epoch,database,workerRole;
    private final ExecutorService pool=Executors.newFixedThreadPool(2);
    @BeforeAll static void approvedMigrationsAndRestrictedRole(){
        workerRole=System.getenv("DEV3_ATOMIC_WORKER_USERNAME");String password=System.getenv("DEV3_ATOMIC_WORKER_PASSWORD");
        if(workerRole==null||!workerRole.matches("dev3_it_worker_[A-Za-z0-9_]+")||password==null||password.isBlank())
            throw new IllegalArgumentException("Pre-created dedicated restricted worker role required");
        ownerDs=IsolatedPostgres.approvedFreshDatabase();owner=new JdbcTemplate(ownerDs);
        assertEquals(0,owner.queryForObject("SELECT count(*) FROM pg_catalog.pg_namespace WHERE nspname='betting_control'",Integer.class));
        Flyway.configure().dataSource(ownerDs).schemas("betting_control").defaultSchema("betting_control")
            .locations("filesystem:"+Path.of("../betting-suspension-service/src/main/resources/db/migration").toAbsolutePath())
            .cleanDisabled(true).load().migrate();
        Flyway.configure().dataSource(ownerDs).schemas("betting_worker").defaultSchema("betting_worker").cleanDisabled(true).load().migrate();
        String role="\""+workerRole+"\"";
        owner.execute("GRANT USAGE ON SCHEMA betting_control,betting_worker TO "+role);
        owner.execute("GRANT SELECT,INSERT,UPDATE ON betting_worker.market_guard,betting_worker.command_inbox,betting_worker.operation,betting_worker.operation_transition,betting_worker.operation_evidence,betting_worker.message_audit TO "+role);
        owner.execute("GRANT EXECUTE ON FUNCTION betting_control.lock_market_authority(text) TO "+role);
        owner.execute("GRANT EXECUTE ON FUNCTION betting_worker.record_atomic_authorization(text,text,bigint,uuid,uuid) TO "+role);
        database=owner.queryForObject("SELECT current_database()",String.class);
        epoch=owner.queryForObject("SELECT epoch::text FROM betting_control.authorization_authority",String.class);
        clientDs=new DriverManagerDataSource(System.getenv("DEV3_IT_DB_URL"),workerRole,password);client=new JdbcTemplate(clientDs);
        assertEquals(workerRole,client.queryForObject("SELECT current_user::text",String.class));
        assertEquals(0,client.queryForObject("""
                SELECT count(*)::integer FROM pg_catalog.pg_roles WHERE rolname=current_user
                    AND (rolsuper OR rolcreaterole OR rolcreatedb OR rolreplication OR rolbypassrls)
                """,Integer.class));
        assertEquals(0,client.queryForObject("""
                SELECT count(*)::integer FROM pg_catalog.pg_class c
                JOIN pg_catalog.pg_namespace n ON n.oid=c.relnamespace
                WHERE n.nspname IN ('betting_control','betting_worker')
                    AND pg_has_role(current_user,c.relowner,'MEMBER')
                """,Integer.class));
        new JdbcAtomicAuthorization(client,database,epoch).verifyInstallation();
        owner.execute("CREATE TABLE betting_worker.it_deadlock_probe(id INTEGER PRIMARY KEY,value INTEGER NOT NULL)");
        owner.update("INSERT INTO betting_worker.it_deadlock_probe VALUES (1,0),(2,0)");
        owner.execute("GRANT SELECT,UPDATE ON betting_worker.it_deadlock_probe TO "+role);
    }
    @AfterEach void stopOnlyTestThreads(){pool.shutdownNow();}
    private TransactionTemplate tx(DriverManagerDataSource ds){return new TransactionTemplate(new DataSourceTransactionManager(ds));}
    private String openMarket(){
        String match="atomic-"+UUID.randomUUID();owner.update("INSERT INTO betting_control.market_state(match_id,status,version,last_event_id,updated_at) VALUES (?,'OPEN',3,'continued',?)",match,Instant.now().toString());
        return match;
    }
    private void lease(String match){
        owner.update("""
            WITH sampled AS (SELECT clock_timestamp() AS now)
            INSERT INTO betting_control.authorization_lease(match_id,lease_id,market_version,issued_at,valid_until,source_checkpoint,actor,reason)
            SELECT ?,?::uuid,3,sampled.now,sampled.now+INTERVAL '5 seconds','test-checkpoint','test-operator','isolated test fixture' FROM sampled
            """,match,UUID.randomUUID().toString());
    }
    private JdbcWorkerStore store(AtomicAuthorizationPort authority){return new JdbcWorkerStore(client,tx(clientDs),MarketEvidence::unavailable,
        new AcceptancePolicy(true,Duration.ofSeconds(5),Duration.ofMinutes(5)),Clock.systemUTC(),authority);}
    private AtomicAuthorizationPort authority(){return new JdbcAtomicAuthorization(client,database,epoch);}
    private SimulatedCommand command(String match){String id=UUID.randomUUID().toString();return new SimulatedCommand("c-"+id,"o-"+id,match,"sim-user",Instant.now());}
    private WorkerStore.Outcome process(JdbcWorkerStore store,SimulatedCommand c){return store.handle(new TransportMessage("atomic-it",null,"it","it",new byte[]{1},"{}"),new DecodedCommand(c,"image-"+c.commandId(),null));}
    private String state(String op){return owner.queryForObject("SELECT status FROM betting_worker.operation WHERE operation_id=?",String.class,op);}
    private void suspend(String match){owner.update("UPDATE betting_control.market_state SET status='SUSPENDED',version=version+1 WHERE match_id=?",match);}
    private static void await(CountDownLatch latch){try{if(!latch.await(4,TimeUnit.SECONDS))throw new IllegalStateException("Test barrier timed out");}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}}

    @Test void suspensionBeforeAuthorizationBlocksAfterWaitingForItsCommit() throws Exception {
        String m=openMarket();lease(m);var c=command(m);var locked=new CountDownLatch(1);var release=new CountDownLatch(1);
        var first=pool.submit(()->tx(ownerDs).execute(s->{owner.queryForMap("SELECT * FROM betting_control.market_state WHERE match_id=? FOR UPDATE",m);suspend(m);locked.countDown();await(release);return null;}));
        await(locked);var attempted=new CountDownLatch(1);var live=authority();
        var second=pool.submit(()->process(store(cmd->{attempted.countDown();return live.lock(cmd);}),c));
        await(attempted);try{assertThrows(TimeoutException.class,()->second.get(150,TimeUnit.MILLISECONDS));}finally{release.countDown();}
        first.get(5,TimeUnit.SECONDS);second.get(5,TimeUnit.SECONDS);assertEquals("BLOCKED",state(c.operationId()));
    }
    @Test void authorizationBeforeSuspensionKeepsSourceLockUntilOperationCommit() throws Exception {
        String m=openMarket();lease(m);var c=command(m);var locked=new CountDownLatch(1);var release=new CountDownLatch(1);var live=authority();
        var first=pool.submit(()->process(store(cmd->{var session=live.lock(cmd);locked.countDown();await(release);return session;}),c));
        await(locked);var attempted=new CountDownLatch(1);var second=pool.submit(()->{attempted.countDown();suspend(m);});
        await(attempted);try{assertThrows(TimeoutException.class,()->second.get(150,TimeUnit.MILLISECONDS));}finally{release.countDown();}
        first.get(5,TimeUnit.SECONDS);second.get(5,TimeUnit.SECONDS);assertEquals("ACCEPTED",state(c.operationId()));
        assertEquals("SUSPENDED",owner.queryForObject("SELECT status FROM betting_control.market_state WHERE match_id=?",String.class,m));
        assertEquals(1,owner.queryForObject("SELECT count(*) FROM betting_worker.operation_authorization WHERE operation_id=?",Integer.class,c.operationId()));
    }
    @Test void twoConcurrentOperationsSerializeAndDuplicatesRemainUnique() throws Exception {
        String m=openMarket();lease(m);var a=command(m);var b=command(m);
        var first=pool.submit(()->process(store(authority()),a));var second=pool.submit(()->process(store(authority()),b));
        first.get(5,TimeUnit.SECONDS);second.get(5,TimeUnit.SECONDS);assertEquals("ACCEPTED",state(a.operationId()));assertEquals("ACCEPTED",state(b.operationId()));
        assertEquals(WorkerStore.Outcome.DUPLICATE,process(store(authority()),a));
    }
    @Test void unknownAndExpiredOpenCannotAccept(){
        var unknown=command("missing-"+UUID.randomUUID());process(store(authority()),unknown);assertEquals("BLOCKED",state(unknown.operationId()));
        String m=openMarket();lease(m);owner.update("UPDATE betting_control.authorization_lease SET issued_at=clock_timestamp()-INTERVAL '10 seconds',valid_until=clock_timestamp()-INTERVAL '9 seconds' WHERE match_id=?",m);
        var c=command(m);process(store(authority()),c);assertEquals("BLOCKED",state(c.operationId()));
    }
    @Test void versionMismatchAndRestartCannotReuseOldPermission(){
        String m=openMarket();lease(m);owner.update("UPDATE betting_control.market_state SET version=4 WHERE match_id=?",m);
        var c=command(m);process(store(authority()),c);assertEquals("BLOCKED",state(c.operationId()));
        assertEquals(Operation.State.BLOCKED,store(authority()).find(c.operationId()).orElseThrow().state());
    }
    @Test void unavailableAuthorityCannotLeaveCommittedOperation(){
        String m=openMarket();lease(m);var c=command(m);
        owner.execute("REVOKE EXECUTE ON FUNCTION betting_control.lock_market_authority(text) FROM \""+workerRole+"\"");
        try{assertThrows(RuntimeException.class,()->process(store(authority()),c));}
        finally{owner.execute("GRANT EXECUTE ON FUNCTION betting_control.lock_market_authority(text) TO \""+workerRole+"\"");}
        assertEquals(0,owner.queryForObject("SELECT count(*) FROM betting_worker.operation WHERE operation_id=?",Integer.class,c.operationId()));
    }
    @Test void rollbackAfterAuthorizationRemovesOperationAndGrantTogether(){
        String m=openMarket();lease(m);var c=command(m);
        assertThrows(IllegalStateException.class,()->tx(clientDs).execute(s->{process(store(authority()),c);throw new IllegalStateException("forced rollback");}));
        assertEquals(0,owner.queryForObject("SELECT count(*) FROM betting_worker.operation WHERE operation_id=?",Integer.class,c.operationId()));
        assertEquals(0,owner.queryForObject("SELECT count(*) FROM betting_worker.operation_authorization WHERE operation_id=?",Integer.class,c.operationId()));
    }
    @Test void leaseExpiringAfterGrantRollsBackAtDeferredCommitCheck(){
        String m=openMarket();lease(m);var c=command(m);
        assertThrows(RuntimeException.class,()->tx(clientDs).execute(s->{
            process(store(authority()),c);assertEquals(Operation.State.ACCEPTED,store(authority()).find(c.operationId()).orElseThrow().state());
            client.execute("SELECT pg_sleep(5.1)");return null;
        }));
        assertEquals(0,owner.queryForObject("SELECT count(*) FROM betting_worker.operation WHERE operation_id=?",Integer.class,c.operationId()));
        assertEquals(0,owner.queryForObject("SELECT count(*) FROM betting_worker.operation_authorization WHERE operation_id=?",Integer.class,c.operationId()));
    }
    @Test void grantCannotCommitSeparatelyFromItsAcceptance(){
        String m=openMarket();lease(m);var c=command(m);String image="image-"+c.commandId();
        client.update("INSERT INTO betting_worker.command_inbox(command_id,operation_id,canonical_payload,original_payload,outcome) VALUES (?,?,?,?,'PENDING')",c.commandId(),c.operationId(),image,new byte[]{1});
        client.update("INSERT INTO betting_worker.market_guard(match_id) VALUES (?)",m);
        client.update("INSERT INTO betting_worker.operation(operation_id,command_id,match_id,simulated_user_id,status,version,command_timestamp,received_at,updated_at) VALUES (?,?,?,'sim-user','RECEIVED',0,?,?,?)",
            c.operationId(),c.commandId(),m,Instant.now().toString(),Instant.now().toString(),Instant.now().toString());
        assertThrows(RuntimeException.class,()->tx(clientDs).execute(status->{assertTrue(authority().lock(c).recordGrant(image).allowed());return null;}));
        assertEquals(0,owner.queryForObject("SELECT count(*) FROM betting_worker.operation_authorization WHERE operation_id=?",Integer.class,c.operationId()));
        assertEquals("RECEIVED",state(c.operationId()));
    }
    @Test void directAcceptedWithoutCurrentTransactionGrantFailsAtCommit(){
        String m=openMarket();var c=command(m);process(store(AtomicAuthorizationPort.disabled()),c);
        assertThrows(RuntimeException.class,()->tx(clientDs).execute(s->{client.update("UPDATE betting_worker.operation SET status='ACCEPTED',reason='forged' WHERE operation_id=?",c.operationId());return null;}));
        assertEquals("BLOCKED",state(c.operationId()));
    }
    @Test void deadlockVictimCanRetryInANewTransaction() throws Exception {
        var barrier=new CyclicBarrier(2);
        java.util.function.IntFunction<Boolean> contender=first->{try{tx(clientDs).execute(s->{
            client.update("UPDATE betting_worker.it_deadlock_probe SET value=value+1 WHERE id=?",first);
            try{barrier.await(4,TimeUnit.SECONDS);}catch(Exception e){throw new IllegalStateException(e);}
            client.update("UPDATE betting_worker.it_deadlock_probe SET value=value+1 WHERE id=?",3-first);return null;});return true;
        }catch(RuntimeException failure){boolean deadlock=false;for(Throwable cause=failure;cause!=null;cause=cause.getCause())if(cause instanceof java.sql.SQLException sql&&"40P01".equals(sql.getSQLState()))deadlock=true;
            if(!deadlock)throw failure;return false;}};
        var a=pool.submit(()->contender.apply(1));var b=pool.submit(()->contender.apply(2));
        boolean first=a.get(8,TimeUnit.SECONDS),second=b.get(8,TimeUnit.SECONDS);assertNotEquals(first,second);
        tx(clientDs).execute(s->{client.update("UPDATE betting_worker.it_deadlock_probe SET value=value+1 WHERE id IN (1,2)");return null;});
        assertEquals(4,owner.queryForObject("SELECT sum(value)::integer FROM betting_worker.it_deadlock_probe",Integer.class));
    }
}
