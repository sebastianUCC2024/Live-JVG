package com.live_jgv.dashboard.domain.model;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class ScoreUpdateTest {
    private static final Instant NOW = Instant.parse("2026-11-14T12:00:00Z");

    @ParameterizedTest
    @EnumSource(EventType.class)
    void acceptsAuthoritativeSnapshotsForEveryEventType(EventType type) {
        var update = new ScoreUpdate("match-1", 0, 0, "event-1", type, NOW, VarStatus.CLEAR, "goal-1");
        assertEquals(0, update.homeScore());
        assertEquals(type, update.eventType());
        assertEquals("goal-1", update.referenceEventId());
        assertEquals(NOW, update.updatedAt());
    }

    @Test
    void preservesIdentityAndAllowsVarWithoutChangingScore() {
        var update = new ScoreUpdate(" Match-1 ", 2, 1, "Event-1", EventType.VAR_CHECK,
                NOW, VarStatus.IN_PROGRESS, null);
        assertEquals(" Match-1 ", update.matchId());
        assertEquals(2, update.homeScore());
        assertEquals(1, update.awayScore());
        assertEquals(VarStatus.IN_PROGRESS, update.varStatus());
        assertNull(update.referenceEventId());
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t\n"})
    void rejectsMissingIdentifiers(String id) {
        assertThrows(InvalidScoreUpdateException.class, () ->
                new ScoreUpdate(id, 0, 0, "e", EventType.GOAL, NOW, VarStatus.CLEAR, null));
        assertThrows(InvalidScoreUpdateException.class, () ->
                new ScoreUpdate("m", 0, 0, id, EventType.GOAL, NOW, VarStatus.CLEAR, null));
    }

    @Test
    void rejectsNegativeScoresForEitherTeam() {
        assertThrows(InvalidScoreUpdateException.class, () ->
                new ScoreUpdate("m", -1, 0, "e", EventType.GOAL, NOW, VarStatus.CLEAR, null));
        assertThrows(InvalidScoreUpdateException.class, () ->
                new ScoreUpdate("m", 0, -1, "e", EventType.GOAL, NOW, VarStatus.CLEAR, null));
    }

    @Test
    void rejectsMissingTypeTimestampOrVarStatus() {
        assertThrows(InvalidScoreUpdateException.class, () ->
                new ScoreUpdate("m", 0, 0, "e", null, NOW, VarStatus.CLEAR, null));
        assertThrows(InvalidScoreUpdateException.class, () ->
                new ScoreUpdate("m", 0, 0, "e", EventType.GOAL, null, VarStatus.CLEAR, null));
        assertThrows(InvalidScoreUpdateException.class, () ->
                new ScoreUpdate("m", 0, 0, "e", EventType.GOAL, NOW, null, null));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t"})
    void annulmentRequiresOriginalEventReference(String reference) {
        assertThrows(InvalidScoreUpdateException.class, () -> new ScoreUpdate(
                "m", 0, 0, "e", EventType.GOAL_ANNULLED, NOW, VarStatus.CLEAR, reference));
    }

    @Test
    void rejectsBlankOptionalReference() {
        assertThrows(InvalidScoreUpdateException.class, () -> new ScoreUpdate(
                "m", 0, 0, "e", EventType.VAR_CHECK, NOW, VarStatus.IN_PROGRESS, " "));
    }
}
