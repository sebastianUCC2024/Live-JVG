# Fase 3B.4 — isolated integration tests, prepared but NOT executed

No infrastructure action is authorized by this document. Normal Maven verify
only compiles the external tests; Surefire excludes their external-integration
tag and their *IT naming pattern is outside ordinary test discovery. Failsafe
exists only in the explicit notification-integration profile. Even direct test
selection cannot create fixtures without the separate runtime authorization gate.

## Audit of the existing 80 tests

| Existing suite | What it actually verifies |
|---|---|
| NotificationPolicyTest (5) | Pure policy plus a mocked repository for suppression |
| RabbitTopologyTest (5) | Declarable objects and mocked connection-factory settings, no broker |
| ConfirmTimeoutTest (6) | Local duration validation/conversion |
| ProcessingFailurePolicyTest (4) | Pure failure classification |
| ScoreUpdateListenerTest (17) | Mock store/channel/template, local confirm futures and bounded retries |
| HealthConfigurationTest (1) | Mock connection factories and listener registration |
| NotificationReadinessTest (10) | Mock JDBC connections, listener containers and simulated events |
| JdbcNotificationStoreTest (10) | Mock JdbcTemplate/transaction manager; SQL calls, not PostgreSQL execution |
| ScoreUpdateCodecTest (13) | Local JSON/domain validation |
| AlertControllerTest (5) | Mock repository and MockMvc; no network HTTP listener |
| NotificationBackendApplicationTests (2) | Inactive Spring context, no DataSource/Flyway/admin/consumer |
| SafetyConfigurationTest (2) | Local properties and CORS validation |

Existing doubles remain in place. The new PostgreSQL/Rabbit ITs use real clients
and the existing service/repository/listener code. MockMvc over the real repository
verifies the REST adapter, not a network HTTP deployment. Deliberate channel
interception injects a fault into a real channel; it does not emulate broker data.

## Mandatory provisioning and approval dependencies

DEV 1 must approve use/extension of an owned 9F run. No tests/9f file or main Compose
is modified here. Infrastructure must separately provision PostgreSQL 17 and
RabbitMQ under that run, with the actual Docker resource identities recorded.
The gate checks context/endpoint, project/run/purpose/service labels, running
containers, exact loopback port bindings, owned named volumes and the owned
internal network. Bind mounts, foreign networks/volumes and ambiguous resources
are refused. No fallback to native PostgreSQL or the MVP is supported.

Required exclusive resources:

- Database `live_jvg_test_<run>_notification`, no existing unrelated schema/data.
- Dedicated DB user `jvg9f_<run>_notification`; authorized test credentials supplied
  privately, never generated here. Test role may migrate/write only this database.
- Infrastructure-created ownership marker `public.notification_test_owner` with
  exactly one TEXT run_id row equal to the approved 12-hex run. Tests only read
  this marker; they do not create it. Provisioning it requires separate approval.
- Schema `notification` and separate Flyway history in that exclusive database.
- Dedicated Rabbit user `jvg9f_<run>_notification`, vhost `9f_<run>_notification`.
- Exchange `9f_<run>_notification_updates`; queue prefix
  `notification.9f_<run>.integration`. Each test gets an unpredictable suffix;
  its DLX/DLQ append `.dlx`/`.dlq`. These prefixes must be approved explicitly.
- Match prefix `9f_<run>_notification_`, with run-owned generated fixture IDs.

This dedicated vhost is not the original 9F vhost. Infrastructure must explicitly
approve/provision it in the same broker; credentials scoped to another vhost are
not sufficient. Running dashboard against it would require another approved
coordination step. Do not create a second Docker stack to avoid this dependency.

## Explicit environment (no .env auto-loading)

Use only the JVG_NOTIFICATION_IT_ namespace below. Clear all inherited
NOTIFICATION_*, SPRING_*, DB_*, RABBITMQ_*, PG* and DOCKER_* variables plus
JAVA_TOOL_OPTIONS/JDK_JAVA_OPTIONS in the isolated test shell. The gate refuses
them rather than silently inheriting or overriding values. Do not clear the
operator's global environment; use a separate child shell/process.

