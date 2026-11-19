package com.live_jgv.matchstate.infrastructure.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.live_jgv.matchstate.application.service.ProcessAndPublishMatchEvent;
import com.live_jgv.matchstate.domain.model.MatchState;
import com.live_jgv.matchstate.infrastructure.jdbc.JdbcMatchStateStore;
import com.live_jgv.matchstate.infrastructure.jdbc.DurableEventIngress;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.Duration;
import org.apache.kafka.clients.admin.AdminClient;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.ObjectProvider;

@Configuration(proxyBeanMethods = false)
@Profile("!memory")
public class DurableStorageConfiguration {
    private final StartupPolicy startup;

    public DurableStorageConfiguration(StartupPolicy startup) {
        this.startup = startup;
    }

    public JdbcMatchStateStore jdbcMatchStateStore(JdbcTemplate jdbc,PlatformTransactionManager manager,ObjectMapper mapper,
            MatchCatalogProperties catalog,String exchange,String routingKey) {
        return jdbcMatchStateStore(jdbc,manager,mapper,catalog,exchange,routingKey,false);
    }
    @Bean
    @DependsOnDatabaseInitialization
    public JdbcMatchStateStore jdbcMatchStateStore(JdbcTemplate jdbc, PlatformTransactionManager manager,
            ObjectMapper mapper, MatchCatalogProperties catalog,
            @Value("${match-state.rabbit.exchange:live_updates}") String exchange,
            @Value("${match-state.rabbit.routing-key:score.{matchId}}") String routingKey, @Value("${match-state.alerts.enabled:false}") boolean alerts) {
        var store = new JdbcMatchStateStore(jdbc, new TransactionTemplate(manager), mapper, exchange, routingKey, alerts);
        if (startup.catalog()) {
            catalog.getMatches().stream().filter(d -> hasText(d.matchId()) || hasText(d.homeTeamId()) || hasText(d.awayTeamId()))
                    .forEach(d -> store.register(MatchState.start(d.matchId(), d.homeTeamId(), d.awayTeamId())));
        }
        if (startup.recovery()) {
            for (var match : store.matchIds()) store.load(match); // Falla si el historial está corrupto; nunca lo reinicia.
        }
        return store;
    }

    private static boolean hasText(String value) { return value != null && !value.isBlank(); }

    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(name = "spring.kafka.listener.auto-startup", havingValue = "true", matchIfMissing = true)
    public KafkaInputSource kafkaInputSource(KafkaProperties properties, ObjectProvider<SslBundles> bundles,
            @Value("${match-state.kafka.source-id:local/1}") String source) {
        return new KafkaInputSource(AdminClient.create(properties.buildAdminProperties(bundles.getIfAvailable())), source);
    }

    @Bean
    public DurableEventIngress durableEventIngress(JdbcTemplate jdbc, PlatformTransactionManager manager,
            ObjectMapper mapper, JdbcMatchStateStore store, ProcessAndPublishMatchEvent processor) {
        return new DurableEventIngress(jdbc, new TransactionTemplate(manager), mapper, store, processor);
    }

    @Bean
    @ConditionalOnProperty(name = "match-state.recovery.enabled", havingValue = "true", matchIfMissing = true)
    public DurableRecoveryRunner durableRecoveryRunner(JdbcMatchStateStore store, ProcessAndPublishMatchEvent processor,
            KafkaListenerEndpointRegistry registry, ConfigurableApplicationContext context,
            @Value("${spring.kafka.listener.auto-startup:true}") boolean startListener,
            @Value("${match-state.recovery.retry-interval:5s}") Duration retryInterval) {
        return new DurableRecoveryRunner(store, processor, registry, context, startListener, retryInterval);
    }
}
