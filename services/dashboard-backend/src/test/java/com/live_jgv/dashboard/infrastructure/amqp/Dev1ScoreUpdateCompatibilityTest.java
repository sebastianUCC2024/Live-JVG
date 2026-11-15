package com.live_jgv.dashboard.infrastructure.amqp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.live_jgv.dashboard.application.service.ProcessScoreUpdateService;
import com.live_jgv.dashboard.domain.model.*;
import com.live_jgv.dashboard.infrastructure.memory.InMemoryDashboardProjection;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import static org.junit.jupiter.api.Assertions.*;

class Dev1ScoreUpdateCompatibilityTest {
    private static final String HOME_GOAL = "00a6fa93-3f61-4c5b-8cb5-59c25cf3b021";
    private static final String AWAY_GOAL = "7f320bfd-72ac-4f74-a588-d04e33b7f6d5";

    private static List<JsonNode> fixtures() throws IOException {
        try (var input = Objects.requireNonNull(Dev1ScoreUpdateCompatibilityTest.class
                .getResourceAsStream("/fixtures/dev1-score-updates.json"))) {
            var nodes = new ArrayList<JsonNode>();
            new ObjectMapper().readTree(input).forEach(nodes::add);
            return nodes;
        }
    }

    static Stream<Arguments> recordedUpdates() throws IOException {
        var samples = fixtures();
        assertEquals(5, samples.size());
        return Stream.of(
                Arguments.of(samples.get(0), EventType.GOAL, 1, 0, VarStatus.CLEAR, HOME_GOAL, null,
                        "2026-11-15T00:12:12.079218900Z"),
                Arguments.of(samples.get(1), EventType.GOAL, 1, 1, VarStatus.CLEAR, AWAY_GOAL, null,
                        "2026-11-15T00:12:12.523727300Z"),
                Arguments.of(samples.get(2), EventType.VAR_CHECK, 1, 1, VarStatus.IN_PROGRESS,
                        "5a6397b3-f299-4c89-94b0-db1d167cbc74", AWAY_GOAL, "2026-11-15T00:12:12.676960200Z"),
                Arguments.of(samples.get(3), EventType.GOAL_ANNULLED, 1, 0, VarStatus.CLEAR,
                        "1b975025-062a-4a45-9bd4-264b8e983e6c", AWAY_GOAL, "2026-11-15T00:12:12.815787600Z"),
                Arguments.of(samples.get(4), EventType.MATCH_CONTINUES, 1, 0, VarStatus.CLEAR,
                        "d2ab559d-d2f9-4dda-8494-1938647c0d3d", null, "2026-11-15T00:12:13.004654900Z"));
    }

    @ParameterizedTest
    @MethodSource("recordedUpdates")
    void consumesActualDev1WireExamples(JsonNode json, EventType type, int home, int away,
            VarStatus status, String eventId, String reference, String timestamp) {
        var projection = new InMemoryDashboardProjection(10);
        var listener = new ScoreUpdateListener(new ScoreUpdateMessageDecoder(), new ProcessScoreUpdateService(projection));
        listener.onMessage(ScoreUpdateListenerTest.message(json.toString()));
        var snapshot = projection.findByMatchId("match-001").orElseThrow();
        assertEquals(new ScoreUpdate("match-001", home, away, eventId, type, Instant.parse(timestamp), status, reference),
                snapshot);
    }

    @Test
    void recordedSequenceAndOldDuplicatesKeepFinalScore() throws IOException {
        var samples = fixtures();
        var projection = new InMemoryDashboardProjection(5);
        var listener = new ScoreUpdateListener(new ScoreUpdateMessageDecoder(), new ProcessScoreUpdateService(projection));
        for (int index : new int[] {0, 0, 1, 2, 3, 3, 4}) {
            listener.onMessage(ScoreUpdateListenerTest.message(samples.get(index).toString()));
        }
        var finalSnapshot = projection.findByMatchId("match-001").orElseThrow();
        assertEquals(1, finalSnapshot.homeScore());
        assertEquals(0, finalSnapshot.awayScore());
        assertEquals(EventType.MATCH_CONTINUES, finalSnapshot.eventType());
        assertEquals(VarStatus.CLEAR, finalSnapshot.varStatus());
        listener.onMessage(ScoreUpdateListenerTest.message(samples.get(0).toString()));
        assertSame(finalSnapshot, projection.findByMatchId("match-001").orElseThrow());
    }

    @Test
    void timestampOnlyReplayDoesNotRegressProjection() throws IOException {
        var samples = fixtures();
        var projection = new InMemoryDashboardProjection(5);
        var listener = new ScoreUpdateListener(new ScoreUpdateMessageDecoder(), new ProcessScoreUpdateService(projection));
        for (var sample : samples) {
            listener.onMessage(ScoreUpdateListenerTest.message(sample.toString()));
        }
        var latest = projection.findByMatchId("match-001").orElseThrow();
        var replay = samples.get(0).deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) replay).put("updated_at", "2026-11-16T10:00:00Z");
        listener.onMessage(ScoreUpdateListenerTest.message(replay.toString()));
        assertSame(latest, projection.findByMatchId("match-001").orElseThrow());
    }
}
