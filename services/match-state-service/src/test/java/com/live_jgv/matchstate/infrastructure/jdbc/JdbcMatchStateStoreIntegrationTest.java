package com.live_jgv.matchstate.infrastructure.jdbc;

import com.live_jgv.matchstate.application.service.MatchStateService;
import com.live_jgv.matchstate.domain.model.*;
import com.live_jgv.matchstate.infrastructure.config.MessagingConfiguration;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** Opt-in real PostgreSQL tests. Use a dedicated database, never a shared deployment. */
@org.junit.jupiter.api.Tag("external-integration")
@org.junit.jupiter.api.Tag("postgres-integration")
@org.junit.jupiter.api.extension.ExtendWith(com.live_jgv.test.DurableIntegrationSafety.class)
class JdbcMatchStateStoreIntegrationTest {
    @Test void overlappingV1ReviewsRecoverWithoutChangingHistoricalRulesOrScore() {
        var service = new MatchStateService(store, Clock.fixed(TIME, ZoneOffset.UTC));
        service.process(event("g", EventType.GOAL, "home", null));
        service.process(event("v1", EventType.VAR_CHECK, null, "g"));
        service.process(event("v2", EventType.VAR_CHECK, null, null));
        var restoredOpen = newStore().load(match).orElseThrow();
        assertEquals(new Score(1, 0), restoredOpen.state().score());
        assertEquals(VarStatus.IN_PROGRESS, restoredOpen.state().varStatus());
        var closure = event("c", EventType.MATCH_CONTINUES, null, "v1");
        new MatchStateService(newStore()).process(closure);
        new MatchStateService(newStore()).process(closure);
        var restoredClosed = newStore().load(match).orElseThrow();
        assertEquals(4, restoredClosed.revision());
        assertEquals(new Score(1, 0), restoredClosed.state().score());
        assertEquals(VarStatus.CLEAR, restoredClosed.state().varStatus());
        assertEquals(4, count("accepted_event"));
        assertEquals(4, count("score_outbox"));
    }
    @Test void twoPostgresPublishersExcludeSameMatchWhileAnotherMatchProgresses() throws Exception {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        var sends = new java.util.concurrent.atomic.AtomicInteger();
        String other=match+"-other";
        store.register(MatchState.start(other,"home","away"));
        new MatchStateService(store).process(new MatchEvent("g",match,EventType.GOAL,"home",1,TIME,null));
        new MatchStateService(store).process(new MatchEvent("g",other,EventType.GOAL,"home",1,TIME,null));
        com.live_jgv.matchstate.application.port.out.ScoreUpdatePublisher publisher=update->{
            if(update.matchId().equals(match)) {
                entered.countDown();
                try { assertTrue(release.await(5,TimeUnit.SECONDS)); }
                catch(InterruptedException e){throw new AssertionError(e);}
            }
            sends.incrementAndGet();
        };
        var lane1=new com.live_jgv.matchstate.application.service.ProcessAndPublishMatchEvent(new MatchStateService(store),store,publisher);
        var store2=newStore();
        var lane2=new com.live_jgv.matchstate.application.service.ProcessAndPublishMatchEvent(new MatchStateService(store2),store2,publisher);
        var pool=Executors.newFixedThreadPool(2);
        try {
            var first=pool.submit(()->lane1.recoverPendingMatch(match));
            assertTrue(entered.await(5,TimeUnit.SECONDS));
            assertThrows(IllegalStateException.class,()->lane2.recoverPendingMatch(match));
            pool.submit(()->lane1.recoverPendingMatch(other)).get(5,TimeUnit.SECONDS);
            release.countDown();first.get(5,TimeUnit.SECONDS);
            lane2.recoverPendingMatch(match);
            assertEquals(2,sends.get());assertTrue(store.pendingForMatch(match).isEmpty());
        } finally {release.countDown();pool.shutdownNow();}
    }
    private static JdbcTemplate jdbc;
    private static TransactionTemplate transactions;
    private JdbcMatchStateStore store;
    private String match;
    private static final Instant TIME = Instant.parse("2026-11-14T21:00:00.123456789Z");

