package com.live_jgv.notification.infrastructure.json;

import static org.junit.jupiter.api.Assertions.*;
import com.live_jgv.notification.Fixtures;
import com.live_jgv.notification.domain.*;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ScoreUpdateCodecTest {
    private final ScoreUpdateCodec codec=new ScoreUpdateCodec();
    private byte[] bytes(String json) { return json.getBytes(StandardCharsets.UTF_8); }
    @Test void actualWireFieldsAndNanosecondsRoundTrip() {
        var update=codec.decode(bytes(Fixtures.JSON));
        assertEquals("goal-001",update.eventId()); assertEquals(123456789,update.updatedAt().getNano());
        assertEquals(update,codec.decode(bytes(codec.encode(update))));
        assertEquals(update,codec.decode(bytes(Fixtures.JSON.replace(",\"reference_event_id\":null",""))));
    }
    @ParameterizedTest @ValueSource(strings={"{}","null","[]","broken","{} {}"})
    void rejectsMalformedBodies(String json) { assertThrows(InvalidUpdateException.class,()->codec.decode(bytes(json))); }
    @ParameterizedTest @ValueSource(strings={"\"1\"","1.2","-1","2147483648","null","true"})
    void rejectsNonIntegerOrInvalidScores(String score) {
        assertThrows(InvalidUpdateException.class,()->codec.decode(bytes(Fixtures.JSON.replace("\"home_score\":1","\"home_score\":"+score))));
    }
    @Test void rejectsUnknownDuplicateTrailingAndMissingFields() {
        for (var bad : new String[]{Fixtures.JSON.replace("\"match_id\":", "\"unknown\":"),
                Fixtures.JSON.replace("\"home_score\":1", "\"home_score\":1,\"home_score\":2"),
                Fixtures.JSON+"{}",Fixtures.JSON.replace("\"event_type\":\"GOAL\"","\"event_type\":1"),
                Fixtures.JSON.replace("\"updated_at\":\"2026-11-15T15:30:00.123456789Z\"","\"updated_at\":123"),
                Fixtures.JSON.replace("\"event_type\":\"GOAL\"","\"event_type\":\"GOAL_ANNULLED\"")})
            assertThrows(InvalidUpdateException.class,()->codec.decode(bytes(bad)));
        assertThrows(InvalidUpdateException.class,()->codec.decode(new byte[65537]));
    }
}
