package com.live_jgv.matchstate.domain.model;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PreparedVarCorrelationTest {
    private MatchEvent event(String id, EventType type, String ref) {
        return new MatchEvent(id, "m", type, type == EventType.GOAL ? "home" : null,
                1, Instant.EPOCH, ref);
    }

    @Test void twoReviewsCloseIndividuallyAndReplayWithoutChangingGoals() {
        var goal = event("g", EventType.GOAL, null);
        var first = event("v1", EventType.VAR_CHECK, "g");
        var second = event("v2", EventType.VAR_CHECK, null);
        var closeFirst = event("c1", EventType.MATCH_CONTINUES, "v1");
        var closeLast = event("c2", EventType.MATCH_CONTINUES, "v2");
        var partial = PreparedVarCorrelation.replay(List.of(goal, first, second, closeFirst));
        assertEquals(VarStatus.IN_PROGRESS, partial.reviews().status());
        assertFalse(partial.reviews().reviews().get("v1"));
        assertTrue(partial.reviews().reviews().get("v2"));
        var finished = partial.apply(closeLast);
        assertEquals(VarStatus.CLEAR, finished.reviews().status());
        var rebuilt = PreparedVarCorrelation.replay(List.of(goal, first, second, closeFirst, closeLast, first));
        assertEquals(finished.events(), rebuilt.events());
        assertEquals(finished.reviews().reviews(), rebuilt.reviews().reviews());
        assertSame(finished, finished.apply(first));
        assertSame(finished, finished.apply(closeLast));
        var score = MatchState.start("m", "home", "away").apply(goal);
        assertEquals(new Score(1, 0), score.score());
        assertEquals(goal, rebuilt.events().get("g"));
    }

    @Test void earlyAndIncorrectClosuresAreRejectedWithoutLosingOpenReview() {
        var first = event("v1", EventType.VAR_CHECK, null);
        var state = PreparedVarCorrelation.empty().apply(first);
        for (String reference : new String[]{null, "missing", "goal"}) {
            assertThrows(IllegalArgumentException.class,
                    () -> state.apply(event("bad", EventType.MATCH_CONTINUES, reference)));
        }
        var early = event("early", EventType.MATCH_CONTINUES, "v2");
        assertThrows(IllegalArgumentException.class, () -> state.apply(early));
        var later = state.apply(event("v2", EventType.VAR_CHECK, null)).apply(early);
        assertEquals(VarStatus.IN_PROGRESS, later.reviews().status());
        assertEquals(1, state.events().size());
        assertEquals(VarStatus.IN_PROGRESS, state.reviews().status());
        assertThrows(IllegalArgumentException.class,
                () -> state.apply(event("v1", EventType.VAR_CHECK, "other")));
    }

    @Test void lateClosureAndOldTimestampCannotCloseNewReview() {
        var state = PreparedVarCorrelation.replay(List.of(event("v1", EventType.VAR_CHECK, null),
                event("c1", EventType.MATCH_CONTINUES, "v1"), event("v2", EventType.VAR_CHECK, null)));
        var late = state.apply(event("late", EventType.MATCH_CONTINUES, "v1"));
        assertTrue(late.reviews().reviews().get("v2"));
        assertEquals(VarStatus.IN_PROGRESS, late.reviews().status());
        assertThrows(UnsupportedOperationException.class,
                () -> late.apply(event("annul", EventType.GOAL_ANNULLED, "g")));
        assertEquals(4, late.events().size());
    }

    @Test void goalAndCrossMatchReferencesCannotMasqueradeAsReviews() {
        var state = PreparedVarCorrelation.empty().apply(event("g", EventType.GOAL, null));
        assertThrows(IllegalArgumentException.class,
                () -> state.apply(event("bad", EventType.MATCH_CONTINUES, "g")));
        assertThrows(IllegalArgumentException.class,
                () -> state.apply(event("bad", EventType.VAR_CHECK, "missing")));
        assertThrows(IllegalArgumentException.class,
                () -> state.apply(new MatchEvent("v", "other", EventType.VAR_CHECK, null, 1, Instant.EPOCH, null)));
    }
}
