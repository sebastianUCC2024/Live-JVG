package com.live_jgv.archiver.infrastructure;
import com.live_jgv.archiver.domain.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;
class VarFieldCompatibilityTest {
    private final JsonEventDecoder decoder=new JsonEventDecoder();
    private static final String PREFIX="{\"event_id\":\"annul\",\"match_id\":\"m\",\"event_type\":\"GOAL_ANNULLED\",\"team_id\":null,\"minute\":10,\"timestamp\":\"2026-11-15T00:00:00Z\",";
    static InputRecord input(String fields,long offset) { return new InputRecord("source","match_events",0,offset,123,"m".getBytes(StandardCharsets.UTF_8),(PREFIX+fields+"}").getBytes(StandardCharsets.UTF_8),"[]"); }
    @ParameterizedTest @ValueSource(strings={"\"annuls_event_id\":\"goal\"","\"reference_event_id\":\"goal\",\"annuls_event_id\":\"goal\"","\"annuls_event_id\":\"goal\",\"reference_event_id\":\"goal\"","\"reference_event_id\":null,\"annuls_event_id\":\"goal\"","\"reference_event_id\":\"goal\",\"annuls_event_id\":null"})
    void aliasesHaveIdenticalHistoricalCanonicalAndPreserveRawBytes(String fields) {
        var old=decoder.decode(input("\"reference_event_id\":\"goal\"",0));var record=input(fields,1);byte[] raw=record.value();var alias=decoder.decode(record);
        assertNull(alias.reason());assertEquals(old.event(),alias.event());assertEquals(old.canonicalPayload(),alias.canonicalPayload());assertFalse(alias.canonicalPayload().contains("annuls_event_id"));assertArrayEquals(raw,record.value());
        assertTrue(old.canonicalPayload().contains("\"reference_event_id\":\"goal\""));
    }
    @ParameterizedTest @ValueSource(strings={"\"annuls_event_id\":1","\"annuls_event_id\":true","\"annuls_event_id\":[]","\"annuls_event_id\":{}","\"annuls_event_id\":\"\"","\"annuls_event_id\":\" \"","\"annuls_event_id\":null","\"reference_event_id\":null","\"reference_event_id\":\"goal\",\"annuls_event_id\":\"other\"","\"reference_event_id\":\"\",\"annuls_event_id\":\"goal\""})
    void invalidOrContradictoryReferencesNeverProduceEvent(String fields) {assertNotNull(decoder.decode(input(fields,0)).reason());}
    @Test void duplicatePropertyIsMalformed() {assertEquals(DecodedInput.Reason.MALFORMED_JSON,decoder.decode(input("\"annuls_event_id\":\"goal\",\"annuls_event_id\":\"other\"",0)).reason());}
    @Test void aliasDoesNotRedefineOtherEventReferences() {var record=input("\"annuls_event_id\":\"goal\"",0);var raw=new String(record.value(),StandardCharsets.UTF_8).replace("GOAL_ANNULLED","MATCH_CONTINUES");assertNotNull(decoder.decode(new InputRecord("source","match_events",0,0,123,record.key(),raw.getBytes(StandardCharsets.UTF_8),"[]")).reason());}
    @Test void unknownGoalRemainsHistoricalFactNotInventedGoal() {var decoded=decoder.decode(input("\"annuls_event_id\":\"not-seen\"",0));assertNull(decoded.reason());assertEquals("not-seen",decoded.event().referenceEventId());}
}
