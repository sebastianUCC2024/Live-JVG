package com.live_jgv.matchstate.infrastructure.jdbc;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

/** Upgrade a populated 7H schema in a new namespace, without touching previous evidence. */
@EnabledIfEnvironmentVariable(named = "MATCH_STATE_TEST_DB_URL", matches = ".+")
class QuarantineMigrationIntegrationTest {
    @Test
    void upgradePreservesV1LedgerAndPendingWireImageAndBackfillsAppliedIdentity() {
        var ds = new DriverManagerDataSource(System.getenv("MATCH_STATE_TEST_DB_URL"), System.getenv("MATCH_STATE_TEST_DB_USERNAME"), System.getenv("MATCH_STATE_TEST_DB_PASSWORD"));
        String schema = "upgrade_" + UUID.randomUUID().toString().replace("-", "");
        Flyway.configure().dataSource(ds).schemas(schema).defaultSchema(schema).target(MigrationVersion.fromVersion("1")).cleanDisabled(true).load().migrate();
        var sql = new JdbcTemplate(ds);
        String event = "{\"event_id\":\"old-goal\",\"match_id\":\"old-match\",\"event_type\":\"GOAL\",\"team_id\":\"home\",\"minute\":10,\"timestamp\":\"1970-01-01T00:00:00Z\",\"reference_event_id\":null}";
        String output = "{\"match_id\":\"old-match\",\"home_score\":1,\"away_score\":0,\"event_id\":\"old-goal\",\"event_type\":\"GOAL\",\"updated_at\":\"1970-01-01T00:00:00.123456789Z\",\"var_status\":\"CLEAR\",\"reference_event_id\":null}";
        sql.update("INSERT INTO " + schema + ".match_catalog(match_id,home_team_id,away_team_id,revision) VALUES ('old-match','home','away',1)");
        sql.update("INSERT INTO " + schema + ".accepted_event(match_id,event_id,application_seq,event_payload) VALUES ('old-match','old-goal',1,?)", event);
        sql.update("INSERT INTO " + schema + ".score_outbox(match_id,event_id,application_seq,payload,exchange_name,routing_key) VALUES ('old-match','old-goal',1,?,'old-exchange','score.old-match')", output);
        Flyway.configure().dataSource(ds).schemas(schema).defaultSchema(schema).cleanDisabled(true).load().migrate();
        assertEquals(1L, sql.queryForObject("SELECT revision FROM " + schema + ".match_catalog", Long.class));
        assertEquals(event, sql.queryForObject("SELECT event_payload FROM " + schema + ".accepted_event", String.class));
        assertEquals(event, sql.queryForObject("SELECT canonical_payload FROM " + schema + ".event_identity", String.class));
        assertEquals("APPLIED", sql.queryForObject("SELECT status FROM " + schema + ".event_identity", String.class));
        assertEquals(output, sql.queryForObject("SELECT payload FROM " + schema + ".score_outbox WHERE NOT published", String.class));
        assertEquals(0, sql.queryForObject("SELECT count(*) FROM " + schema + ".quarantine_case", Integer.class));
    }
}
