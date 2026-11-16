package com.live_jgv.archiver.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.live_jgv.archiver.domain.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import java.nio.charset.StandardCharsets;
import java.util.stream.Stream;
import static org.junit.jupiter.api.Assertions.*;

class JsonEventDecoderTest {
    private final JsonEventDecoder decoder = new JsonEventDecoder();
    static final String GOAL = "{\"event_id\":\"e\",\"match_id\":\"m\",\"event_type\":\"GOAL\",\"team_id\":\"h\",\"minute\":10,\"timestamp\":\"2026-11-15T00:00:00.123456789Z\",\"reference_event_id\":null}";
    static InputRecord input(String value) {
        return new InputRecord("source/1","match_events",0,0,1,"m".getBytes(StandardCharsets.UTF_8),
                value == null ? null : value.getBytes(StandardCharsets.UTF_8),"[]");
    }
    @Test void preservesIdentityAndNanosecondTimestamp() {
        var result=decoder.decode(input(GOAL));assertNull(result.reason());
        assertEquals("e",result.event().eventId());assertEquals(123456789,result.event().timestamp().getNano());
    }
    @Test void acceptsAllFiveUnmodifiedPreviouslyCapturedProducerPayloads() throws Exception {
        try(var resource=getClass().getResourceAsStream("/fixtures/producer-events.jsonl")) {
            assertNotNull(resource);
            var lines=new String(resource.readAllBytes(),StandardCharsets.UTF_8).lines().toList();assertEquals(5,lines.size());
            var mapper=new ObjectMapper();
            for(String line:lines) {
                var json=mapper.readTree(line);var record=new InputRecord("fixture/1","match_events",0,0,1,
                        json.get("match_id").asText().getBytes(StandardCharsets.UTF_8),line.getBytes(StandardCharsets.UTF_8),"[]");
                var decoded=decoder.decode(record);assertNull(decoded.reason(),line);
                assertEquals(json.get("event_id").asText(),decoded.event().eventId());
                assertArrayEquals(line.getBytes(StandardCharsets.UTF_8),record.value());
            }
        }
    }
    static Stream<String> invalidEvents() {
        return Stream.of(GOAL.replace("\"minute\":10","\"minute\":\"10\""),
            GOAL.replace("\"minute\":10","\"minute\":1.5"),GOAL.replace("\"minute\":10","\"minute\":-1"),
            GOAL.replace("\"minute\":10","\"minute\":131"),GOAL.replace("\"minute\":10","\"minute\":99999999999999"),
            GOAL.replace("\"minute\":10,",""),GOAL.replace("\"team_id\":\"h\"","\"team_id\":null"),
            GOAL.replace("\"event_id\":\"e\"","\"event_id\":\" \""),GOAL.replace("\"event_id\":\"e\"","\"event_id\":5"),
            GOAL.replace("\"match_id\":\"m\",",""),GOAL.replace("\"GOAL\"","\"OTHER\""),
            GOAL.replace("\"timestamp\":\"2026-11-15T00:00:00.123456789Z\"","\"timestamp\":42"),
            GOAL.replace("2026-11-15T00:00:00.123456789Z","yesterday"),GOAL.replace("\"GOAL\"","\"GOAL_ANNULLED\""),
            GOAL.replace("\"reference_event_id\":null","\"reference_event_id\":7"),
            GOAL.replace("\"reference_event_id\":null","\"future_field\":true"));
    }
    @ParameterizedTest @MethodSource("invalidEvents") void rejectsInvalidFieldsWithoutThrowing(String value) {
        assertEquals(DecodedInput.Reason.INVALID_EVENT,decoder.decode(input(value)).reason());
    }
    @ParameterizedTest @NullSource @ValueSource(strings={"", "{", "[]", "null", "42", "{} {}", "{\"a\":1,\"a\":2}"})
    void malformedJsonIsExplicit(String value) {
        assertEquals(DecodedInput.Reason.MALFORMED_JSON,decoder.decode(input(value)).reason());
    }
    @Test void rejectsInvalidUtf8WithoutLosingBytes() {
        var record=new InputRecord("s","t",0,0,0,null,new byte[]{(byte)0xc3,0x28},"[]");
        assertEquals(DecodedInput.Reason.MALFORMED_JSON,decoder.decode(record).reason());
        assertArrayEquals(new byte[]{(byte)0xc3,0x28},record.value());
    }
    @Test void keyMustMatchMatchId() {
        var record=input(GOAL);var wrong=new InputRecord("s","t",0,0,0,new byte[]{'x'},record.value(),"[]");
        assertEquals(DecodedInput.Reason.KEY_MISMATCH,decoder.decode(wrong).reason());
    }
    @Test void structuralCanonicalizationIgnoresWhitespaceAndOptionalNullAbsence() {
        var other=GOAL.replace("{","{ ").replace(",\"reference_event_id\":null","");
        assertEquals(decoder.decode(input(GOAL)).canonicalPayload(),decoder.decode(input(other)).canonicalPayload());
    }
    @Test void outOfOrderAnnulmentIsAValidHistoricalFact() {
        var value=GOAL.replace("\"GOAL\"","\"GOAL_ANNULLED\"").replace("\"reference_event_id\":null","\"reference_event_id\":\"not-seen-yet\"");
        assertNull(decoder.decode(input(value)).reason());
    }
    @Test void inputRecordDefensivelyCopiesEvidence() {
        byte[] value={1};var record=new InputRecord("s","t",0,0,0,null,value,"[]");value[0]=2;
        assertEquals(1,record.value()[0]);record.value()[0]=3;assertEquals(1,record.value()[0]);
    }
}
