# Match event contract proposal

Status: pending approval. This document does not change the shared contract.
The existing `contracts/events/match-event.schema.json` is empty.
Future transport adapters must map Java camelCase attributes to these snake_case
properties. The Phase 3 Kafka adapter implements this provisional serialization format.

## Domain tests

Run `mvn clean test` for the full JUnit 5 suite, including domain and Kafka adapter
unit tests. No broker or Docker is required. Spring Boot 3.5 manages JUnit 5 and
Jackson 2 consistently; the former Boot 4 / JUnit 6 domain-only profile was removed.

The reviewable Draft 2020-12 schema is [match-event.schema.proposed.json](match-event.schema.proposed.json). This is the single proposal source; the shared contract remains unchanged.

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

The 0..130 interval is a provisional simulation restriction, not a universal football rule. For GOAL_ANNULLED, reference_event_id denotes the original GOAL. match-state-service will validate existence, event type, match ownership and prior annulment. Neither the domain nor JSON Schema queries Kafka or a database.

MatchEventContractTest checks structural consistency using the existing Jackson dependency: fields, enum, bounds, nullability, and conditional rules. It is not a full JSON Schema validator or a serialization test. If a nonempty shared contract is present, the test also checks that it matches the proposal. Enable date-time format assertion in a future Draft 2020-12 validator; format alone is an annotation by default.
