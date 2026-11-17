# Academic Docker requirements: offline implementation proposal

Base: 257662cc6fb5c300e01e5ad4ad967566dd60af88.
Branch/worktree: dev1/docker-academic-v1 / .worktrees/docker-academic-v1.
No commit, push, PR, merge, container build or infrastructure execution is authorized.
The 21eed1d branch, commit and transfer bundle are unchanged.

## Image and packaging

The default runtime target uses maven:3.9.6-eclipse-temurin-17-alpine to build a
selected standalone module (SERVICE_MODULE). Its POM is copied before sources;
dependency:go-offline and verify share a BuildKit Maven cache. verify runs the
module's normal tests, without adding skip flags or external-integration profiles.
A later source change can reuse the descriptor dependency layer and Maven cache.

The full permitted repository context is copied after dependency preparation,
because existing tests read root Compose/fixtures and other committed resources.
The runtime copies only the repackaged *-SNAPSHOT.jar. Existing artifact names and
Spring Boot executable JAR packaging remain unchanged. Root Maven is not a reactor
for all seven services, so each Compose build selects its own SERVICE_MODULE.

The optional prebuilt target retains the original JAR_FILE argument for separately
verified host-built JARs. Compose uses the source-building default target; selecting
prebuilt is a separately reviewed build choice, not the default path. No host JDK,
Maven executable or cache is mounted into runtime containers.

Runtime is eclipse-temurin:17-jre-alpine, with curl for existing healthchecks and
UID/GID 10001:10001. Application files are read-only. Alpine uses apk, not apt.
The root build context excludes .worktrees, tmp/temp, logs and .9f in addition to
the existing secret/target exclusions. The new shell file is LF and covered by
.gitattributes. Dockerfile frontend/BuildKit support for cache mounts, COPY chmod
and multi-stage targets is required. Compose still requires >=2.24.4 for OFF tags.

## Controlled JVM options

JAVA_OPTS is exactly:

```
-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError
```

The entrypoint compares that literal and invokes java with fixed individual
arguments. It does not evaluate or split input. Missing JAVA_OPTS uses the same
safe default; empty, reordered, appended or changed values fail with exit 64.
Nonempty JAVA_TOOL_OPTIONS, JDK_JAVA_OPTIONS and _JAVA_OPTIONS are rejected.
Application arguments are rejected as well. No arbitrary JVM/agent/system-property
override is accepted. Administrative replacement of the image entrypoint remains
outside this guard; the E2E preflight rejects declared entrypoint/command overrides.

Compose does not declare JAVA_OPTS/JAVA_TOOL_OPTIONS, so the current strict
preflight need not allow new JVM environment keys. The safe image default is used.
No application gate, URL, credential, catalog, authority or lease configuration
was enabled by these changes.

## Proposed resource budgets (not professor-specified numbers)

All values below are conservative starting caps for a local demonstration, not
measured sizing or numeric requirements attributed to the academic statement.

| Service | CPUs / container | Memory / container |
| --- | --- | --- |
| producer | 0.5 | 384 MiB |
| dashboard, match-state, archiver, suspension, worker, notification | 0.5 each | 512 MiB each |
| postgres | 1.0 | 768 MiB |
| kafka | 1.0 | 1536 MiB |
| rabbitmq | 1.0 | 768 MiB |
| kafka-init (one-shot, separately authorized) | 0.5 | 512 MiB |

Limits are declared under deploy.resources.limits. Existing Java mem_limit values
remain equal to the new memory caps for local Compose compatibility. Kafka's heap
is explicitly 256 MiB initial / 768 MiB maximum to leave memory for native/broker
work within its cap. Java's 75 percent maximum heap also leaves native overhead,
which still needs measurement before any performance claims.
With two Workers the sum of long-running caps is 7040 MiB and 7 CPUs, plus the
one-shot initializer if separately selected; these are caps, not reservations.
Provisioning needs host memory beyond those caps for the OS, engine and image builds.
Enforcement by a particular Compose/engine installation has not been verified.

## Replicas and safe OFF compatibility

Base worker (betting-worker module) prepares deploy.replicas=2 for a future Work
Queue demonstration. Consumer and acceptance remain false, authority disabled,
and the integration-prepared profile remains required. No Worker is started here.
The OFF manifest overlay now retains exactly 2 Workers, with consumer and acceptance
false and authority disabled for both. Dashboard remains exactly one instance.
No worker container_name or host port prevents replication.

The base Compose is still an active local MVP configuration for other services;
it must never be treated as the passive environment. OFF runs must use the manifest
overlay, which retains passive flags, dependency resets and provisioning profiles.
Declaring 2 replicas is not evidence of Work Queue distribution or concurrent safety.

The accompanying preflight patch depends explicitly on 21eed1d. Its contents are
materialized in this worktree for offline review only; that original commit and
its bundle remain untouched. The patch enforces exactly 2 Workers in both phase
models, with every other service limited to 1, and inspects every returned ID.
See preflight-replicas-review.md for dependency, checks and remaining risks.

## Offline checks and practical limits

Executed, without Docker or SQL:

- python -B scripts/test-docker-runtime.py: 5 passed; POSIX sh executes a temporary
  java shell double, never a JVM/service. Positive argv and negative injection cases.
- python -B scripts/test-e2e-harness.py: 37 passed (21eed1d phase suite plus replica regressions).
- Maven offline match-state-service test: 121 passed, zero failures/errors/skips.
  Includes SnakeYAML parsing of both Compose files, build args/resource/replica
  checks and existing passive/invalid-startup tests. External integration excluded.
- git diff --check: passed.

Dockerfile inspection is static; no image pull, build, availability check of base
tags, Alpine runtime or Compose schema/engine validation was performed. No resource
limits were exercised. Existing tests verify a parsed YAML model, not the installed
Compose implementation. Whole-source builds of the other six modules in Maven
3.9.6 Alpine have not been executed. Build tools/base tags and dependency downloads
need network and later authorization. None of this constitutes real E2E certification.