| JVG_NOTIFICATION_IT_ suffix | Required value |
|---|---|
| APPROVED | exactly true, only after reviewed authorization |
| RUN_ID | approved 12 lowercase hexadecimal characters |
| DOCKER_CONTEXT / DOCKER_ENDPOINT | explicit local context and npipe/unix Docker endpoint |
| DB_PORT / RABBIT_PORT | distinct alternate approved loopback ports; native/MVP defaults rejected |
| DB_URL | jdbc:postgresql://127.0.0.1:<DB_PORT>/live_jvg_test_<run>_notification, no extra options |
| DB_USERNAME / DB_PASSWORD | dedicated DB user / private authorized password |
| RABBIT_USERNAME / RABBIT_PASSWORD | dedicated Rabbit user / independent private authorized password |
| RABBIT_VHOST | 9f_<run>_notification |
| EXCHANGE | 9f_<run>_notification_updates |
| QUEUE_PREFIX | notification.9f_<run>.integration |
| APPROVAL_FILE | real path inside this repository's private .9f/<run> directory |

Passwords must be distinct, at least 32 characters and not known placeholders.
This is a validation rule, not permission to invent credentials. Passwords, tokens,
auth headers and credential-bearing URLs never belong in arguments, approval JSON,
Git, logs or evidence. The private manifest records only non-secret scoped role
identities so the actual connected user can be verified.

## Run-bound approval manifest

The private JSON approval must contain the exact values for run_id, project,
docker_context, docker_endpoint, database_url, database_user, database_port,
rabbit_user, rabbit_port, vhost, exchange, queue_prefix, match_prefix and
postgres_container. postgres_container must be live-jvg-9f-<run>-postgres;
Rabbit's actual name is resolved by unique owned project/service labels.

Also required:

- approved_by_dev1=true and approved_by_infrastructure=true as JSON booleans;
- expires_at, an unexpired UTC/offset Instant;
- postgres_container_id and rabbitmq_container_id, exact full 64-hex Docker IDs;
- migration_sha256, source_bundle_sha256, compiled_bundle_sha256, jar_sha256;
- explicit actions, not inherited flags from another 9F action.

PostgreSQL suite actions: database-read, database-write, migrate, restart-probe.
Rabbit suite actions: database-read, database-write, migrate, declare, consume,
publish, channel-failure, topology-failure. Tests do not grant themselves actions.
An authorization capability is created only after all offline/ownership checks;
fixtures require it and verify their required actions before connecting.

## Local preparation and future commands

The following local build compiles all IT sources and runs ONLY local tests:

```powershell
mvn -f services/notification-backend/pom.xml verify
```

After the build, generate non-secret fingerprints with JDK 17. This tool only reads
local files; it neither authorizes a run nor creates a manifest or credentials:

```powershell
Set-Location D:\Universidad\Live-JVG\services\notification-backend
java --source 17 src/test/java/com/live_jgv/notification/integration/IntegrationFingerprints.java .
```

Source digest covers pom.xml, src/main and src/test. Compiled digest covers
target/classes and target/test-classes; artifact/migration digests cover the exact
JAR/V1 bytes. Paths are sorted, encoded with forward slashes, then hashed as
relative-path UTF-8 + NUL + file bytes + NUL for every file. Any change requires
recompilation, new fingerprints and reviewed approval.

**Future execution commands — NOT authorized or executed in this phase:**

```powershell
# From repository root, ONLY after provisioning, private env and approval review:
mvn -f services/notification-backend/pom.xml -Pnotification-integration -Dit.test=NotificationPostgresIT failsafe:integration-test failsafe:verify
mvn -f services/notification-backend/pom.xml -Pnotification-integration -Dit.test=NotificationRabbitIT failsafe:integration-test failsafe:verify
```

Use the reviewed, already compiled artifacts. These direct goals intentionally
avoid rebuilding/repackaging after approval, which can change fingerprints. The
Failsafe plugin must be available locally before execution. No other Maven profile
or existing 9F action implicitly approves these suites. Running the profile without
the explicit flag/complete manifest must fail before Docker/database/broker access.

## External scenarios prepared

NotificationPostgresIT (7 scenarios):

1. Flyway V1 migration/validation and repeat migration without clean.
2. Actual uniqueness, deduplication and conflicting payload rejection.
3. Real rollback after inbox insertion: an oversized internal alert violates the
   existing SQL CHECK; inbox, alert and cursor update must all roll back.
