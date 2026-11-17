package com.live_jgv.matchstate.domain.model;

import com.live_jgv.matchstate.entrypoint.kafka.MatchEventMessage;
import com.live_jgv.matchstate.infrastructure.config.MessagingConfiguration;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Executes the harness's exact wire fixtures through the existing V1 engine and rebuilder. */
class E2EV1FixtureReplayTest {
    @Test void everyHarnessStepAndDuplicateRebuildWithHistoricalV1Semantics() throws Exception {
        var mapper = new MessagingConfiguration().messagingObjectMapper();
        var plan = mapper.readTree(Files.readString(Path.of("../../scripts/e2e/fixtures/v1-sequence.json")));
        var match = plan.path("match_id").asText();
        var state = MatchState.start(match, "home", "away");
        var history = new ArrayList<MatchEvent>();
        for (var step : plan.path("steps")) {
            var event = mapper.treeToValue(step.path("event"), MatchEventMessage.class).toDomain();
            state = state.apply(event);
            history.add(event);
            assertEquals(new Score(step.path("home_score").asInt(), step.path("away_score").asInt()), state.score());
            assertEquals(VarStatus.valueOf(step.path("var_status").asText()), state.varStatus());
            assertSame(state, state.apply(event));
            var rebuilder = MatchState.rebuilder(match, "home", "away");
            for (var accepted : history) rebuilder.accept(accepted);
            var restored = rebuilder.finish();
            assertEquals(state.score(), restored.score());
            assertEquals(state.varStatus(), restored.varStatus());
            assertEquals(state.processedEvents(), restored.processedEvents());
        }
        assertTrue(history.get(6).timestamp().isBefore(history.get(0).timestamp()));
        assertSame(state, state.apply(history.get(0))); // Old goal redelivery cannot change final 0-0.
        assertEquals(new Score(0, 0), state.score());
        assertEquals(8, state.processedEvents().size());
        var conflict = new MatchEvent(history.get(0).eventId(), match, EventType.GOAL, "away",
                10, history.get(0).timestamp(), null);
        var finalState = state;
        assertEquals(InvalidMatchEventException.Reason.ID_CONFLICT,
                assertThrows(InvalidMatchEventException.class, () -> finalState.apply(conflict)).reason());
    }
}
