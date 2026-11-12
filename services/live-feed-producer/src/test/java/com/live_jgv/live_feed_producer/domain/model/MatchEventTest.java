package com.live_jgv.live_feed_producer.domain.model;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.reflect.Modifier;
import java.time.Instant;
import java.util.HashSet;

import static org.junit.jupiter.api.Assertions.*;

class MatchEventTest {
    private static final Instant TIME = Instant.parse("2026-11-12T15:00:00Z");

    private MatchEvent event(String id, String match, EventType type, String team,
                             int minute, Instant timestamp, String reference) {
        return new MatchEvent(id, match, type, team, minute, timestamp, reference);
    }

    @Test
    void createsValidGoal() {
        var goal = MatchEvent.create("match-1", EventType.GOAL, "team-1", 42, TIME, null);
        assertAll(() -> assertFalse(goal.eventId().isBlank()),
                () -> assertEquals("match-1", goal.matchId()),
                () -> assertEquals(EventType.GOAL, goal.eventType()),
                () -> assertEquals("team-1", goal.teamId()),
                () -> assertEquals(42, goal.minute()),
                () -> assertEquals(TIME, goal.timestamp()),
                () -> assertNull(goal.referenceEventId()));
    }

    @Test
    void generatesUniqueIdentifiers() {
        var ids = new HashSet<String>();
        for (int i = 0; i < 1000; i++) {
            assertTrue(ids.add(MatchEvent.create("match-1", EventType.GOAL,
                    "team-1", 42, TIME, null).eventId()));
        }
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t\n"})
    void rejectsInvalidEventId(String id) {
        assertThrows(IllegalArgumentException.class,
                () -> event(id, "match-1", EventType.GOAL, "team-1", 1, TIME, null));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t\n"})
    void rejectsInvalidMatchId(String match) {
        assertThrows(IllegalArgumentException.class,
                () -> event("event-1", match, EventType.GOAL, "team-1", 1, TIME, null));
    }

    @Test
    void rejectsNullEventType() {
        assertThrows(IllegalArgumentException.class,
                () -> event("event-1", "match-1", null, "team-1", 1, TIME, null));
    }

    @Test
    void rejectsNullTimestamp() {
        assertThrows(IllegalArgumentException.class,
                () -> event("event-1", "match-1", EventType.GOAL, "team-1", 1, null, null));
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, Integer.MIN_VALUE, 131, Integer.MAX_VALUE})
    void rejectsMinutesOutsideRange(int minute) {
        assertThrows(IllegalArgumentException.class,
                () -> event("event-1", "match-1", EventType.GOAL, "team-1", minute, TIME, null));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 130})
    void acceptsMinuteBoundaries(int minute) {
        assertEquals(minute, event("event-1", "match-1", EventType.GOAL,
                "team-1", minute, TIME, null).minute());
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t\n"})
    void rejectsGoalWithoutTeam(String team) {
        assertThrows(IllegalArgumentException.class,
                () -> event("event-1", "match-1", EventType.GOAL, team, 1, TIME, null));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t\n"})
    void rejectsAnnulmentWithoutReference(String reference) {
        assertThrows(IllegalArgumentException.class,
                () -> event("event-2", "match-1", EventType.GOAL_ANNULLED, null, 1, TIME, reference));
    }

    @Test
    void createsVarEventsAndContinuationWithoutTeam() {
        var review = event("review-1", "match-1", EventType.VAR_CHECK, null, 45, TIME, "goal-1");
        var annulment = event("annulment-1", "match-1", EventType.GOAL_ANNULLED, null, 46, TIME, "goal-1");
        var continuation = event("continue-1", "match-1", EventType.MATCH_CONTINUES, null, 47, TIME, null);
        assertAll(() -> assertEquals(EventType.VAR_CHECK, review.eventType()),
                () -> assertEquals("goal-1", annulment.referenceEventId()),
                () -> assertEquals(EventType.MATCH_CONTINUES, continuation.eventType()));
    }

    @Test
    void isImmutableRecordWithImmutableComponents() {
        assertTrue(MatchEvent.class.isRecord());
        assertTrue(Modifier.isFinal(MatchEvent.class.getModifiers()));
        for (var field : MatchEvent.class.getDeclaredFields()) {
            if (!Modifier.isStatic(field.getModifiers())) {
                assertTrue(Modifier.isPrivate(field.getModifiers()));
                assertTrue(Modifier.isFinal(field.getModifiers()));
                assertTrue(field.getType() == String.class || field.getType() == int.class
                        || field.getType() == Instant.class || field.getType() == EventType.class);
            }
        }
    }
}
