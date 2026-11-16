package com.live_jgv.worker.integration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.flywaydb.core.Flyway;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.*;
import static org.junit.jupiter.api.Assertions.*;

import com.live_jgv.worker.domain.*;
import com.live_jgv.worker.application.*;
import com.live_jgv.worker.infrastructure.JdbcWorkerStore;
import java.util.concurrent.atomic.AtomicReference;

@EnabledIfEnvironmentVariable(named="DEV3_RUN_POSTGRES_TESTS",matches="true")
class PreparedPostgresIntegrationTest {
    @Test void blockedOperationsAndSuspensionFenceSurviveNewAdapterAndDuplicate() {
        var ds=IsolatedPostgres.approvedFreshDatabase();var jdbc=new JdbcTemplate(ds);
        Flyway.configure().dataSource(ds).schemas("betting_worker").defaultSchema("betting_worker").cleanDisabled(true).load().migrate();
        var tx=new TransactionTemplate(new DataSourceTransactionManager(ds));var now=Instant.now();
        var observed=new AtomicReference<>(new MarketEvidence("it-match","test-authority","test-epoch",2,MarketEvidence.Status.SUSPENDED,now,now.plusSeconds(3)));
        SuspensionEvidencePort source=match->observed.get();var policy=new AcceptancePolicy(false,Duration.ofSeconds(5),Duration.ofMinutes(5));
        var store=new JdbcWorkerStore(jdbc,tx,source,policy,Clock.fixed(now,ZoneOffset.UTC));
        var raw=new TransportMessage("it/1","broker-id","it-exchange","it-key",new byte[]{1},"{}");
        var c=new SimulatedCommand("c1","o1","it-match","sim-user",now);
        assertEquals(WorkerStore.Outcome.PROCESSED,store.handle(raw,new DecodedCommand(c,"c1-image",null)));
        assertEquals(WorkerStore.Outcome.DUPLICATE,store.handle(raw,new DecodedCommand(c,"c1-image",null)));
        observed.set(new MarketEvidence("it-match","test-authority","test-epoch",1,MarketEvidence.Status.OPEN,now,now.plusSeconds(3)));
        var recovered=new JdbcWorkerStore(jdbc,tx,source,policy,Clock.fixed(now,ZoneOffset.UTC));
        var next=new SimulatedCommand("c2","o2","it-match","sim-user",now);
        recovered.handle(raw,new DecodedCommand(next,"c2-image",null));
        assertEquals(Operation.State.BLOCKED,recovered.find("o1").orElseThrow().state());
        assertEquals("STALE_MARKET_REVISION",recovered.find("o2").orElseThrow().reason());
        assertEquals(2,jdbc.queryForObject("SELECT count(*) FROM betting_worker.operation",Integer.class));
    }
}
