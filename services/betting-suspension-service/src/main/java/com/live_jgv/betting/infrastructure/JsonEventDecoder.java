package com.live_jgv.betting.infrastructure;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.live_jgv.betting.application.EventDecoder;
import com.live_jgv.betting.domain.*;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.time.Instant;
import java.util.Set;
import java.util.TreeMap;

public final class JsonEventDecoder implements EventDecoder {
    private static final Set<String> FIELDS = Set.of("event_id", "match_id", "event_type", "team_id",
            "minute", "timestamp", "reference_event_id");
    private final ObjectMapper mapper = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();

    @Override public DecodedInput decode(InputRecord record) {
        final JsonNode tree;
        try {
            if (record.value() == null) return DecodedInput.invalid(DecodedInput.Reason.MALFORMED_JSON);
            tree = mapper.readTree(utf8(record.value()));
            if (tree == null || !tree.isObject()) return DecodedInput.invalid(DecodedInput.Reason.MALFORMED_JSON);
        } catch (Exception failure) { return DecodedInput.invalid(DecodedInput.Reason.MALFORMED_JSON); }
        try {
            var names = tree.fieldNames();
            while (names.hasNext()) if (!allowed(names.next())) throw new IllegalArgumentException("Unknown field");
            var minute = tree.path("minute");
            if (!minute.isIntegralNumber() || !minute.canConvertToInt()) throw new IllegalArgumentException("Invalid minute");
            String reference = resolveReference(tree);
            var event = new SportEvent(text(tree, "event_id", true), text(tree, "match_id", true),
                    SportEvent.EventType.valueOf(text(tree, "event_type", true)), text(tree, "team_id", false),
                    minute.intValue(), Instant.parse(text(tree, "timestamp", true)), reference);
            if (record.key() == null || !event.matchId().equals(utf8(record.key())))
                return DecodedInput.invalid(DecodedInput.Reason.KEY_MISMATCH);
            // Canonicalize only known flat fields; missing optional fields and explicit null are equivalent.
            // Timestamp spelling is preserved, so conflicting wire values are never silently merged.
            var canonical = new TreeMap<String, JsonNode>();
            for (String field : FIELDS) canonical.put(field, tree.path(field).isMissingNode() ? mapper.nullNode() : tree.get(field));
            canonical.put("reference_event_id", reference == null ? mapper.nullNode() : mapper.getNodeFactory().textNode(reference));
            return DecodedInput.valid(event, mapper.writeValueAsString(canonical));
        } catch (Exception failure) { return DecodedInput.invalid(DecodedInput.Reason.INVALID_EVENT); }
    }
    // Keep FIELDS unchanged: normalized canonical JSON must match persisted V1 identities.
    private static boolean allowed(String name) { return FIELDS.contains(name) || name.equals("annuls_event_id"); }
    private static String resolveReference(JsonNode tree) {
        String legacy = text(tree, "reference_event_id", false);
        if (tree.has("annuls_event_id") && !"GOAL_ANNULLED".equals(text(tree,"event_type",true)))
            throw new IllegalArgumentException("annuls_event_id is only valid for GOAL_ANNULLED");
        String alias = text(tree,"annuls_event_id",false);
        if (alias != null && alias.isBlank()) throw new IllegalArgumentException("Blank annuls_event_id");
        if (legacy != null && alias != null && !legacy.equals(alias))
            throw new IllegalArgumentException("Contradictory goal references");
        return alias == null ? legacy : alias;
    }
    private static String text(JsonNode tree, String field, boolean required) {
        var value = tree.get(field);
        if (value == null || value.isNull()) {
            if (required) throw new IllegalArgumentException("Missing field");
            return null;
        }
        if (!value.isTextual()) throw new IllegalArgumentException("Text required");
        return value.textValue();
    }
    private static String utf8(byte[] bytes) throws CharacterCodingException {
        return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
    }
}
