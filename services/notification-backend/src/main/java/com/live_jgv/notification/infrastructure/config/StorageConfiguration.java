package com.live_jgv.notification.infrastructure.config;

import com.live_jgv.notification.application.*;
import com.live_jgv.notification.domain.*;
import com.live_jgv.notification.infrastructure.jdbc.JdbcNotificationStore;
import com.live_jgv.notification.infrastructure.json.ScoreUpdateCodec;
import com.zaxxer.hikari.HikariDataSource;
import java.time.Clock;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration(proxyBeanMethods = false)
public class StorageConfiguration {
    @Bean ScoreUpdateCodec scoreUpdateCodec() { return new ScoreUpdateCodec(); }
    @Bean ProcessScoreUpdateService processor(NotificationStore store) {
        return new ProcessScoreUpdateService(store, new NotificationPolicy(), Clock.systemUTC());
    }
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(name="notification.storage.enabled", havingValue="true")
    static class EnabledStorage {
        @Bean DataSource notificationDataSource(@Value("${notification.storage.url:}") String url,
                @Value("${notification.storage.username:}") String user, @Value("${notification.storage.password:}") String password) {
            if (!url.startsWith("jdbc:postgresql:") || user.isBlank() || password.isBlank())
                throw new IllegalArgumentException("Authorized PostgreSQL URL, username and password are required");
            var source = new HikariDataSource();
            source.setJdbcUrl(url); source.setUsername(user); source.setPassword(password);
            source.setMaximumPoolSize(4); source.setConnectionTimeout(5000); source.setPoolName("notification");
            return source;
        }
        @Bean PlatformTransactionManager notificationTransactionManager(DataSource source) { return new JdbcTransactionManager(source); }
        @Bean NotificationStore notificationStore(DataSource source, PlatformTransactionManager manager, ScoreUpdateCodec codec) {
            var jdbc=new JdbcTemplate(source);
            // Comprueba el esquema existente sin ejecutar DDL.
            // El esquema debe prepararse antes de habilitar almacenamiento y consumo.
            if (!Integer.valueOf(1).equals(jdbc.queryForObject("SELECT COUNT(*) FROM notification.cursor_state WHERE singleton=TRUE",Integer.class)))
                throw new IllegalStateException("Notification cursor state is missing");
            jdbc.queryForList("SELECT a.sequence,i.payload FROM notification.alerts a JOIN notification.inbox i ON (a.source,a.match_id,a.event_id)=(i.source,i.match_id,i.event_id) WHERE FALSE");
            return new JdbcNotificationStore(jdbc, manager, codec);
        }
    }
    @Bean
    @ConditionalOnProperty(name="notification.storage.enabled", havingValue="false", matchIfMissing=true)
    NotificationStore disabledNotificationStore() {
        return new NotificationStore() {
            public Result accept(ScoreUpdate update, Optional<LiveAlert> alert) { throw new StorageUnavailableException(); }
            public AlertPage query(String matchId, String after, int limit) { throw new StorageUnavailableException(); }
        };
    }
}
