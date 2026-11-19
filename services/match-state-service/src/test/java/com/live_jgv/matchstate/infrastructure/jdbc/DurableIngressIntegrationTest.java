package com.live_jgv.matchstate.infrastructure.jdbc;

import com.live_jgv.matchstate.application.service.*;
import com.live_jgv.matchstate.domain.model.*;
import com.live_jgv.matchstate.application.port.out.ScoreUpdatePublisher;
import com.live_jgv.matchstate.infrastructure.config.MessagingConfiguration;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@org.junit.jupiter.api.Tag("external-integration")
@org.junit.jupiter.api.Tag("postgres-integration")
@org.junit.jupiter.api.extension.ExtendWith(com.live_jgv.test.DurableIntegrationSafety.class)
class DurableIngressIntegrationTest {
    private static JdbcTemplate jdbc;
    private static TransactionTemplate tx;
    private final com.fasterxml.jackson.databind.ObjectMapper mapper = new MessagingConfiguration().messagingObjectMapper();
    private JdbcMatchStateStore store;
    private DurableEventIngress ingress;
    private final List<StoredScoreUpdate> outputs = new ArrayList<>();
    private boolean publicationFails;
    private String match;
    private String source;
    private long offset;

    @BeforeAll static void migrate() {
        var ds = new DriverManagerDataSource(System.getenv("MATCH_STATE_TEST_DB_URL"), System.getenv("MATCH_STATE_TEST_DB_USERNAME"), System.getenv("MATCH_STATE_TEST_DB_PASSWORD"));
        Flyway.configure().dataSource(ds).schemas("match_state").defaultSchema("match_state").cleanDisabled(true).load().migrate();
        jdbc = new JdbcTemplate(ds); tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
    }
    private DurableEventIngress freshIngress() {
        store = new JdbcMatchStateStore(jdbc, tx, mapper, "test-updates", "score.{matchId}");
        var publisher = new ScoreUpdatePublisher() {
            public void publish(ScoreUpdate update) { fail("Durable ingress must use stored image"); }
            public void publishStored(StoredScoreUpdate update) { if (publicationFails) throw new IllegalStateException("test publication unavailable"); outputs.add(update); }
        };
        return new DurableEventIngress(jdbc, tx, mapper, store, new ProcessAndPublishMatchEvent(new MatchStateService(store), store, publisher));
    }
    @BeforeEach void setup() {
        match = "ingress-" + UUID.randomUUID(); source = match;
        ingress = freshIngress(); store.register(MatchState.start(match, "home", "away"));
    }
    private MatchEvent event(String id, EventType type, String team, String ref) { return new MatchEvent(id, match, type, team, 10, Instant.EPOCH, ref); }
    private InputRecord input(byte[] value) { return new InputRecord(source, "test-topic", 0, offset++, 123, match.getBytes(StandardCharsets.UTF_8), value, "[]"); }
    private InputRecord input(MatchEvent event) throws Exception { return input(mapper.writeValueAsBytes(event)); }
    private UUID caseId() { return jdbc.queryForObject("SELECT DISTINCT case_id FROM match_state.input_receipt WHERE source_id=? AND case_id IS NOT NULL", UUID.class, source); }
    private long cases() { return jdbc.queryForObject("SELECT count(DISTINCT case_id) FROM match_state.input_receipt WHERE source_id=?", Long.class, source); }
    private ReplayRequest request(UUID id, long revision) { return new ReplayRequest(UUID.randomUUID(), id, "integration-operator", "reviewed test case", revision); }

    @ParameterizedTest
    @ValueSource(strings = {"broken-json", "{}", "null", "{\"event_type\":\"UNKNOWN\"}", "{\"event_id\":123}"})
    void malformedInputIsPreservedBeforeValidEventContinues(String text) throws Exception {
        var raw = input(text.getBytes(StandardCharsets.UTF_8)); ingress.handle(raw); ingress.handle(raw);
        assertEquals(1, cases());
        var row = jdbc.queryForMap("SELECT * FROM match_state.input_receipt WHERE source_id=?", source);
        assertArrayEquals(raw.value(), (byte[]) row.get("raw_value")); assertEquals(2, ((Number) row.get("delivery_attempts")).intValue());
        assertEquals("QUARANTINED", row.get("outcome")); assertTrue(outputs.isEmpty());
        ingress.handle(input(event("valid", EventType.GOAL, "home", null)));
        assertEquals(new Score(1, 0), store.findById(match).orElseThrow().score()); assertEquals(1, outputs.size());
    }

    @Test void rawInvalidUtf8AndTombstoneAreRetained() {
        var raw = input(new byte[]{(byte)0xc3, (byte)0x28}); ingress.handle(raw); ingress.handle(input((byte[])null));
        assertEquals(2, cases());
        assertArrayEquals(raw.value(), jdbc.queryForObject("SELECT raw_value FROM match_state.input_receipt WHERE source_id=? AND record_offset=0", byte[].class, source));
        assertTrue(outputs.isEmpty());
    }

    @Test void invalidTimestampIsPermanentAndAvailableIdentityIsSearchable() throws Exception {
        var valid = event("bad-date", EventType.GOAL, "home", null);
        String text = mapper.writeValueAsString(valid).replace("1970-01-01T00:00:00Z", "not-a-date");
        ingress.handle(input(text.getBytes(StandardCharsets.UTF_8)));
        var q = jdbc.queryForMap("SELECT * FROM match_state.quarantine_case WHERE case_id=?", caseId());
        assertEquals("bad-date", q.get("event_id")); assertEquals(match, q.get("match_id")); assertEquals("MALFORMED", q.get("reason_code"));
        ingress.handle(input(event("next", EventType.GOAL, "home", null)));
        assertEquals(1, outputs.size());
    }

