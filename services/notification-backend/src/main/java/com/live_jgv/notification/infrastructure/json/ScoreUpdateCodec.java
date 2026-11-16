package com.live_jgv.notification.infrastructure.json;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.live_jgv.notification.domain.*;

public final class ScoreUpdateCodec {
    private final ObjectMapper mapper = JsonMapper.builder().addModule(new JavaTimeModule())
            .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    public ScoreUpdate decode(byte[] body) {
        if (body == null || body.length == 0 || body.length > 65536) throw invalid();
        try {
            var node = mapper.readTree(body);
            if (node == null || !node.isObject() || node.size() < 7 || node.size() > 8) throw invalid();
            var allowed = java.util.Set.of("match_id", "event_id", "event_type", "home_score", "away_score", "var_status", "updated_at", "reference_event_id");
            var names = node.fieldNames();
            while (names.hasNext()) if (!allowed.contains(names.next())) throw invalid();
            for (var field : java.util.List.of("match_id", "event_id", "event_type", "var_status", "updated_at"))
                if (!node.path(field).isTextual()) throw invalid();
            for (var field : java.util.List.of("home_score", "away_score"))
                if (!node.path(field).isIntegralNumber() || !node.path(field).canConvertToInt()) throw invalid();
            var reference = node.get("reference_event_id");
            if (reference != null && !reference.isNull() && !reference.isTextual()) throw invalid();
            return new ScoreUpdate(node.get("match_id").textValue(), node.get("event_id").textValue(),
                    ScoreUpdate.EventType.valueOf(node.get("event_type").textValue()), node.get("home_score").intValue(),
                    node.get("away_score").intValue(), ScoreUpdate.VarStatus.valueOf(node.get("var_status").textValue()),
                    java.time.Instant.parse(node.get("updated_at").textValue()),
                    reference == null || reference.isNull() ? null : reference.textValue());
        } catch (Exception error) { throw invalid(); }
    }
    public String encode(ScoreUpdate update) {
        try { return mapper.writeValueAsString(update); }
        catch (Exception error) { throw new IllegalStateException("Cannot encode validated update", error); }
    }
    private InvalidUpdateException invalid() { return new InvalidUpdateException("Malformed or unsupported ScoreUpdate"); }
}
