package com.live_jgv.worker.academic;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.time.Instant;
import java.util.Set;

public final class AcademicCommandCodec {
    private static final Set<String> FIELDS=Set.of("schema_version","command_id","match_id","source_event_id","command_type","timestamp");
    private final ObjectMapper mapper=JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    public AcademicCommand decode(byte[] body) {
        try {
            if(body==null||body.length==0||body.length>65536)throw new IllegalArgumentException();
            String text=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(body)).toString();
            var node=mapper.readTree(text);
            if(node==null||!node.isObject()||node.size()!=FIELDS.size())throw new IllegalArgumentException();
            var names=node.fieldNames();while(names.hasNext())if(!FIELDS.contains(names.next()))throw new IllegalArgumentException();
            var version=node.path("schema_version");
            if(!version.isIntegralNumber()||!version.canConvertToInt()||version.intValue()!=1)throw new IllegalArgumentException();
            return new AcademicCommand(1,field(node,"command_id"),field(node,"match_id"),field(node,"source_event_id"),
                    AcademicCommand.CommandType.valueOf(field(node,"command_type")),Instant.parse(field(node,"timestamp")));
        } catch(Exception invalid){throw new IllegalArgumentException("Invalid academic command");}
    }
    private String field(JsonNode node,String field) {
        var value=node.get(field);if(value==null||!value.isTextual()||value.textValue().isBlank())throw new IllegalArgumentException();
        return value.textValue();
    }
}
