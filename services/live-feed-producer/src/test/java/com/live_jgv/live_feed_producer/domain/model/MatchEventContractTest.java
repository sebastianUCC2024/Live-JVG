package com.live_jgv.live_feed_producer.domain.model;

import org.junit.jupiter.api.Test;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/** Structural drift checks, not a general JSON Schema validation engine. */
class MatchEventContractTest {
    private static final Path PROPOSAL = Path.of("docs", "match-event.schema.proposed.json");

    private JsonNode schema() throws IOException {
        // After approval, check the shared contract too, rather than silently
        // continuing to test only the proposal.
        var mapper = JsonMapper.builder().build();
        var proposal = mapper.readTree(Files.readString(PROPOSAL));
        var shared = Path.of("..", "..", "contracts", "events", "match-event.schema.json");
        if (Files.exists(shared) && Files.size(shared) > 0) {
            assertEquals(proposal, mapper.readTree(Files.readString(shared)),
                    "Shared contract and proposal differ; reconcile their semantics");
        }
        return proposal;
    }

    private Set<String> strings(JsonNode array) {
        var values = new HashSet<String>();
        array.forEach(value -> values.add(value.asText()));
        return values;
    }

    @Test
    void matchesDomainTypesAndMinuteLimits() throws IOException {
        var schema = schema();
        assertEquals("https://json-schema.org/draft/2020-12/schema", schema.get("$schema").asText());
        assertEquals("object", schema.get("type").asText());
        var properties = schema.get("properties");
        assertEquals(Arrays.stream(EventType.values()).map(Enum::name).collect(Collectors.toSet()),
                strings(properties.get("event_type").get("enum")));
        assertEquals("integer", properties.get("minute").get("type").asText());
        assertEquals(MatchEvent.MIN_MINUTE, properties.get("minute").get("minimum").asInt());
        assertEquals(MatchEvent.MAX_MINUTE, properties.get("minute").get("maximum").asInt());
    }

    @Test
    void declaresRequiredFieldsAndRejectsUnknownFields() throws IOException {
        var schema = schema();
        assertEquals(Set.of("event_id", "match_id", "event_type", "minute", "timestamp"),
                strings(schema.get("required")));
        assertFalse(schema.get("additionalProperties").asBoolean());
        var properties = schema.get("properties");
        assertEquals(7, properties.size());
        for (String name : Set.of("event_id", "match_id", "event_type", "team_id",
                "minute", "timestamp", "reference_event_id")) {
            assertNotNull(properties.get(name), name);
        }
        assertEquals("string", properties.get("timestamp").get("type").asText());
        assertEquals("date-time", properties.get("timestamp").get("format").asText());
        for (String name : Set.of("event_id", "match_id")) {
            assertEquals("#/$defs/identifier", properties.get(name).get("$ref").asText());
        }
    }

    @Test
    void allowsOptionalNullFieldsAndRequiresConditionalIdentifiers() throws IOException {
        var schema = schema();
        for (String name : Set.of("team_id", "reference_event_id")) {
            assertEquals(Set.of("string", "null"), strings(schema.get("properties").get(name).get("type")));
        }
        var identifier = schema.get("$defs").get("identifier");
        assertEquals("string", identifier.get("type").asText());
        assertEquals(1, identifier.get("minLength").asInt());
        assertEquals("\\S", identifier.get("pattern").asText());
        var rules = schema.get("allOf");
        assertEquals(2, rules.size());
        for (var rule : rules) {
            String type = rule.get("if").get("properties").get("event_type").get("const").asText();
            String field = switch (EventType.valueOf(type)) {
                case GOAL -> "team_id";
                case GOAL_ANNULLED -> "reference_event_id";
                default -> throw new AssertionError("Unexpected conditional event type");
            };
            assertEquals(Set.of(field), strings(rule.get("then").get("required")));
            assertEquals("#/$defs/identifier",
                    rule.get("then").get("properties").get(field).get("$ref").asText());
        }
        assertEquals(Set.of("GOAL", "GOAL_ANNULLED"), Set.of(
                rules.get(0).get("if").get("properties").get("event_type").get("const").asText(),
                rules.get(1).get("if").get("properties").get("event_type").get("const").asText()));
    }
}
