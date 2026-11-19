package com.live_jgv.matchstate.domain.model;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import java.time.Instant;
import static org.junit.jupiter.api.Assertions.*;

class MatchEventTest {
    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t"})
    void rejectsMissingIdentifiersAndConditionalFields(String invalid) {
        assertThrows(IllegalArgumentException.class, () -> new MatchEvent(invalid, "m", EventType.GOAL, "t", 1, Instant.EPOCH, null));
        assertThrows(IllegalArgumentException.class, () -> new MatchEvent("e", invalid, EventType.GOAL, "t", 1, Instant.EPOCH, null));
        assertThrows(IllegalArgumentException.class, () -> new MatchEvent("e", "m", EventType.GOAL, invalid, 1, Instant.EPOCH, null));
        assertThrows(IllegalArgumentException.class, () -> new MatchEvent("e", "m", EventType.GOAL_ANNULLED, null, 1, Instant.EPOCH, invalid));
    }

    @Test
    void validatesTypeTimestampAndProvisionalMinuteBounds() {
        assertThrows(IllegalArgumentException.class, () -> new MatchEvent("e", "m", null, "t", 1, Instant.EPOCH, null));
        assertThrows(IllegalArgumentException.class, () -> new MatchEvent("e", "m", EventType.GOAL, "t", 1, null, null));
        for (int minute : new int[] {-1, 131}) {
            assertThrows(IllegalArgumentException.class, () -> new MatchEvent("e", "m", EventType.GOAL, "t", minute, Instant.EPOCH, null));
        }
        for (int minute : new int[] {0, 130}) {
            assertEquals(minute, new MatchEvent("e", "m", EventType.GOAL, "t", minute, Instant.EPOCH, null).minute());
        }
    }
}
