package com.live_jgv.betting.infrastructure;

import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.*;
import java.util.Map;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

class JdbcOpenLeaseIssuerTest {
    JdbcTemplate jdbc;PlatformTransactionManager manager;JdbcOpenLeaseIssuer issuer;
    @BeforeEach void setup(){
        jdbc=mock(JdbcTemplate.class);manager=mock(PlatformTransactionManager.class);when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        issuer=new JdbcOpenLeaseIssuer(jdbc,new TransactionTemplate(manager));
        when(jdbc.queryForMap(contains("FOR UPDATE"),eq("m"))).thenReturn(Map.of("status","OPEN","version",3L,"inconsistent",false));
        when(jdbc.queryForObject(anyString(),eq(Long.class),eq("m"))).thenReturn(0L);when(jdbc.update(anyString(),any(Object[].class))).thenReturn(1);
    }
    private JdbcOpenLeaseIssuer.Request request(){return new JdbcOpenLeaseIssuer.Request("m",3,1000,"approved-checkpoint","operator","approved test");}
    @Test void authorityRowIsLockedBeforeIssuingShortLease(){
        assertNotNull(issuer.issue(request()));var order=inOrder(jdbc,manager);
        order.verify(jdbc).queryForMap(contains("FOR UPDATE"),eq("m"));order.verify(jdbc).queryForObject(contains("pending_review"),eq(Long.class),eq("m"));
        order.verify(jdbc).update(contains("authorization_lease"),any(Object[].class));order.verify(manager).commit(any());
    }
    @Test void suspendedUnknownOrPendingReviewCannotIssueLease(){
        for(String state:new String[]{"SUSPENDED","UNKNOWN"}){
            when(jdbc.queryForMap(contains("FOR UPDATE"),eq("m"))).thenReturn(Map.of("status",state,"version",3L,"inconsistent",false));
            assertThrows(IllegalStateException.class,()->issuer.issue(request()));
        }
        when(jdbc.queryForMap(contains("FOR UPDATE"),eq("m"))).thenReturn(Map.of("status","OPEN","version",3L,"inconsistent",false));
        when(jdbc.queryForObject(anyString(),eq(Long.class),eq("m"))).thenReturn(1L);
        assertThrows(IllegalStateException.class,()->issuer.issue(request()));verify(jdbc,never()).update(anyString(),any(Object[].class));
    }
    @Test void staleRevisionAndUnboundedLeaseAreRejected(){
        when(jdbc.queryForMap(contains("FOR UPDATE"),eq("m"))).thenReturn(Map.of("status","OPEN","version",4L,"inconsistent",false));
        assertThrows(IllegalStateException.class,()->issuer.issue(request()));
        assertThrows(IllegalArgumentException.class,()->new JdbcOpenLeaseIssuer.Request("m",3,5001,"checkpoint","a","r"));
    }
    @Test void persistenceFailureRollsBackLease(){
        when(jdbc.update(anyString(),any(Object[].class))).thenReturn(0);
        assertThrows(IllegalStateException.class,()->issuer.issue(request()));verify(manager).rollback(any());verify(manager,never()).commit(any());
    }
}
