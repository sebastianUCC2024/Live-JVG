# Fase 3B.3 — notification integration proposal for DEV 1 approval

Proposal only. No 9F files, main Compose, shared schemas, databases or brokers were
modified. Existing 9F approval actions do not authorize notification resources.
Do not bypass that guard or create another Docker stack by default.

## Reuse and resource scope

Reuse the approved 9F PostgreSQL 17/RabbitMQ containers, project, internal network
and owned volumes for one identified run. Add a separate database
`live_jvg_test_<run>_notification` and schema `notification`, rather than using
native PostgreSQL or another service's database. Use a notification runtime role
limited to CONNECT, schema USAGE, SELECT/INSERT inbox+alerts and SELECT/UPDATE
cursor_state. A separately authorized migrator owns DDL; application runtime does
not create tables. All credentials must be supplied privately by infrastructure;
this proposal creates none.

Use the same run's approved Rabbit vhost and AUDIT_EXCHANGE so dashboard and
notifications receive copies of the same ScoreUpdate. Owned queue:
`notification.9f_<run>.score-updates`; DLX and DLQ append `.dlx` and `.dlq`, with
failure routing `rejected`. Bind only `score.${AUDIT_MATCH_ID}` (or a reviewed
explicit list), never dashboard's queue. Queue prefix validation intentionally
requires `notification.`; the main queue `mvp-dashboard-scores` is prohibited.

Notification REST should bind only to 127.0.0.1 on a run-specific configurable
port, e.g. candidate 28082 after conflict checks. The application can run as an
owned host JVM process; its PID, JAR hash, configuration and stop permission must
be added to the approved resource manifest. Container deployment, if preferred,
requires separate review of 9F service allowlists and ownership tracking. This
phase does not add such a service or alter its network/volumes.

## Allowed environment mapping

Construct an allowlisted child environment after removing **all** inherited
`NOTIFICATION_*` values. Also preserve 9F's existing removal of inherited SPRING_*,
DB_*, RABBITMQ_*, JAVA_TOOL_OPTIONS, JDK_JAVA_OPTIONS and other unsafe overrides.
No inherited default must silently enable consumers or redirect database writes.

| Variable | Approved run value |
|---|---|
| NOTIFICATION_STORAGE_ENABLED | false until migration/schema authorization, then true only for approved execution |
| NOTIFICATION_MESSAGING_ENABLED | false until topology/consumption authorization |
| NOTIFICATION_DB_URL | JDBC URL of the exclusive run notification database |
| NOTIFICATION_DB_USERNAME / NOTIFICATION_DB_PASSWORD | Private notification runtime role credentials |
| NOTIFICATION_RABBIT_HOST / NOTIFICATION_RABBIT_PORT | Exact loopback published endpoints of the approved 9F Rabbit container |
| NOTIFICATION_RABBIT_USERNAME / NOTIFICATION_RABBIT_PASSWORD | Private role scoped to run vhost/resources |
| SPRING_RABBITMQ_VIRTUAL_HOST | Exact TEST_RABBIT_VHOST; never fallback to `/` |
| NOTIFICATION_EXCHANGE | AUDIT_EXCHANGE of the same run |
| NOTIFICATION_QUEUE | notification.9f_<run>.score-updates |
| NOTIFICATION_MATCH_IDS | Approved AUDIT_MATCH_ID or explicit reviewed list |
| NOTIFICATION_BIND_ADDRESS | 127.0.0.1 |
| NOTIFICATION_PORT | Exclusive approved REST port, checked again before use |
| NOTIFICATION_MAX_ATTEMPTS | Reviewed bounded value, default 3 |
| NOTIFICATION_RETRY_INTERVAL_MS | Reviewed bounded value, default 1000 |
| NOTIFICATION_CONFIRM_TIMEOUT | Reviewed duration >=1ms, default 5s |
| NOTIFICATION_ALLOWED_ORIGINS | Empty unless exact frontend origins are separately approved |

Values outside this list require review. Disable migrations for the service;
there is no NOTIFICATION_* migration-enable flag. Explicitly prevent inherited
SPRING_FLYWAY_ENABLED or command-line overrides from enabling them accidentally.

