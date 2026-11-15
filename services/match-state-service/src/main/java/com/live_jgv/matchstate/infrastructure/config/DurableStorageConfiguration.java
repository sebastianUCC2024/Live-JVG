package com.live_jgv.matchstate.infrastructure.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.live_jgv.matchstate.application.service.ProcessAndPublishMatchEvent;
import com.live_jgv.matchstate.domain.model.MatchState;
import com.live_jgv.matchstate.infrastructure.jdbc.JdbcMatchStateStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.Duration;

@Configuration(proxyBeanMethods = false)
@Profile("!memory")
public class DurableStorageConfiguration {
    @Bean
    @DependsOnDatabaseInitialization
    public JdbcMatchStateStore jdbcMatchStateStore(JdbcTemplate jdbc, PlatformTransactionManager manager,
            ObjectMapper mapper, MatchCatalogProperties catalog,
            @Value("${match-state.rabbit.exchange:live_updates}") String exchange,
            @Value("${match-state.rabbit.routing-key:score.{matchId}}") String routingKey) {
        var store = new JdbcMatchStateStore(jdbc, new TransactionTemplate(manager), mapper, exchange, routingKey);
        catalog.getMatches().stream().filter(d -> hasText(d.matchId()) || hasText(d.homeTeamId()) || hasText(d.awayTeamId()))
                .forEach(d -> store.register(MatchState.start(d.matchId(), d.homeTeamId(), d.awayTeamId())));
        for (var match : store.matchIds()) store.load(match); // fail startup on corrupt history, never reset it
        return store;
    }

    private static boolean hasText(String value) { return value != null && !value.isBlank(); }

    @Bean
    public DurableRecoveryRunner durableRecoveryRunner(JdbcMatchStateStore store, ProcessAndPublishMatchEvent processor,
            KafkaListenerEndpointRegistry registry, ConfigurableApplicationContext context,
            @Value("${spring.kafka.listener.auto-startup:true}") boolean startListener,
            @Value("${match-state.recovery.retry-interval:5s}") Duration retryInterval) {
        return new DurableRecoveryRunner(store, processor, registry, context, startListener, retryInterval);
    }
}