    @BeforeAll
    static void migrate() {
        var ds = new DriverManagerDataSource(System.getenv("MATCH_STATE_TEST_DB_URL"),
                System.getenv("MATCH_STATE_TEST_DB_USERNAME"), System.getenv("MATCH_STATE_TEST_DB_PASSWORD"));
        Flyway.configure().dataSource(ds).schemas("match_state").defaultSchema("match_state").cleanDisabled(true).load().migrate();
        jdbc = new JdbcTemplate(ds);
        transactions = new TransactionTemplate(new DataSourceTransactionManager(ds));
    }

    private JdbcMatchStateStore newStore() {
        return new JdbcMatchStateStore(jdbc, transactions, new MessagingConfiguration().messagingObjectMapper(),
                "integration-updates", "score.{matchId}");
    }

    @BeforeEach
    void setup() {
        store = newStore(); match = "jdbc-" + UUID.randomUUID();
        store.register(MatchState.start(match, "home", "away"));
    }

    private MatchEvent event(String id, EventType type, String team, String ref) {
        return new MatchEvent(id, match, type, team, 10, TIME, ref);
    }

    private long count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM match_state." + table + " WHERE match_id=?", Long.class, match);
    }

    @Test
    void restoresGoalsVarAnnulmentAndContinuationInAcceptanceOrderWithoutRecreatingPublishedOutputs() {
        var service = new MatchStateService(store, Clock.fixed(TIME, ZoneOffset.UTC));
        var home = event("home-goal", EventType.GOAL, "home", null);
        var away = event("away-goal", EventType.GOAL, "away", null);
        service.process(home); service.process(away);
        assertEquals(new Score(1, 1), newStore().findById(match).orElseThrow().score());
        service.process(event("review", EventType.VAR_CHECK, null, away.eventId()));
        assertEquals(VarStatus.IN_PROGRESS, newStore().findById(match).orElseThrow().varStatus());
        service.process(event("annulment", EventType.GOAL_ANNULLED, null, away.eventId()));
        service.process(event("continue", EventType.MATCH_CONTINUES, null, null));
        for (var update : store.pendingForMatch(match)) store.markPublished(update);
        var restarted = newStore(); var loaded = restarted.load(match).orElseThrow();
        assertEquals(5, loaded.revision());
        assertEquals(new Score(1, 0), loaded.state().score());
        assertEquals(VarStatus.CLEAR, loaded.state().varStatus());
        assertEquals(5, loaded.state().processedEvents().size());
        assertTrue(restarted.pendingForMatch(match).isEmpty());
        assertEquals(5, count("score_outbox"));
    }

    @Test
    void deduplicatesBeforeAndAfterRestartAndPreservesNanoseconds() {
        var goal = event("same-goal", EventType.GOAL, "home", null);
        var service = new MatchStateService(store, Clock.fixed(TIME, ZoneOffset.UTC));
        service.process(goal); service.process(goal);
        var restarted = newStore(); new MatchStateService(restarted).process(goal);
        assertEquals(1, count("accepted_event")); assertEquals(1, count("score_outbox"));
        assertEquals(TIME, restarted.findById(match).orElseThrow().processedEvents().get(goal.eventId()).timestamp());
        assertEquals(TIME, restarted.pendingForMatch(match).get(0).updatedAt());
        assertEquals(new Score(1, 0), restarted.findById(match).orElseThrow().score());
        assertThrows(InvalidMatchEventException.class, () -> new MatchStateService(restarted)
                .process(event(goal.eventId(), EventType.GOAL, "away", null)));
    }

    @Test
    void deduplicatesAnnulmentAfterRestartAndRejectsAnotherAnnulment() {
        var service = new MatchStateService(store);
        service.process(event("goal", EventType.GOAL, "home", null));
        var annul = event("annul", EventType.GOAL_ANNULLED, null, "goal"); service.process(annul);
        new MatchStateService(newStore()).process(annul);
        assertEquals(2, count("accepted_event"));
        assertEquals(new Score(0, 0), newStore().findById(match).orElseThrow().score());
        assertThrows(InvalidMatchEventException.class, () -> service.process(event("other-annul", EventType.GOAL_ANNULLED, null, "goal")));
    }

    @Test
    void rollsBackRevisionAndLedgerWhenPostgresRejectsOutboxInsertThenAllowsRetry() {
        String trigger = "fault_" + UUID.randomUUID().toString().replace("-", "");
        jdbc.execute("""
                CREATE OR REPLACE FUNCTION match_state.phase7h_reject_outbox() RETURNS trigger AS $$
                BEGIN IF NEW.event_id = TG_ARGV[0] THEN RAISE EXCEPTION 'phase7h injected outbox failure'; END IF;
                RETURN NEW; END; $$ LANGUAGE plpgsql
                """);
        String eventId = UUID.randomUUID().toString();
        jdbc.execute("CREATE TRIGGER " + trigger + " BEFORE INSERT ON match_state.score_outbox FOR EACH ROW EXECUTE FUNCTION match_state.phase7h_reject_outbox('" + eventId + "')");
        var goal = event(eventId, EventType.GOAL, "home", null);
        try {
            assertThrows(org.springframework.dao.DataAccessException.class, () -> new MatchStateService(store).process(goal));
            assertEquals(0, store.load(match).orElseThrow().revision());
            assertEquals(0, count("accepted_event")); assertEquals(0, count("score_outbox"));
        } finally {
            // Remove only this test's own fault hook, not application data or prior resources.
            jdbc.execute("DROP TRIGGER " + trigger + " ON match_state.score_outbox");
        }
        new MatchStateService(newStore()).process(goal);
        assertEquals(1, count("accepted_event")); assertEquals(1, count("score_outbox"));
    }

    @Test
    void concurrentWritersCannotCommitTheSameRevision() throws Exception {
        var a = store.load(match).orElseThrow(); var b = newStore().load(match).orElseThrow();
        var home = event("a", EventType.GOAL, "home", null); var away = event("b", EventType.GOAL, "away", null);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var gate = new CountDownLatch(1);
            var one = pool.submit(() -> { gate.await(); return store.compareAndSet(a, a.state().apply(home), new ScoreUpdate(match, 1, 0, "a", EventType.GOAL, TIME, VarStatus.CLEAR, null)); });
            var two = pool.submit(() -> { gate.await(); return newStore().compareAndSet(b, b.state().apply(away), new ScoreUpdate(match, 0, 1, "b", EventType.GOAL, TIME, VarStatus.CLEAR, null)); });
            gate.countDown();
            assertNotEquals(one.get(10, TimeUnit.SECONDS), two.get(10, TimeUnit.SECONDS));
            assertEquals(1, count("accepted_event")); assertEquals(1, count("score_outbox"));
        } finally { pool.shutdownNow(); }
    }

    @Test
    void refusesConflictingCatalogAndCorruptHistoryInsteadOfResetting() {
        assertThrows(IllegalStateException.class, () -> newStore().register(MatchState.start(match, "away", "home")));
        jdbc.update("UPDATE match_state.match_catalog SET revision=1 WHERE match_id=?", match);
        try { assertThrows(IllegalStateException.class, () -> newStore().load(match)); }
        finally { jdbc.update("UPDATE match_state.match_catalog SET revision=0 WHERE match_id=?", match); }
        assertEquals(new Score(0, 0), store.findById(match).orElseThrow().score());
    }

    @Test
    void pendingWireImageAndDestinationSurviveReloadAndConfigurationChanges() {
        new MatchStateService(store, Clock.fixed(TIME, ZoneOffset.UTC)).process(event("pending", EventType.GOAL, "home", null));
        var first = store.storedPublication(store.pendingForMatch(match).get(0)).orElseThrow();
        var changedConfig = new JdbcMatchStateStore(jdbc, transactions, new MessagingConfiguration().messagingObjectMapper(), "different", "other.{matchId}");
        assertEquals(first, changedConfig.storedPublication(changedConfig.pendingForMatch(match).get(0)).orElseThrow());
        store.markPublished(first.update());
        assertTrue(newStore().pendingForMatch(match).isEmpty());
        assertThrows(IllegalStateException.class, () -> store.markPublished(first.update()));
    }

    @Test
    void unknownMatchCannotBeCreatedFromAGoal() {
        var other = new MatchEvent("foreign", match + "-unknown", EventType.GOAL, "home", 1, TIME, null);
        assertThrows(IllegalArgumentException.class, () -> new MatchStateService(store).process(other));
        assertTrue(store.load(other.matchId()).isEmpty()); assertEquals(0, count("accepted_event"));
    }
}
