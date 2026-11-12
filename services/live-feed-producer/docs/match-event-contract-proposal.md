# Match event contract proposal

Status: pending approval. This document does not change the shared contract.
The existing `contracts/events/match-event.schema.json` is empty.
Future transport adapters must map Java camelCase attributes to these snake_case
properties. No serialization adapter is implemented in this phase.

## Domain tests

Run `mvn -Pdomain-tests test` to execute only domain tests with JUnit 5.12.2.
Run `mvn clean test` for the full suite with Spring Boot's managed JUnit version.
Spring Framework 7's context test extension requires JUnit 6; downgrading the
entire suite to JUnit 5 causes a `NoSuchMethodError`. The domain profile uses
existing transitive test dependencies and adds no libraries. Both commands can
run offline with `-o` once their dependencies and plugins have been cached.

```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "title": "MatchEvent",
  "type": "object",
  "additionalProperties": false,
  "required": ["event_id", "match_id", "event_type", "minute", "timestamp"],
  "properties": {
    "event_id": { "$ref": "#/$defs/identifier" },
    "match_id": { "$ref": "#/$defs/identifier" },
    "event_type": {
      "enum": ["GOAL", "VAR_CHECK", "GOAL_ANNULLED", "MATCH_CONTINUES"]
    },
    "team_id": { "type": ["string", "null"] },
    "minute": { "type": "integer", "minimum": 0, "maximum": 130 },
    "timestamp": { "type": "string", "format": "date-time" },
    "reference_event_id": { "type": ["string", "null"] }
  },
  "$defs": {
    "identifier": { "type": "string", "minLength": 1, "pattern": "\\S" }
  },
  "allOf": [
    {
      "if": { "properties": { "event_type": { "const": "GOAL" } } },
      "then": {
        "required": ["team_id"],
        "properties": { "team_id": { "$ref": "#/$defs/identifier" } }
      }
    },
    {
      "if": { "properties": { "event_type": { "const": "GOAL_ANNULLED" } } },
      "then": {
        "required": ["reference_event_id"],
        "properties": { "reference_event_id": { "$ref": "#/$defs/identifier" } }
      }
    }
  ]
}
```

The 130-minute maximum is an initial documented domain limit, not runtime
configuration. A valid team means a nonblank identifier; membership in a match
requires application state and is outside this value object's responsibility.
Likewise, checking that a reference points to an existing goal in the same match
requires state and is outside this phase.

New events should use `MatchEvent.create`, which generates random UUIDs.
The canonical constructor also supports replaying events with their original IDs.
UUID collisions are practically negligible, not mathematically impossible;
global uniqueness and idempotency require consumer or storage coordination.

Review the optional null fields, VAR reference semantics, minute limit and UUID
policy before approving the shared contract. Configure date-time format
assertion in the future schema validator. Unicode whitespace rules may differ
between JSON Schema regular expressions and Java `String.isBlank()`.
