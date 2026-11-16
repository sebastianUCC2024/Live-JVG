package com.live_jgv.worker.infrastructure;

import com.live_jgv.worker.domain.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import java.nio.charset.StandardCharsets;
import java.util.stream.Stream;
import static org.junit.jupiter.api.Assertions.*;

class JsonCommandDecoderTest {
    static final String COMMAND="{\"schema_version\":1,\"command_id\":\"c\",\"operation_id\":\"o\",\"match_id\":\"m\",\"simulated_user_id\":\"sim-user\",\"timestamp\":\"2026-11-15T00:00:00.123456789Z\"}";
    private final JsonCommandDecoder decoder=new JsonCommandDecoder();
    private TransportMessage message(String body){return new TransportMessage("s",null,"x","k",body==null?null:body.getBytes(StandardCharsets.UTF_8),"{}");}
    @Test void provisionalLocalCommandPreservesIdentityAndTimestamp(){
        var result=decoder.decode(message(COMMAND));assertNull(result.error());assertEquals("o",result.command().operationId());assertEquals(123456789,result.command().timestamp().getNano());
    }
    static Stream<String> invalid(){return Stream.of(
        COMMAND.replace("\"schema_version\":1","\"schema_version\":2"),COMMAND.replace("\"schema_version\":1","\"schema_version\":1.5"),
        COMMAND.replace("\"schema_version\":1","\"schema_version\":\"1\""),COMMAND.replace("\"schema_version\":1,",""),
        COMMAND.replace("\"command_id\":\"c\"","\"command_id\":null"),COMMAND.replace("\"operation_id\":\"o\"","\"operation_id\":42"),
        COMMAND.replace("\"match_id\":\"m\"","\"match_id\":\" \""),COMMAND.replace("\"simulated_user_id\":\"sim-user\",",""),
        COMMAND.replace("2026-11-15T00:00:00.123456789Z","invalid"),COMMAND.replace("{","{\"amount\":10,"),
        COMMAND.replace("{","{\"market_status\":\"OPEN\","));}
    @ParameterizedTest @MethodSource("invalid") void invalidOrFinancialFieldsCannotBecomeACommand(String body){assertEquals("INVALID_COMMAND",decoder.decode(message(body)).error());}
    @ParameterizedTest @NullSource @ValueSource(strings={"", "null", "[]", "{", "{} {}", "{\"a\":1,\"a\":2}"})
    void malformedJsonIsExplicit(String body){assertEquals("MALFORMED_JSON",decoder.decode(message(body)).error());}
    @Test void invalidUtf8IsNotSilentlyReplaced(){
        assertEquals("MALFORMED_JSON",decoder.decode(new TransportMessage("s",null,"x","k",new byte[]{(byte)0xc3,0x28},"{}")).error());
    }
    @Test void canonicalizationIsStructuralNotByteOrder(){assertEquals(decoder.decode(message(COMMAND)).canonicalPayload(),decoder.decode(message(COMMAND.replace("{","{ "))).canonicalPayload());}
}
