package com.live_jgv.matchstate.infrastructure.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.live_jgv.matchstate.MatchStateServiceApplication;
import com.live_jgv.matchstate.application.service.ProcessAndPublishMatchEvent;
import com.live_jgv.matchstate.application.port.out.ScoreUpdatePublisher;
import com.live_jgv.matchstate.infrastructure.jdbc.JdbcMatchStateStore;
import com.live_jgv.matchstate.infrastructure.rabbit.RabbitScoreUpdatePublisher;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.transaction.PlatformTransactionManager;
import org.flywaydb.core.Flyway;
import javax.sql.DataSource;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.boot.convert.ApplicationConversionService;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PassiveStartupTest {
    @Test
    void allSixteenDurableCombinationsAndDefaults() {
        assertEquals(new StartupPolicy(true, true, true), StartupPolicy.from(new MockEnvironment()));
        for (boolean catalog : new boolean[]{false, true})
            for (boolean recovery : new boolean[]{false, true})
                for (boolean publication : new boolean[]{false, true})
                    for (boolean listener : new boolean[]{false, true}) {
                        var env = new MockEnvironment()
                                .withProperty("match-state.catalog.bootstrap-enabled", "" + catalog)
                                .withProperty("match-state.recovery.enabled", "" + recovery)
                                .withProperty("match-state.publication.enabled", "" + publication)
                                .withProperty("spring.kafka.listener.auto-startup", "" + listener);
                        if ((recovery && !publication) || (listener && (!recovery || !publication))) {
                            new ApplicationContextRunner().withBean(StartupPolicy.class, () -> StartupPolicy.from(env))
                                    .run(context -> assertNotNull(context.getStartupFailure()));
                        } else assertEquals(new StartupPolicy(catalog, recovery, publication), StartupPolicy.from(env));
                    }
    }

    @Test
    void fullPassiveDurableContextHasNoStartupIOOrResourceDeclarers() {
        var datasource = mock(DataSource.class);
        new ApplicationContextRunner().withInitializer(new ConfigDataApplicationContextInitializer())
                .withInitializer(context -> context.getBeanFactory().setConversionService(ApplicationConversionService.getSharedInstance()))
                .withUserConfiguration(MatchStateServiceApplication.class)
                .withBean(DataSource.class, () -> datasource)
                .withPropertyValues("KAFKA_LISTENER_AUTO_STARTUP=false",
                        "MATCH_STATE_CATALOG_BOOTSTRAP_ENABLED=false", "MATCH_STATE_RECOVERY_ENABLED=false",
                        "MATCH_STATE_PUBLICATION_ENABLED=false", "spring.flyway.enabled=false",
                        "spring.rabbitmq.dynamic=false", "spring.sql.init.mode=never")
                .run(context -> {
                    assertNull(context.getStartupFailure(), () -> "" + context.getStartupFailure());
                    assertNotNull(context.getBean(JdbcMatchStateStore.class));
                    assertTrue(context.getBeansOfType(DurableRecoveryRunner.class).isEmpty());
                    assertTrue(context.getBeansOfType(KafkaInputSource.class).isEmpty());
                    assertTrue(context.getBeansOfType(Flyway.class).isEmpty());
                    assertTrue(context.getBeansOfType(RabbitAdmin.class).isEmpty());
                    var registry = context.getBean(KafkaListenerEndpointRegistry.class);
                    assertFalse(registry.getListenerContainer("matchEventsListener").isRunning());
                    var publisher = context.getBean(ScoreUpdatePublisher.class);
                    assertThrows(IllegalStateException.class, () -> publisher.publish(null));
                    assertThrows(UnsupportedOperationException.class, () -> publisher.publishStored(null));
                    verifyNoInteractions(datasource);
                });
    }

    @Test
    void catalogAndHistoryValidationAreIndependentAndDefaultOn() {
        for (boolean catalogOn : new boolean[]{false, true})
            for (boolean recoveryOn : new boolean[]{false, true}) {
                var jdbc = mock(JdbcTemplate.class);
                when(jdbc.queryForMap(anyString(), eq("match"))).thenReturn(Map.of("home_team_id", "home", "away_team_id", "away"));
                var manager = mock(PlatformTransactionManager.class);
                var catalog = new MatchCatalogProperties();
                catalog.setMatches(List.of(new MatchCatalogProperties.Definition("match", "home", "away")));
                var config = new DurableStorageConfiguration(new StartupPolicy(catalogOn, recoveryOn, true));
                config.jdbcMatchStateStore(jdbc, manager, new ObjectMapper(), catalog, "exchange", "score.{matchId}");
                if (catalogOn) verify(manager).getTransaction(any());
                else verifyNoInteractions(manager);
                if (recoveryOn) verify(jdbc).queryForList(anyString(), eq(String.class));
                else verify(jdbc, never()).queryForList(anyString(), eq(String.class));
            }
    }

    @Test
    void publisherOffThrowsWithoutTransportAndOnPreservesAdapter() {
        var rabbit = mock(RabbitTemplate.class);
        var config = new MessagingConfiguration();
        var off = config.scoreUpdatePublisher(rabbit, new ObjectMapper(), new StartupPolicy(false, false, false),
                "exchange", "score.{matchId}", Duration.ofSeconds(1));
        assertThrows(IllegalStateException.class, () -> off.publish(null));
        verifyNoInteractions(rabbit);
        assertInstanceOf(RabbitScoreUpdatePublisher.class, config.scoreUpdatePublisher(rabbit, new ObjectMapper(),
                new StartupPolicy(true, true, true), "exchange", "score.{matchId}", Duration.ofSeconds(1)));
        verify(rabbit).setMandatory(true);
    }

    @Test
    void recoveryDrainsBeforeKafkaAndListenerOffStillRecovers() throws Exception {
        for (boolean listener : new boolean[]{false, true}) {
            var store = mock(JdbcMatchStateStore.class);
            var processor = mock(ProcessAndPublishMatchEvent.class);
            var registry = mock(KafkaListenerEndpointRegistry.class);
            var context = mock(ConfigurableApplicationContext.class);
            when(context.isActive()).thenReturn(true);
            when(store.matchIds()).thenReturn(List.of("match"));
            new DurableRecoveryRunner(store, processor, registry, context, listener, Duration.ofMillis(1))
                    .run(new DefaultApplicationArguments());
            var order = inOrder(processor, registry);
            order.verify(processor).recoverPendingMatch("match");
            if (listener) order.verify(registry).start();
            else verifyNoInteractions(registry);
        }
    }

    @Test
    void failedRecoveryNeverStartsKafka() {
        var store = mock(JdbcMatchStateStore.class);
        var processor = mock(ProcessAndPublishMatchEvent.class);
        var registry = mock(KafkaListenerEndpointRegistry.class);
        var context = mock(ConfigurableApplicationContext.class);
        when(context.isActive()).thenReturn(true, false);
        when(store.matchIds()).thenReturn(List.of("match"));
        doThrow(new IllegalStateException("offline failure")).when(processor).recoverPendingMatch("match");
        var runner = new DurableRecoveryRunner(store, processor, registry, context, true, Duration.ofMillis(1));
        assertThrows(IllegalStateException.class, () -> runner.run(new DefaultApplicationArguments()));
        verifyNoInteractions(registry);
    }
}
