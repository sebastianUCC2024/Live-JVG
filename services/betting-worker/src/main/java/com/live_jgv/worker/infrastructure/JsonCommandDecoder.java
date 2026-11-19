package com.live_jgv.worker.infrastructure;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.live_jgv.worker.application.CommandDecoder;
import com.live_jgv.worker.domain.*;
import java.nio.*;
import java.nio.charset.*;
import java.time.Instant;
import java.util.*;

/** Decodifica el contrato privado; el comando no aporta evidencia de autoridad. */
public final class JsonCommandDecoder implements CommandDecoder {
    private static final Set<String> FIELDS=Set.of("schema_version","command_id","operation_id","match_id","simulated_user_id","timestamp");
    private final ObjectMapper mapper=JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    @Override public DecodedCommand decode(TransportMessage message){
        final JsonNode tree;
        try{
            if(message.body()==null)return DecodedCommand.invalid("MALFORMED_JSON");
            var text=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(message.body())).toString();
            tree=mapper.readTree(text);if(tree==null||!tree.isObject())return DecodedCommand.invalid("MALFORMED_JSON");
        }catch(Exception failure){return DecodedCommand.invalid("MALFORMED_JSON");}
        try{
            var fields=tree.fieldNames();while(fields.hasNext())if(!FIELDS.contains(fields.next()))throw new IllegalArgumentException("Unknown field");
            var version=tree.path("schema_version");
            if(!version.isIntegralNumber()||!version.canConvertToInt()||version.intValue()!=1)throw new IllegalArgumentException("Unsupported private schema version");
            var command=new SimulatedCommand(text(tree,"command_id"),text(tree,"operation_id"),text(tree,"match_id"),text(tree,"simulated_user_id"),Instant.parse(text(tree,"timestamp")));
            var canonical=new TreeMap<String,JsonNode>();for(String field:FIELDS)canonical.put(field,tree.get(field));
            return new DecodedCommand(command,mapper.writeValueAsString(canonical),null);
        }catch(Exception failure){return DecodedCommand.invalid("INVALID_COMMAND");}
    }
    private static String text(JsonNode tree,String field){
        var value=tree.get(field);if(value==null||!value.isTextual()||value.textValue().isBlank())throw new IllegalArgumentException("Text field required");return value.textValue();
    }
}
