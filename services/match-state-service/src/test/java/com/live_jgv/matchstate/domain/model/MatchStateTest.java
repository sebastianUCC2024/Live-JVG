package com.live_jgv.matchstate.domain.model;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.time.Instant;
import static org.junit.jupiter.api.Assertions.*;

class MatchStateTest {
    private static final Instant TIME = Instant.parse("2026-11-12T15:00:00Z");
    private MatchState initial() { return MatchState.start("match-1", "home", "away"); }
    private MatchEvent event(String id, EventType type, String team, String reference) {
        return new MatchEvent(id, "match-1", type, team, 42, TIME, reference);
    }
    private MatchEvent goal(String id, String team) { return event(id, EventType.GOAL, team, null); }
    private MatchEvent annul(String id, String goal) { return event(id, EventType.GOAL_ANNULLED, null, goal); }

    @ParameterizedTest
    @ValueSource(strings = {"home", "away"})
    void registersAndAnnulsExactlyOneGoalForEitherTeam(String team) {
        var empty = initial();
        var scored = empty.apply(goal("g1", team)).apply(goal("g2", team));
        assertEquals(team.equals("home") ? new Score(2, 0) : new Score(0, 2), scored.score());
        var annulled = scored.apply(annul("a1", "g1"));
        assertEquals(team.equals("home") ? new Score(1, 0) : new Score(0, 1), annulled.score());
        assertEquals(2, annulled.goals().size());
        assertEquals(java.util.Set.of("g1"), annulled.annulledGoalIds());
        assertEquals(3, annulled.processedEvents().size());
        assertEquals(new Score(0, 0), empty.score());
        assertEquals(2, scored.processedEvents().size());
    }

    @Test
    void handlesConsecutiveGoalsForBothTeams() {
        var state = initial().apply(goal("g1", "home")).apply(goal("g2", "away")).apply(goal("g3", "home"));
        assertEquals(new Score(2, 1), state.score());
    }

    @Test
    void rejectsMissingGoalWithoutConsumingEventAndAllowsRetryAfterGoal() {
        var state = initial();
        var annulment = annul("a1", "g1");
        assertThrows(InvalidMatchEventException.class, () -> state.apply(annulment));
        assertTrue(state.processedEvents().isEmpty());
        var recovered = state.apply(goal("g1", "home")).apply(annulment);
        assertEquals(new Score(0, 0), recovered.score());
        assertEquals(2, recovered.processedEvents().size());
    }

    @Test
    void rejectsSecondAnnulmentAndNeverProducesNegativeScore() {
        var state = initial().apply(goal("g1", "home")).apply(annul("a1", "g1"));
        assertThrows(InvalidMatchEventException.class, () -> state.apply(annul("a2", "g1")));
        assertEquals(new Score(0, 0), state.score());
        assertEquals(2, state.processedEvents().size());
        assertThrows(IllegalArgumentException.class, () -> new Score(-1, 0));
    }

    @Test
    void duplicateGoalAndAnnulmentAreNoOpsButConflictingIdsAreRejected() {
        var goal = goal("g1", "home");
        var scored = initial().apply(goal);
        assertSame(scored, scored.apply(goal));
        assertThrows(InvalidMatchEventException.class, () -> scored.apply(goal("g1", "away")));
        var annulment = annul("a1", "g1");
        var state = scored.apply(annulment);
        assertSame(state, state.apply(annulment));
    }

    @Test
    void rejectsForeignMatchAndForeignGoalReference() {
        var state = initial();
        var foreign = new MatchEvent("g1", "match-2", EventType.GOAL, "home", 42, TIME, null);
        assertThrows(InvalidMatchEventException.class, () -> state.apply(foreign));
        var other = MatchState.start("match-2", "home", "away").apply(foreign);
        assertThrows(InvalidMatchEventException.class, () -> state.apply(annul("a1", "g1")));
        assertEquals(new Score(1, 0), other.score());
        assertTrue(state.processedEvents().isEmpty());
    }

    @Test
    void rejectsUnknownTeamAndInconsistentAnnulmentTeam() {
        assertThrows(InvalidMatchEventException.class, () -> initial().apply(goal("g1", "unknown")));
        var state = initial().apply(goal("g1", "home"));
        assertThrows(InvalidMatchEventException.class,
                () -> state.apply(event("a1", EventType.GOAL_ANNULLED, "away", "g1")));
        assertThrows(InvalidMatchEventException.class,
                () -> state.apply(event("v1", EventType.VAR_CHECK, "unknown", null)));
    }

    @Test
    void reviewAndContinuationKeepScoreAndRecordReviewState() {
        var scored = initial().apply(goal("g1", "home"));
        var review = scored.apply(event("v1", EventType.VAR_CHECK, null, "g1"));
        assertEquals(VarStatus.IN_PROGRESS, review.varStatus());
        assertEquals(scored.score(), review.score());
        var continued = review.apply(event("c1", EventType.MATCH_CONTINUES, null, null));
        assertEquals(VarStatus.CLEAR, continued.varStatus());
        assertEquals(scored.score(), continued.score());
        assertEquals(3, continued.processedEvents().size());
        assertEquals(VarStatus.CLEAR, review.apply(annul("a1", "g1")).varStatus());
    }

    @Test
    void allowsGeneralReviewButRejectsReferenceToNonGoal() {
        var review = initial().apply(event("v1", EventType.VAR_CHECK, null, null));
        assertThrows(InvalidMatchEventException.class, () -> review.apply(annul("a1", "v1")));
        assertThrows(InvalidMatchEventException.class,
                () -> review.apply(event("v2", EventType.VAR_CHECK, null, "missing")));
    }

    @Test
    void acceptsOldTimestampsInArrivalOrderAndDoesNotReplayDuplicateReview() {
        var reviewEvent = event("v1", EventType.VAR_CHECK, null, null);
        var state = initial().apply(reviewEvent).apply(event("c1", EventType.MATCH_CONTINUES, null, null));
        assertSame(state, state.apply(reviewEvent));
        var oldGoal = new MatchEvent("g1", "match-1", EventType.GOAL, "home", 1, TIME.minusSeconds(60), null);
        assertEquals(new Score(1, 0), state.apply(oldGoal).score());
    }

    @Test
    void protectsStateCollectionsAndRejectsInvalidMatchSetup() {
        var state = initial().apply(goal("g1", "home"));
        assertThrows(UnsupportedOperationException.class, () -> state.processedEvents().clear());
        assertThrows(UnsupportedOperationException.class, () -> state.goals().clear());
        assertThrows(UnsupportedOperationException.class, () -> state.annulledGoalIds().add("g1"));
        assertThrows(IllegalArgumentException.class, () -> MatchState.start("m", "same", "same"));
        assertThrows(IllegalArgumentException.class, () -> MatchState.start(" ", "home", "away"));
        assertThrows(IllegalArgumentException.class, () -> initial().apply(null));
    }
}
