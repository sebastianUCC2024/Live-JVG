package com.live_jgv.dashboard.infrastructure.amqp;

import com.live_jgv.dashboard.application.service.ProcessScoreUpdateService;
import com.live_jgv.dashboard.domain.model.*;
import com.live_jgv.dashboard.infrastructure.memory.InMemoryDashboardProjection;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import java.util.stream.Stream;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import static org.junit.jupiter.api.Assertions.*;

class ScoreUpdateListenerTest {
    static final String JSON = """
            {"match_id":"match-1","home_score":2,"away_score":1,"event_id":"event-1",
             "event_type":"GOAL","updated_at":"2026-11-14T12:00:00Z",
             "var_status":"CLEAR","reference_event_id":null}
            """;

    static Message message(String json) {
        return new Message(json.getBytes(StandardCharsets.UTF_8), new MessageProperties());
    }

    @Test
    void validJsonInvokesApplicationAndUpdatesProjection() {
        var projection = new InMemoryDashboardProjection(10);
        var listener = new ScoreUpdateListener(new ScoreUpdateMessageDecoder(), new ProcessScoreUpdateService(projection));
        listener.onMessage(message(JSON));
        var snapshot = projection.findByMatchId("match-1").orElseThrow();
        assertEquals(2, snapshot.homeScore());
        assertEquals(1, snapshot.awayScore());
        assertEquals("event-1", snapshot.eventId());
        assertEquals("2026-11-14T12:00:00Z", snapshot.updatedAt().toString());
        listener.onMessage(message(JSON.replace("event-1", "event-2").replace("\"home_score\":2", "\"home_score\":3")));
        assertEquals(3, projection.findByMatchId("match-1").orElseThrow().homeScore());
    }

    @Test
    void varEventPreservesScoreAndCarriesResultingStatus() {
        var updates = new ArrayList<ScoreUpdate>();
        var listener = new ScoreUpdateListener(new ScoreUpdateMessageDecoder(), new ProcessScoreUpdateService(updates::add));
        listener.onMessage(message(JSON.replace("GOAL", "VAR_CHECK").replace("CLEAR", "IN_PROGRESS")));
        assertEquals(EventType.VAR_CHECK, updates.get(0).eventType());
        assertEquals(VarStatus.IN_PROGRESS, updates.get(0).varStatus());
        assertEquals(2, updates.get(0).homeScore());
    }

    @Test
    void annulmentCarriesOriginalReferenceAndFullScore() {
        var update = new ScoreUpdateMessageDecoder().decode(message(JSON.replace("GOAL", "GOAL_ANNULLED")
                .replace("null", "\"goal-original\"")).getBody());
        assertEquals("goal-original", update.referenceEventId());
        assertEquals(EventType.GOAL_ANNULLED, update.eventType());
    }

    @Test
    void optionalReferenceCanBeAbsent() {
        assertNull(new ScoreUpdateMessageDecoder().decode(message(JSON.replace(
                ",\"reference_event_id\":null", "")).getBody()).referenceEventId());
    }

    @ParameterizedTest
    @MethodSource("invalidMessages")
    void invalidMessageNeverCallsApplication(String body) {
        var updates = new ArrayList<ScoreUpdate>();
        var listener = new ScoreUpdateListener(new ScoreUpdateMessageDecoder(), updates::add);
        var failure = assertThrows(InvalidScoreUpdateMessageException.class, () -> listener.onMessage(message(body)));
        assertTrue(updates.isEmpty());
        assertNull(failure.getCause());
        assertEquals("Invalid score update message", failure.getMessage());
    }

    static Stream<String> invalidMessages() {
        return Stream.of("", "not-json", "null", "[]", "\"string\"", "{}", JSON + "{}",
                JSON.replace("\"home_score\":2,", ""),
                JSON.replace("\"home_score\":2", "\"home_score\":null"),
                JSON.replace("\"home_score\":2", "\"home_score\":-1"),
                JSON.replace("\"home_score\":2", "\"home_score\":2.5"),
                JSON.replace("\"home_score\":2", "\"home_score\":\"2\""),
                JSON.replace("\"match-1\"", "123"),
                JSON.replace("\"match-1\"", "\" \""),
                JSON.replace("\"GOAL\"", "\"UNKNOWN\""),
                JSON.replace("\"GOAL\"", "0"),
                JSON.replace("\"CLEAR\"", "null"),
                JSON.replace("\"2026-11-14T12:00:00Z\"", "\"invalid-date\""),
                JSON.replace("\"2026-11-14T12:00:00Z\"", "123"),
                JSON.replace("\"GOAL\"", "\"GOAL_ANNULLED\""),
                JSON.replace("\"event_id\":\"event-1\"", "\"event_id\":\"event-1\",\"event_id\":\"other\""),
                JSON.replace("\"match_id\"", "\"unknown_field\""));
    }

    @Test
    void processingFailurePropagatesForRetry() {
        var failure = new IllegalStateException("temporary output failure");
        var listener = new ScoreUpdateListener(new ScoreUpdateMessageDecoder(), update -> { throw failure; });
        assertSame(failure, assertThrows(IllegalStateException.class, () -> listener.onMessage(message(JSON))));
    }
}
