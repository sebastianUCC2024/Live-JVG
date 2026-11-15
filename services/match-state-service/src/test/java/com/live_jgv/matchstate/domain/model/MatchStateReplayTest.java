package com.live_jgv.matchstate.domain.model;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MatchStateReplayTest {
    private MatchEvent event(String id, EventType type, String reference) {
        return new MatchEvent(id, "m", type, type == EventType.GOAL ? "h" : null, 1, Instant.EPOCH, reference);
    }
    @Test void replayOfTwentyThousandEventsRetainsOldIdentitiesAndVarRules() {
        var replay = MatchState.rebuilder("m", "h", "a");
        var goal = event("old-goal", EventType.GOAL, null);
        replay.accept(goal);
        for (int i = 0; i < 20000; i++) replay.accept(event("e" + i, EventType.MATCH_CONTINUES, null));
        replay.accept(event("var", EventType.VAR_CHECK, "old-goal"));
        replay.accept(event("annul", EventType.GOAL_ANNULLED, "old-goal"));
        var state = replay.finish();
        assertEquals(20003, state.processedEvents().size());
        assertEquals(new Score(0, 0), state.score()); assertEquals(VarStatus.CLEAR, state.varStatus());
        assertSame(state, state.apply(goal));
        assertEquals(InvalidMatchEventException.Reason.GOAL_ALREADY_ANNULLED,
                assertThrows(InvalidMatchEventException.class, () -> state.apply(event("again", EventType.GOAL_ANNULLED, "old-goal"))).reason());
        assertThrows(UnsupportedOperationException.class, () -> state.processedEvents().clear());
        assertThrows(IllegalStateException.class, () -> replay.accept(goal));
    }
    @Test void replayAndOrdinaryApplyHaveIdenticalResultsAndRejections() {
        var replay = MatchState.rebuilder("m", "h", "a");
        var state = MatchState.start("m", "h", "a");
        for (int i = 0; i < 100; i++) {
            for (var e : java.util.List.of(event("g"+i, EventType.GOAL, null),
                    event("v"+i, EventType.VAR_CHECK, "g"+i), event("a"+i, EventType.GOAL_ANNULLED, "g"+i))) {
                replay.accept(e); state = state.apply(e);
            }
        }
        var invalid = event("bad", EventType.VAR_CHECK, "not-accepted");
        var expected = assertThrows(InvalidMatchEventException.class, () -> MatchState.start("m","h","a").apply(invalid));
        assertEquals(expected.reason(), assertThrows(InvalidMatchEventException.class, () -> replay.accept(invalid)).reason());
        var rebuilt = replay.finish();
        assertEquals(state.processedEvents(), rebuilt.processedEvents());
        assertEquals(state.annulledGoalIds(), rebuilt.annulledGoalIds());
        assertEquals(state.score(), rebuilt.score()); assertEquals(state.varStatus(), rebuilt.varStatus());
        var before = rebuilt.processedEvents().size();
        assertEquals(before + 1, rebuilt.apply(event("next", EventType.GOAL, null)).processedEvents().size());
        assertEquals(before, rebuilt.processedEvents().size());
    }
}
