# Development integration REST entry point

`POST /api/integration/events` accepts camelCase JSON and returns **204 No Content
only after Kafka acknowledges publication**. Invalid bodies return 400; publication
failures return 503 without broker details. Kafka output remains the existing
snake_case format produced by `KafkaMatchEventPublisher`.

The controller is absent by default. Both an active `dev` or `test` profile and
`live-feed.integration-rest.enabled=true` are required. For local development,
set these process environment variables before starting the producer:

```powershell
$env:SPRING_PROFILES_ACTIVE='dev'
$env:LIVE_FEED_INTEGRATION_REST_ENABLED='true'
$env:SERVER_ADDRESS='127.0.0.1'
$env:SERVER_PORT='8080'
$env:KAFKA_BOOTSTRAP_SERVERS='127.0.0.1:19092'
$env:MATCH_EVENTS_TOPIC='match_events'
```

This endpoint has no authentication and must never be enabled on a publicly
reachable deployment. Use the loopback binding above for local integration.
Do not activate dev/test profiles with this flag in production.

Example body:

```json
{
  "eventId": "goal-home-001",
  "matchId": "match-001",
  "eventType": "GOAL",
  "teamId": "team-home",
  "minute": 10,
  "timestamp": "2026-11-14T20:00:00.123456789Z",
  "referenceEventId": null
}
```

Required fields: eventId, matchId, eventType, minute (integer 0..130), timestamp
(ISO instant). GOAL additionally requires teamId; GOAL_ANNULLED requires
referenceEventId. VAR_CHECK and MATCH_CONTINUES may omit teamId. References are
supported for reviews and annulments; non-null optional identifiers must not be
blank. Types are GOAL, VAR_CHECK, GOAL_ANNULLED and MATCH_CONTINUES.

Caller eventId and timestamp are preserved as domain values; timestamps are
normalized by the existing adapter, so equivalent ISO representations need not
retain their original text. Reposting the same body publishes again with the
same identity and timestamp. The producer deliberately does not deduplicate;
match-state-service owns that behavior. Do not generate a fresh ID for a
duplicate test.

The application service delegates to MatchEventPublisher; it does not duplicate
Kafka serialization, retry or confirmation logic. A 204 acknowledges Kafka only,
not downstream state processing or browser delivery. A 503 or HTTP timeout does
not prove that Kafka did not accept the event; retry using the same identity.
The HTTP asynchronous timeout follows Spring MVC configuration; a timeout is not
a successful publication response.

The match and team IDs must already be registered in match-state-service.
Existence and ownership of referenced goals remain downstream domain checks.
No shared JSON schemas, consumer services or broker topology are changed.

Tests use MockMvc with a mocked publisher port and the existing Kafka adapter
tests use a mocked KafkaTemplate. No real broker is required by `clean verify`.
