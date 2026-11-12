# Phase 3: Kafka producer

The wire format is PROVISIONAL and follows the current domain and
`match-event.schema.proposed.json`. The shared contract is empty and unchanged.
Definitive interoperability requires team approval of the shared contract and
consumer agreement, including DEV 3. No consumer implementation is assumed.

## Implementation

`KafkaMatchEventPublisher` is a Spring output adapter. It uses
`KafkaTemplate<String, String>` with an isolated copy of Boot's Jackson mapper,
snake_case fields, ISO 8601 timestamps and explicit null optional fields.
The domain has no Jackson, Spring or Kafka dependencies. JSON is serialized
once before passing the string to Kafka's StringSerializer (no double encoding
and no Java type headers). There is no business logic in the adapter.

The port now returns `CompletableFuture<Void>`: success means broker
acknowledgement under `acks=all`. Preparation/serialization errors complete with
`IllegalArgumentException`; immediate or asynchronous send failures complete with
`IllegalStateException`, preserving the cause. Callers must observe the future.
There were no existing callers to migrate. Logs record acknowledgement only after
success and avoid payloads, settings and exception messages that could contain
credentials. No manual retries or application-level exactly-once guarantee are
introduced; producer idempotence only covers Kafka producer retries.

No get/join/flush is used in production. KafkaTemplate.send can still briefly
block for metadata or buffer allocation up to Kafka's max.block.ms; asynchronous
completion does not eliminate that client behavior.

Spring Boot was aligned from 4.1.2 to 3.5.7 for the requested Boot 3.x / Java 17 /
JUnit 5 stack. Dependencies now use spring-kafka, starter-web and starter-test;
existing contract tests were adapted from Jackson 3 to Jackson 2. The obsolete
JUnit 5 domain-only profile was removed; all tests now use JUnit 5.

## Configuration

- `spring.kafka.bootstrap-servers=${KAFKA_BOOTSTRAP_SERVERS:localhost:9092}`
- `spring.kafka.producer.key-serializer=org.apache.kafka.common.serialization.StringSerializer`
- `spring.kafka.producer.value-serializer=org.apache.kafka.common.serialization.StringSerializer`
- `spring.kafka.producer.acks=all`
- `spring.kafka.producer.properties.enable.idempotence=true`
- `live-feed.kafka.topic=${MATCH_EVENTS_TOPIC:match_events}`

Provision the topic and choose partition count, replication factor and
min.insync.replicas with the infrastructure owner. Acks=all durability depends on
those broker settings. Same-match partition ordering relies on the matchId key
and stable partition count. No topic, broker or credential is provisioned here.

## Verification

Run from `services/live-feed-producer`:

```powershell
mvn clean test
mvn package
```

In the restricted Codex environment, Maven's default repository resolved to an
unwritable `C:\.m2`. Use `-Dmaven.repo.local=C:\Live-JVG\.m2\repository` if needed.
JUnit 5 and Mockito tests require no broker or Docker. They cover topic/key,
wire fields, goal team and annulment reference, pending and successful futures,
immediate/asynchronous broker failures, serialization failure, invalid input,
and domain independence. Schema tests are structural checks, not full JSON
Schema validation.

## Optional broker smoke test

With a reachable Kafka cluster, provision `match_events` using the existing
infrastructure process. Start a console consumer with keys printed, then use
JShell with the service runtime classpath or a local IDE scratch program to
load the Spring application context, obtain `MatchEventPublisher`, and call:

```java
publisher.publish(MatchEvent.create("manual-match-1", EventType.GOAL,
        "team-1", 42, Instant.now(), null)).get(15, TimeUnit.SECONDS);
```

Publish GOAL_ANNULLED referencing the first event's ID. Verify key
`manual-match-1`, JSON snake_case, RFC 3339 timestamp, preserved reference and
acknowledgement logs. With the broker unavailable, verify exceptional completion
and absence of acknowledgement logs; allow Kafka delivery timeout to expire
rather than interpreting the scratch program's timeout as delivery failure.
This is a manual optional check, not part of unit tests. No REST endpoint or
automatic match simulator was added.

## Pending review

DEV 3/team must approve the shared schema and confirm consumer field names,
null handling, event enums, reference semantics and timestamp parsing. Confirm
ownership of topic provisioning and broker availability with the team. Consumers
must define duplicate handling and validation of references; producer idempotence
does not replace those responsibilities. Review the Boot 3 alignment and run the
optional broker smoke test before deployment. Phase 4 is not implemented.

## Recorded verification (2026-11-12)

- `mvn -Dmaven.repo.local=C:\Live-JVG\.m2\repository clean test`: BUILD SUCCESS,
  43 tests, 0 failures, 0 errors, 0 skipped.
- `mvn -B -ntp -Dmaven.repo.local=C:\Live-JVG\.m2\repository package`: BUILD SUCCESS,
  reran all 43 tests successfully and produced the executable Spring Boot JAR.
- Initial verification was blocked by an unwritable default local Maven repository
  and restricted network access. A workspace-local cache and authorized dependency
  downloads resolved both environment issues. No test failures occurred.
- No real-broker smoke test was executed. Unit test acknowledgements are mocked.
