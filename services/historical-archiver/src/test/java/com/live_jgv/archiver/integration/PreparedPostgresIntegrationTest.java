package com.live_jgv.archiver.integration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.flywaydb.core.Flyway;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.*;
import static org.junit.jupiter.api.Assertions.*;

import com.live_jgv.archiver.domain.*;
import com.live_jgv.archiver.infrastructure.*;

@EnabledIfEnvironmentVariable(named="DEV3_RUN_POSTGRES_TESTS",matches="true")
class PreparedPostgresIntegrationTest {
    @Test void v1UpgradePreservesEvidenceAndV2SupportsMatchScopedIdentityAndRecovery() {
        var ds=IsolatedPostgres.approvedFreshDatabase();var jdbc=new JdbcTemplate(ds);
        Flyway.configure().dataSource(ds).schemas("archiver").defaultSchema("archiver").target("1").cleanDisabled(true).load().migrate();
        jdbc.update("INSERT INTO archiver.archived_event(event_id,match_id,event_type,team_id,minute,event_timestamp,original_payload,canonical_payload) VALUES ('legacy','old-match','GOAL','home',10,?,?,'legacy-image')",Instant.EPOCH.toString(),new byte[]{1});
        jdbc.update("INSERT INTO archiver.input_receipt(source_id,topic,partition_id,record_offset,broker_timestamp,original_headers,outcome,event_id) VALUES ('old','events',0,0,0,'[]','STORED','legacy')");
        Flyway.configure().dataSource(ds).schemas("archiver").defaultSchema("archiver").cleanDisabled(true).load().migrate();
        assertEquals("old-match",jdbc.queryForObject("SELECT match_id FROM archiver.input_receipt WHERE event_id='legacy'",String.class));
        var tx=new TransactionTemplate(new DataSourceTransactionManager(ds));var store=new JdbcArchiveStore(jdbc,tx);
        var event=new SportEvent("legacy","new-match",SportEvent.EventType.GOAL,"home",10,Instant.EPOCH,null);
        var raw=new InputRecord("it/1","events",0,1,0,new byte[]{1},new byte[]{2},"[]");
        assertEquals(ArchiveOutcome.STORED,store.save(raw,DecodedInput.valid(event,"new-image")));
        var duplicate=new InputRecord("it/1","events",0,2,0,new byte[]{1},new byte[]{2},"[]");
        assertEquals(ArchiveOutcome.DUPLICATE,store.save(duplicate,DecodedInput.valid(event,"new-image")));
        var conflict=new InputRecord("it/1","events",0,3,0,new byte[]{1},new byte[]{3},"[]");
        assertEquals(ArchiveOutcome.IDENTITY_CONFLICT,store.save(conflict,DecodedInput.valid(event,"changed")));
        var recovered=new JdbcArchiveStore(jdbc,tx);
        assertEquals(1,recovered.find("new-match",0,10).totalElements());
        assertEquals(2,jdbc.queryForObject("SELECT count(*) FROM archiver.archived_event WHERE event_id='legacy'",Integer.class));
    }
}