## Migration and approval manifest

Fingerprint the local V1 migration
`src/main/resources/db/migration/V1__notification_inbox_and_alerts.sql` and its
Flyway locations/history schema. Add its hash to the 9F source/migration manifest
before approval, along with the executable notification JAR hash and relevant
configuration/test harness hashes. DEV 1 must approve changes to 9F's fixed
service, artifact, migration, action and port allowlists; do not silently execute
notification work through existing dashboard/state actions.

The run-bound approval must cover context and Docker endpoint, database name and
owner, role privileges, vhost, exchange, queue/bindings/DLX/DLQ, REST port, process
ownership and the exact non-secret configuration fingerprint. Secrets remain in
the private run store; redact logs and connection material from evidence.

Separate permissions are required for: database provisioning; Flyway migration
and validation; declaration/consumption; publication; intentional connection/
transaction/channel/topology failures; and stopping only owned processes or
resources. No volume deletion, shared native DB operations or main Compose changes.
Apply V1 in a separate authorized migrator step, validate it, then enable storage
and finally messaging. The service's startup performs only read-only schema checks.

## Real tests still pending

All 14 scenarios from 3B.2 remain pending: GOAL, VAR_CHECK, GOAL_ANNULLED,
suppressed MATCH_CONTINUES, duplicate, conflicting identity, restart, genuine
redelivery after commit-before-ACK interruption, PostgreSQL rollback, invalid JSON
in DLQ, failed DLQ custody, concurrent cursor pagination, independent dashboard/
notification copies, and absence of changes outside the owned run.

Add readiness checks for disabled storage, DB loss/recovery, missing/failed input
consumer, fresh ConsumeOk after reconnect, DLQ-triggered consumer stop and separate
liveness. Confirm submillisecond configuration is refused before activation.
Fault tests need deterministic barriers and exact ownership checks; random timing
or republishing the same payload is not proof of broker redelivery.

## Identity and approval dependencies

ScoreUpdate has no source epoch. Restoring/replacing DEV 1's origin can reuse
(match_id,event_id); the notification cursor epoch does not resolve that problem.
An unchanged notification epoch after DB restore may also leave some old cursors
undetectable. DEV 1 and infrastructure must approve identity, replay, restore and
epoch-rotation procedures. Do not invent a namespace change to bypass deduplication.

The team still owns shared LiveAlert approval, match scope and retention. No shared
schema or React delivery integration is changed or implicitly authorized here.

## Local verification completed in 3B.3

- Starting HEAD: `7db4ece179d88f4f19f968bc58dc442fecd8289e`, authorized branch
  `feature/Gabo-react-integration`, initially clean. That existing user commit
  contains the prior 47 files; they were not discarded or reset.
- Java Temurin 17.0.21+9 and Maven 3.9.16, configured only in the verification shell.
- Module-only Maven verify: **80 tests, zero failures/errors/skips**, comprising
  the previous 54 and 26 additional cases. No external services were required.
- Runtime defaults remained inactive; the Spring test context created no DataSource,
  Flyway, RabbitAdmin, Declarables or notification listener. Its readiness group
  reports OUT_OF_SERVICE while process liveness is UP.
- Executable JAR built with Build-Jdk-Spec 17; all 40 compiled-output entries matched
  BOOT-INF/classes byte-for-byte. No packaging mismatch.
- `git diff --check` passed. All files outside notification-backend remained unchanged
  relative to the starting commit. No commit/push/merge/reset/stash was performed.
- Local ignored evidence: `target/verify-3b3.log` and `target/surefire-reports`.

The module was verified with Maven offline using its already populated local cache:

```powershell
mvn -B -ntp -o -f services/notification-backend/pom.xml -Dmaven.repo.local=D:/Universidad/Live-JVG/services/notification-backend/target/maven-cache verify
git diff --check
```

This verifies code/configuration with doubles and an inactive Spring context only.
It does not certify PostgreSQL rollback/concurrency, RabbitMQ failures or real 9F
integration. No migration, broker publication, external consumer or real fault test
was executed.