4. Sixteen concurrent receipts commit exactly one alert.
5. A separate owned JVM reads the same committed alert UUID. This tests durable
   persistence across a reader-process restart, not the whole Spring service lifecycle.
6. Incremental cursor pagination and REST adapter responses over the real repository.
7. A writer demonstrably blocked on the actual cursor row lock cannot be skipped
   by polling; the test observes its Lock wait in pg_stat_activity before proceeding.

NotificationRabbitIT (10 invocations):

1. GOAL / VAR_CHECK / GOAL_ANNULLED / MATCH_CONTINUES (four parameter cases): real
   delivery and commit checked from another DB connection before ACK.
2. Abort the underlying AMQP channel after commit/before ACK, then require genuine
   broker redelivered=true and the same persisted alert UUID after reprocessing.
3. Invalid JSON reaches confirmed DLQ before original ACK; next valid event works.
4. Remove only the unique test DLQ binding; unroutable transfer must retain input
   and request stop. Restore that binding and verify subsequent custody succeeds.
5. Same identity/same payload remains unique; changed content reaches DLQ without
   overwriting the original alert.
6. Publish to a nonexistent run-owned DLX to cause a real channel/confirmation
   failure; original input must remain queued and unacknowledged.
7. Stop only the owned listener container, queue another message, and start a new
   listener; require fresh ConsumeOk and committed processing.

All writes/declarations/faults are inside the exclusive approved run. There is no
Flyway clean, DROP DATABASE/SCHEMA, TRUNCATE, queue purge/delete, container command
or volume cleanup. Generated run queues persist for infrastructure review.

## Evidence, criteria and limitations

On authorized execution, JSONL evidence is written only below this module's
target/integration-evidence/<run>. Allowed fields cover event/alert IDs, row counts,
ACK/NACK, redelivery, ready/DLQ message counts, REST status/items/has_more,
elapsed time, result and exception class. Raw exception messages, environment,
authorization headers, passwords and credential values are not evidence fields.
Raw opaque REST cursors are not recorded. Failsafe alone selects the external
logging configuration, which disables library logs containing connection metadata;
the separate reader JVM uses the same configuration. The external JUnit extension
retains failures but replaces escaping exception messages/causes with a sanitized
type-only assertion. Failsafe XML must still remain private and be reviewed before sharing.

Success requires every selected assertion to pass with real clients and the
expected DB/queue identity. Any ownership, approval, expiry, resource, fingerprint,
credential, migration, deadline, count or identity mismatch fails the suite.
Do not convert refusal/failure into a skipped or successful infrastructure claim.

Broker-originated negative confirms and network-induced confirm timeouts are not
claimed by the missing-DLX/return scenarios. Those require additional approved
fault infrastructure. Full Kafka/Match State/dashboard/React delivery, container
restart, restore identity and shared LiveAlert approval remain outside this suite.
The SQL rollback case is a real constraint failure, not a PostgreSQL server outage.

## Safe stopping

Ctrl+C stops the owned test process; do not stop Docker or shared containers. The
suite closes its owned AMQP channels/connections and owned listener containers;
server-side unacknowledged deliveries should be redelivered on channel closure.
The reader probe is the only child JVM and is terminated on its timeout. JDBC
calls/AMQP RPC and waits are bounded. Preserve exclusive DB/queues and private
evidence for review; infrastructure decides later cleanup under separate approval.
After interruption, verify manifests/ownership again and seek fresh approval if
artifacts, context, container IDs, credentials or configuration changed.

DEV 1 still owns 9F coordination and source identity after restoration. The
notification cursor epoch cannot resolve reused source event identities.

## Local result in this phase

Temurin 17.0.21+9 / Maven 3.9.16: module-only offline `verify` completed with
**103 local tests, zero failures/errors/skips** (80 existing and 23 isolation/
diagnostic guard cases). Both external suites and their helpers compiled. The
17 external invocations were excluded, not executed and not counted as passing.
No Failsafe reports or external-run evidence directory was created.

The starting commit `7db4ece179d88f4f19f968bc58dc442fecd8289e` contains the prior
47 files. Of the 17 pending 3B.3 files, all 16 non-README hashes remained identical;
README only gained the documentation link. Files outside notification-backend
remain unchanged. `git diff --check` passed. No commit, push, merge, reset or stash.
Local verification evidence is `target/verify-3b4.log` and Surefire XML.
