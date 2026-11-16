package com.live_jgv.worker.integration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

/** Pure preflight validation: no database connection or migration. */
class IsolatedPostgresSafetyTest {
    private final String approval="I_APPROVE_ISOLATED_MIGRATIONS";
    private String url(int port){return "jdbc:postgresql://127.0.0.1:"+port+"/dev3_it_betting_worker_safety";}
    @ParameterizedTest @ValueSource(ints={5432,19534,55432,55433,55434})
    void knownSharedAndHistoricalPortsCannotBeUsed(int port){
        assertThrows(IllegalArgumentException.class,()->IsolatedPostgres.configuredDataSource(approval,url(port),"dev3_it_owner","test-only"));
    }
    @Test void missingApprovalCannotEvenConstructDatasource(){
        assertThrows(IllegalStateException.class,()->IsolatedPostgres.configuredDataSource(null,url(19634),"dev3_it_owner","test-only"));
    }
    @Test void rejectsRemoteEndpointJdbcOptionsAndNonDedicatedCredentials(){
        for(String value:new String[]{url(19634).replace("127.0.0.1","db.example.invalid"),url(19634)+"?currentSchema=public",url(19634)+"#other",url(19634).replace("dev3_it_","shared_")})
            assertThrows(IllegalArgumentException.class,()->IsolatedPostgres.configuredDataSource(approval,value,"dev3_it_owner","test-only"));
        assertThrows(IllegalArgumentException.class,()->IsolatedPostgres.configuredDataSource(approval,url(19634),"postgres","test-only"));
        assertThrows(IllegalArgumentException.class,()->IsolatedPostgres.configuredDataSource(approval,url(19634),"dev3_it_owner",""));
    }
    @Test void validShapeConstructsDatasourceWithoutConnecting(){
        var ds=IsolatedPostgres.configuredDataSource(approval,url(19634),"dev3_it_owner","test-only");
        assertEquals(url(19634),ds.getUrl());assertEquals("dev3_it_owner",ds.getUsername());
        // This is syntactic validation, not proof of isolation or permission to use the endpoint.
    }
}