    @Test void permanentDomainErrorAndItsDuplicateDoNotChangeScore() throws Exception {
        var invalid = event("foreign-team", EventType.GOAL, "other", null);
        ingress.handle(input(invalid)); ingress.handle(input(invalid));
        assertEquals(1, cases()); assertEquals("UNKNOWN_TEAM", jdbc.queryForObject("SELECT reason_code FROM match_state.quarantine_case WHERE case_id=?", String.class, caseId()));
        assertEquals("REJECTED", ingress.replay(request(caseId(), 0))); assertTrue(outputs.isEmpty());
        assertEquals(new Score(0, 0), store.findById(match).orElseThrow().score());
    }

    @Test void deferredAnnulmentSurvivesRestartAndExplicitReplayIsIdempotent() throws Exception {
        var goal = event("goal", EventType.GOAL, "home", null); var annul = event("annul", EventType.GOAL_ANNULLED, null, goal.eventId());
        ingress.handle(input(annul)); UUID id = caseId();
        assertEquals("REJECTED", ingress.replay(request(id, 0))); // reference still missing
        ingress = freshIngress(); ingress.handle(input(annul)); assertEquals(1, cases());
        ingress.handle(input(goal)); assertEquals(new Score(1, 0), store.findById(match).orElseThrow().score());
        assertEquals("REVIEW_REQUIRED", ingress.replay(request(id, 0)));
        var approval = request(id, 1); assertEquals("APPLIED", ingress.replay(approval));
        assertEquals("APPLIED", ingress.replay(approval)); assertEquals("ALREADY_APPLIED", ingress.replay(request(id, 2)));
        assertEquals(2, outputs.size()); assertEquals(new Score(0, 0), store.findById(match).orElseThrow().score());
        assertEquals(2, store.load(match).orElseThrow().revision()); assertEquals("RESOLVED", jdbc.queryForObject("SELECT review_state FROM match_state.quarantine_case WHERE case_id=?", String.class, id));
        assertEquals(4, jdbc.queryForObject("SELECT count(*) FROM match_state.replay_attempt WHERE case_id=?", Integer.class, id));
    }

    @Test void replayCannotAnnulAGoalAlreadyAnnulledByAnotherEvent() throws Exception {
        ingress.handle(input(event("deferred", EventType.GOAL_ANNULLED, null, "g"))); var id = caseId();
        ingress.handle(input(event("g", EventType.GOAL, "home", null)));
        ingress.handle(input(event("other-annul", EventType.GOAL_ANNULLED, null, "g")));
        assertEquals("REJECTED", ingress.replay(request(id, 2))); assertEquals(2, outputs.size());
    }

    @Test void reservedIdentityCannotBeChangedWhileDeferred() throws Exception {
        ingress.handle(input(event("a", EventType.GOAL_ANNULLED, null, "g1")));
        UUID original = caseId(); ingress.handle(input(event("a", EventType.GOAL_ANNULLED, null, "g2")));
        assertEquals(2, cases());
        ingress.handle(input(event("g1", EventType.GOAL, "home", null)));
        assertEquals("APPLIED", ingress.replay(request(original, 1)));
        assertEquals("g1", outputs.get(1).update().referenceEventId());
    }

    @Test void quarantineInsertFailureRollsBackCustodyAndRetryCreatesOneCase() {
        long casesBefore = jdbc.queryForObject("SELECT count(*) FROM match_state.quarantine_case", Long.class);
        String trigger = "ingress_fault_" + UUID.randomUUID().toString().replace("-", "");
        jdbc.execute("CREATE OR REPLACE FUNCTION match_state.phase7i_reject_receipt() RETURNS trigger AS $$ BEGIN IF NEW.source_id=TG_ARGV[0] THEN RAISE EXCEPTION 'phase7i receipt fault'; END IF; RETURN NEW; END; $$ LANGUAGE plpgsql");
        jdbc.execute("CREATE TRIGGER " + trigger + " BEFORE INSERT ON match_state.input_receipt FOR EACH ROW EXECUTE FUNCTION match_state.phase7i_reject_receipt('" + source + "')");
        var raw = input("broken".getBytes(StandardCharsets.UTF_8));
        try {
            assertThrows(org.springframework.dao.DataAccessException.class, () -> ingress.handle(raw));
            assertEquals(0, cases());
            assertEquals(casesBefore, jdbc.queryForObject("SELECT count(*) FROM match_state.quarantine_case", Long.class));
        } finally { jdbc.execute("DROP TRIGGER " + trigger + " ON match_state.input_receipt"); }
        ingress.handle(raw); assertEquals(1, cases());
        assertEquals(casesBefore + 1, jdbc.queryForObject("SELECT count(*) FROM match_state.quarantine_case", Long.class));
    }

    @Test void replayApplicationAndAuditCommitEvenIfPublicationMustBeRetried() throws Exception {
        ingress.handle(input(event("a", EventType.GOAL_ANNULLED, null, "g"))); UUID id = caseId();
        ingress.handle(input(event("g", EventType.GOAL, "home", null))); publicationFails = true;
        var approval = request(id, 1);
        assertThrows(IllegalStateException.class, () -> ingress.replay(approval));
        assertEquals(2, store.load(match).orElseThrow().revision()); assertEquals(1, store.pendingForMatch(match).size());
        publicationFails = false; ingress = freshIngress(); assertEquals("APPLIED", ingress.replay(approval));
        assertEquals(2, outputs.size()); assertTrue(store.pendingForMatch(match).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> ingress.replay(new ReplayRequest(approval.replayId(), id, "different-actor", approval.reason(), 1)));
    }
}
