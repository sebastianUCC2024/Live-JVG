# E2E V1 preflight: validation by phase

Base: `257662cc6fb5c300e01e5ad4ad967566dd60af88` (integrated PRs #32/#33).
Branch: `dev1/e2e-preflight-offline-v1`. No extra worktree.

## Reproduced defect

Before changing the harness, an offline runner with mocked container access
reproduced all four errors below. No subprocess or container was accessed:

```text
SPRING_SQL_INIT_MODE: Unreviewed runtime override refused: dashboard/SPRING_SQL_INIT_MODE
SPRING_KAFKA_ADMIN_AUTO_CREATE: Unreviewed runtime override refused: dashboard/SPRING_KAFKA_ADMIN_AUTO_CREATE
SPRING_RABBITMQ_LISTENER_DIRECT_AUTO_STARTUP: Unreviewed runtime override refused: dashboard/SPRING_RABBITMQ_LISTENER_DIRECT_AUTO_STARTUP
SPRING_PROFILES_ACTIVE: Unreviewed runtime override refused: dashboard/SPRING_PROFILES_ACTIVE
```

The old preflight also required consumers/storage ON, so accepting four additional
variable names alone would not make the OFF overlay valid or safe.

## Minimal correction

`validate_config(phase)` checks the resolved Compose model without commands or
connections. `preflight()` retains active validation, actual container identity,
environment, network/mount checks and storage reads for future authorized execution.
No application, Compose overlay, event contract or migration changes are included.

The phase must be explicit; it is never inferred from partially enabled flags.
Missing controls, different values and unknown Spring/JVM overrides are rejected.
Every Java service must retain empty startup dependencies. A present kafka-init
service must belong only to provisioning-explicit; absence from an inactive-profile
resolved model is permitted. Selecting that service operationally is not authorized.

| Controls | passive | active (V1 rehearsal model only) |
| --- | --- | --- |
| Flyway, RabbitAdmin auto-config, KafkaAdmin auto-create | false | false |
| SQL init | never | never |
| Rabbit direct listeners | false | false |
| Dashboard Spring profile | passive only | passive only |
| Match State / Dashboard catalog bootstrap | false | false |
| Dashboard simulator | false | false |
| Producer simulator | false | true |
| Match State recovery, publication, both Kafka startup flags | false | true |
| Dashboard Rabbit flag and simple listener | false | true |
| Archiver, Control and Worker consumer gates | false | true |
| Notification storage, messaging and simple listener | false | true |
| Other generic Kafka/simple listener flags | false | false |
| Control publication | false | false |
| Worker acceptance / authorization mode | false / disabled | false / disabled |

The active model deliberately keeps the Dashboard passive startup profile and
catalog OFF; explicit listener overrides permit the reviewed durable consumer.
DEV 3 consumers use their own readiness gates even while generic auto-startup is
false. Control publication/authority causality is outside this V1 rehearsal scope.

The existing project acknowledgement, internal URLs, topic/vhost/group/destination
checks, loopback ports, volume/network ownership, entrypoint/JVM restrictions and
actual running-container inspection remain enforced. Configuration validation
alone cannot attest to running containers or resources.

## Offline entrypoint

Given an already resolved, private Compose JSON file and the matching manifest:

```powershell
$env:LIVE_JVG_E2E_APPROVED_PROJECT = 'live-jvg-e2e-<run>'
python -B scripts/e2e-harness.py check-config --run <run> --manifest <manifest.json> --config-json <resolved.json> --phase passive
```

Use `--phase active` to validate a separately reviewed activation model. This mode
only reads JSON and prints CONFIG_VALIDATED_ONLY; it never invokes Docker, SQL,
HTTP, publication or restart, and creates no plan/claim/result files. It does not
produce the JSON or approve the resources. Protect the JSON: it may contain secrets.
`execute-v1` cannot use --phase/--config-json to bypass actual runtime inspection.

## Run identity and failure boundary

Plan identity and canonical V1 fixtures remain unchanged. A plan is still never
overwritten. Failed constructor/configuration/container/storage/freshness checks
occur before the exclusive execute.claim, so they create no result.json and do not
burn the run. Correcting those failures permits a retry with the same saved plan.

After preparation succeeds, exclusive creation of execute.claim still arbitrates
concurrent executors. A pre-existing claim is rejected before preparation. A claim
created by a competitor during preparation is never removed or overwritten.
Once claimed, any execution failure retains the claim and FAILED result; a retry
must use a new run. Existing failed artifacts are never automatically deleted or
reclassified, including failures created by the previous harness version.

## Offline evidence

Executed:

```powershell
python -B scripts/test-e2e-harness.py
git diff --check
```

Result: **29 tests passed**, including positive passive/active models, all policy
gates missing/unsafe (subtests), literal-control coverage against the committed
OFF overlay, foreign profiles/JVM overrides, dependency and provisioning-profile
checks, prior isolation/transport/container guards, CLI no-I/O checks, retryable
preflight failures, preserved results/claims and exclusive concurrent claims.
The canonical event fixture and V1 publication/barrier/restart expectations pass
using mocks. No additional test dependency is introduced.

## Remaining operational risks

- No Docker config/runtime, SQL, broker, application or real E2E execution was done.
- The current manifest remains PROPOSED_NOT_APPROVED; owners must still approve
  provisioning, migration order, grants, catalogs, topology and activation.
- Notification messaging ON creates an explicit RabbitAdmin in existing application
  code even with spring.rabbitmq.dynamic=false. Its declarations/permissions require
  separate owner approval; this harness change does not disable that application bean.
- Model validation is not an administrative security boundary. It verifies declared
  settings; runtime inspection remains mandatory before future publication/restart.
- Configuration-only success does not prove an empty database, readiness, effective
  broker permissions, UI/STOMP behavior or E2E certification.
- No acceptance, authority or leases were enabled; no push, PR or merge was performed.
