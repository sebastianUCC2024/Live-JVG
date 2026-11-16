package com.live_jgv.worker.infrastructure;

import com.live_jgv.worker.application.*;
import com.live_jgv.worker.domain.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import javax.sql.DataSource;
import java.sql.Connection;
import java.time.*;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

/** Validates transaction binding using a real Spring manager over a mock connection, not PostgreSQL atomicity. */
class JdbcAtomicAuthorizationTest {
    private JdbcTemplate jdbc;private JdbcAtomicAuthorization authority;private TransactionTemplate tx;private Connection connection;
    private final String epoch="00000000-0000-0000-0000-000000000001";
    private final SimulatedCommand command=new SimulatedCommand("c","o","m","sim-user",Instant.EPOCH);
    private Map<String,Object> row(){
        var r=new HashMap<String,Object>();r.put("database_name","dev3_test");r.put("authority_epoch",UUID.fromString(epoch));
        r.put("status","OPEN");r.put("market_version",3L);r.put("lease_version",3L);r.put("pending_reviews",0L);r.put("inconsistent",false);
        r.put("lease_id",UUID.fromString("00000000-0000-0000-0000-000000000002"));
        r.put("issued_at",Instant.EPOCH);r.put("valid_until",Instant.EPOCH.plusSeconds(5));r.put("checked_at",Instant.EPOCH.plusSeconds(1));return r;
    }
    @BeforeEach void setup() throws Exception {
        var ds=mock(DataSource.class);connection=mock(Connection.class);when(ds.getConnection()).thenReturn(connection);when(connection.getAutoCommit()).thenReturn(true);
        jdbc=mock(JdbcTemplate.class);when(jdbc.getDataSource()).thenReturn(ds);
        when(jdbc.queryForMap(contains("lock_market_authority"),eq("m"))).thenReturn(row());
        when(jdbc.queryForObject(contains("record_atomic_authorization"),eq(Boolean.class),any(Object[].class))).thenReturn(true);
        tx=new TransactionTemplate(new DataSourceTransactionManager(ds));authority=new JdbcAtomicAuthorization(jdbc,"dev3_test",epoch);
    }
    @Test void sourceLockAndGrantUseTheExistingSpringTransaction() throws Exception {
        tx.execute(status->{var session=authority.lock(command);assertTrue(session.authoritative());assertNull(session.refusal());
            assertTrue(session.recordGrant("canonical").allowed());return null;});
        var order=inOrder(jdbc,connection);order.verify(jdbc).queryForMap(contains("lock_market_authority"),eq("m"));
        order.verify(jdbc).queryForObject(contains("record_atomic_authorization"),eq(Boolean.class),any(Object[].class));order.verify(connection).commit();
    }
    @Test void noTransactionOrReadOnlyTransactionCannotLockAuthority(){
        assertThrows(IllegalStateException.class,()->authority.lock(command));tx.setReadOnly(true);
        assertThrows(IllegalStateException.class,()->tx.execute(status->authority.lock(command)));
    }
    @Test void sessionCannotBeUsedAfterCommitOrInAnotherTransaction(){
        var session=tx.execute(status->authority.lock(command));assertNotNull(session);
        assertThrows(IllegalStateException.class,()->session.recordGrant("canonical"));
        assertThrows(IllegalStateException.class,()->tx.execute(status->session.recordGrant("canonical")));
    }
    @Test void suspensionUnknownStaleLeaseAndVersionMismatchFailClosed(){
        for(String status:List.of("SUSPENDED","UNKNOWN")){
            var r=row();r.put("status",status);when(jdbc.queryForMap(contains("lock_market_authority"),eq("m"))).thenReturn(r);
            tx.execute(s->{assertFalse(authority.lock(command).recordGrant("canonical").allowed());return null;});
        }
        var expired=row();expired.put("valid_until",Instant.EPOCH);when(jdbc.queryForMap(contains("lock_market_authority"),eq("m"))).thenReturn(expired);
        tx.execute(s->{assertEquals("STALE_AUTHORITY_LEASE",authority.lock(command).refusal());return null;});
        var stale=row();stale.put("lease_version",2L);when(jdbc.queryForMap(contains("lock_market_authority"),eq("m"))).thenReturn(stale);
        tx.execute(s->{assertEquals("STALE_AUTHORITY_LEASE",authority.lock(command).refusal());return null;});
        verify(jdbc,never()).queryForObject(contains("record_atomic_authorization"),eq(Boolean.class),any(Object[].class));
    }
    @Test void sourceFailureRollsBackRatherThanCommitting() throws Exception {
        when(jdbc.queryForMap(contains("lock_market_authority"),eq("m"))).thenThrow(new IllegalStateException("unavailable"));
        assertThrows(IllegalStateException.class,()->tx.execute(s->authority.lock(command)));verify(connection).rollback();verify(connection,never()).commit();
    }
    @Test void grantMayExpireAfterInitialLockAndMustNotBeAccepted(){
        when(jdbc.queryForObject(contains("record_atomic_authorization"),eq(Boolean.class),any(Object[].class))).thenReturn(false);
        tx.execute(s->{assertFalse(authority.lock(command).recordGrant("canonical").allowed());return null;});
    }
    @Test void differentDatabaseOrEpochCannotBeTrusted(){
        var r=row();r.put("database_name","different");when(jdbc.queryForMap(contains("lock_market_authority"),eq("m"))).thenReturn(r);
        assertThrows(IllegalStateException.class,()->tx.execute(s->authority.lock(command)));
    }
    @Test void powerfulWorkerRoleIsRejectedAtInstallation(){
        when(jdbc.queryForMap(contains("can_forge_grant"))).thenReturn(Map.of("database_name","dev3_test","can_lock",true,"can_record",true,"can_change_source",false,"can_forge_grant",true));
        assertThrows(IllegalStateException.class,authority::verifyInstallation);
    }
}
