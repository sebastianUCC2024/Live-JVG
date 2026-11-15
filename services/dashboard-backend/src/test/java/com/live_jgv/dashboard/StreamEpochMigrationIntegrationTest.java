package com.live_jgv.dashboard;

import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import static org.junit.jupiter.api.Assertions.*;

/** Real PostgreSQL, opt-in only. Does not start a server or create a container. */
@org.junit.jupiter.api.Tag("external-integration")
@org.junit.jupiter.api.Tag("postgres-integration")
@org.junit.jupiter.api.extension.ExtendWith(com.live_jgv.test.DurableIntegrationSafety.class)
class StreamEpochMigrationIntegrationTest {
    @Test void upgradeFromV1PreservesRowsAndRepeatedMigrationKeepsEpoch() {
        var ds = new DriverManagerDataSource(System.getenv("DASHBOARD_INTEGRATION_DB_URL"),
                System.getenv("DASHBOARD_TEST_DB_USERNAME"),System.getenv("DASHBOARD_TEST_DB_PASSWORD"));
        String schema = "epoch_test_"+UUID.randomUUID().toString().replace("-","");
        var sql = new JdbcTemplate(ds);
        // Leave isolated schema for inspection; never clean/drop any database objects.
        Flyway.configure().dataSource(ds).schemas(schema).defaultSchema(schema).cleanDisabled(true)
                .target("1").load().migrate();
        sql.update("INSERT INTO "+schema+".matches(match_id,home_team_id,away_team_id,home_name,away_name) VALUES ('m','h','a','H','A')");
        sql.update("INSERT INTO "+schema+".inbox(match_id,event_id,payload) VALUES ('m','e','historical-bytes')");
        var flyway=Flyway.configure().dataSource(ds).schemas(schema).defaultSchema(schema).cleanDisabled(true).load();
        flyway.migrate();
        String epoch=sql.queryForObject("SELECT epoch::text FROM "+schema+".stream_epoch",String.class);
        assertNotNull(UUID.fromString(epoch));
        flyway.migrate();
        assertEquals(epoch,sql.queryForObject("SELECT epoch::text FROM "+schema+".stream_epoch",String.class));
        assertEquals("historical-bytes",sql.queryForObject("SELECT payload FROM "+schema+".inbox",String.class));
        String newEpoch=UUID.randomUUID().toString();
        assertEquals(1,sql.update("UPDATE "+schema+".stream_epoch SET epoch=?::uuid WHERE singleton AND epoch=?::uuid",newEpoch,epoch));
        assertEquals(0,sql.update("UPDATE "+schema+".stream_epoch SET epoch=?::uuid WHERE singleton AND epoch=?::uuid",UUID.randomUUID().toString(),epoch));
        assertEquals(newEpoch,sql.queryForObject("SELECT epoch::text FROM "+schema+".stream_epoch",String.class));
        assertEquals("historical-bytes",sql.queryForObject("SELECT payload FROM "+schema+".inbox",String.class));
    }
}
