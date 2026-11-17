package com.live_jgv.notification.infrastructure.json;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.live_jgv.notification.domain.*;
import java.time.Instant;
import java.util.*;
public final class LiveAlertCodec {
    private final ObjectMapper mapper=JsonMapper.builder().addModule(new JavaTimeModule())
        .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE).disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    public LiveAlertV1 decode(byte[] body) {
        if(body==null || body.length==0 || body.length>65536) throw new InvalidUpdateException("Invalid LiveAlert size");
        try {
            var node=mapper.readTree(body);
            var fields=Set.of("contract_version","alert_id","match_id","source_event_id","event_type","message","timestamp","reference_event_id","snapshot");
            if(!node.isObject() || node.size()!=fields.size()) throw new IllegalArgumentException();
            var names=node.fieldNames(); while(names.hasNext()) if(!fields.contains(names.next())) throw new IllegalArgumentException();
            for(var field:fields) if(!field.equals("snapshot") && !field.equals("reference_event_id") && !node.path(field).isTextual()) throw new IllegalArgumentException();
            var ref=node.get("reference_event_id"); if(!ref.isNull() && !ref.isTextual()) throw new IllegalArgumentException();
            var id=UUID.fromString(node.get("alert_id").textValue());
            if(!id.toString().equals(node.get("alert_id").textValue())) throw new IllegalArgumentException();
            return new LiveAlertV1(node.get("contract_version").textValue(),id,node.get("match_id").textValue(),node.get("source_event_id").textValue(),
                ScoreUpdate.EventType.valueOf(node.get("event_type").textValue()),node.get("message").textValue(),Instant.parse(node.get("timestamp").textValue()),
                ref.isNull()?null:ref.textValue(),new ScoreUpdateCodec().decode(mapper.writeValueAsBytes(node.get("snapshot"))));
        } catch(Exception failure) { throw new InvalidUpdateException("Malformed or incoherent LiveAlert V1"); }
    }
    public String encode(LiveAlertV1 alert) {
        try { return mapper.writeValueAsString(alert); } catch(Exception failure) { throw new IllegalStateException("Cannot encode LiveAlert",failure); }
    }
}
