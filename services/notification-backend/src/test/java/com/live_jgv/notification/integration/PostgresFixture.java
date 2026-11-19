package com.live_jgv.notification.integration;

import com.live_jgv.notification.application.*;
import com.live_jgv.notification.domain.*;
import com.live_jgv.notification.infrastructure.jdbc.JdbcNotificationStore;
import com.live_jgv.notification.infrastructure.json.ScoreUpdateCodec;
import java.time.*;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import static com.live_jgv.notification.integration.IntegrationSettings.refused;

/** Construct only AFTER IntegrationGate.authorize. Never cleans, truncates or drops. */
public final class PostgresFixture {
    public static final Set<String> ACTIONS=Set.of("database-read","database-write","migrate");
    public final IntegrationGate.AuthorizedRun authorization;
    public final IntegrationSettings settings;
    public final DriverManagerDataSource source;
    public final JdbcTemplate jdbc;
    public final ScoreUpdateCodec codec=new ScoreUpdateCodec();
    public final JdbcNotificationStore store;
    public PostgresFixture(IntegrationGate.AuthorizedRun authorization,boolean migrate) throws Exception {
        authorization.require(migrate ? ACTIONS : Set.of("database-read"));
        this.authorization=authorization; var settings=authorization.settings();
        this.settings=settings;
        source=new DriverManagerDataSource(settings.databaseUrl(),settings.databaseUser(),settings.databasePassword());
        source.setConnectionProperties(properties());
        jdbc=new JdbcTemplate(source);
        try (var connection=source.getConnection()) {
            if (connection.getMetaData().getDatabaseMajorVersion()!=17) throw refused("PostgreSQL 17 required");
        }
        if (!settings.databaseName().equals(jdbc.queryForObject("SELECT current_database()",String.class))
                || !settings.databaseUser().equals(jdbc.queryForObject("SELECT current_user",String.class))) throw refused("Database identity mismatch");
        // Provisioned in the exclusive database by infrastructure, NOT created by tests.
        var owners=jdbc.queryForList("SELECT run_id FROM public.notification_test_owner",String.class);
        if (!owners.equals(List.of(settings.run()))) throw refused("Exclusive database ownership marker missing/mismatched");
        var flyway=Flyway.configure().dataSource(source).schemas("notification").defaultSchema("notification")
                .locations("classpath:db/migration").cleanDisabled(true).baselineOnMigrate(false).load();
        if (migrate) {
            boolean schema=Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM pg_namespace WHERE nspname='notification')",Boolean.class));
            boolean history=Boolean.TRUE.equals(jdbc.queryForObject("SELECT to_regclass('notification.flyway_schema_history') IS NOT NULL",Boolean.class));
            if (schema && !history) throw refused("Unmanaged notification schema refused");
            flyway.migrate();
        }
        flyway.validate();
        store=newStore();
    }
    private Properties properties() {
        var properties=new Properties(); properties.setProperty("ApplicationName","notification-it-"+settings.run());
        properties.setProperty("connectTimeout","5"); properties.setProperty("socketTimeout","15"); return properties;
    }
    public JdbcNotificationStore newStore() { return new JdbcNotificationStore(jdbc,new JdbcTransactionManager(source),codec); }
    public ProcessScoreUpdateService service() { return new ProcessScoreUpdateService(store,new NotificationPolicy(),Clock.systemUTC()); }
    public ScoreUpdate update(String match,String event,ScoreUpdate.EventType type) {
        return new ScoreUpdate(match,event,type,type==ScoreUpdate.EventType.GOAL_ANNULLED ? 0 : 1,0,
                type==ScoreUpdate.EventType.VAR_CHECK ? ScoreUpdate.VarStatus.IN_PROGRESS : ScoreUpdate.VarStatus.CLEAR,
                Instant.parse("2026-11-16T12:00:00.123456789Z"),type==ScoreUpdate.EventType.GOAL_ANNULLED ? "goal-original" : null);
    }
    public int inbox(String match) { return jdbc.queryForObject("SELECT count(*) FROM notification.inbox WHERE match_id=?",Integer.class,match); }
    public int alerts(String match) { return jdbc.queryForObject("SELECT count(*) FROM notification.alerts WHERE match_id=?",Integer.class,match); }
}
