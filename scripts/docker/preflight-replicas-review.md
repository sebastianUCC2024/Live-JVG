# Review: academic Worker replicas and E2E preflight

Status: READY FOR OFFLINE REVIEW, not operational authorization.
Worktree: C:/Live-JVG/.worktrees/docker-academic-v1
Branch: dev1/docker-academic-v1
HEAD/base remains 257662cc6fb5c300e01e5ad4ad967566dd60af88; no new commit.

## Dependency and separate patch

The phase-aware harness is still on the separate, unintegrated commit
21eed1de1376a473a524b0528a4dac82e71f7e41. Its harness, tests and document were
materialized in this worktree and extended, without cherry-pick, merge, rewriting
that commit, or modifying its bundle. This worktree diff against integrated dev
therefore includes both that dependency and this replica extension.

The separately reviewable replica delta against the exact 21eed1d blobs is:
`tmp/replica-review/worker-replicas-on-21eed1d.patch` (ignored local artifact).
It contains only e2e-harness.py, test-e2e-harness.py and e2e/preflight-v1.md.
Forward git apply --check against temporary copies of the original blobs and
reverse --check against this worktree both pass. It does not modify Git refs.
Applying this patch requires the accompanying academic Compose/overlay changes;
the old one-Worker overlay is intentionally rejected by the resulting policy.
The patch does not by itself publish or integrate the 21eed1d dependency.

## Exact count and validation

- WORKER_REPLICAS=2 is an explicit reviewed policy constant, not derived from a
  user-controlled --scale value or arbitrary deploy.replicas declaration.
- Both passive and active models require an integer Worker deploy.replicas=2.
  Missing, zero, one, three, boolean and string values fail closed.
- Dashboard and every other inspected service require exactly 1 instance.
- Both Compose base and OFF overlay declare 2 Workers. All OFF control flags
  remain false/disabled; neither replica is activated by this offline change.
- Config-only passive validation remains command-free. execute-v1 still requires
  the active policy, project acknowledgement and actual runtime preflight; no
  partial ON state is inferred or enabled by choosing 2 replicas.

Runtime inspection uses Compose ps --all -q (includes stopped instances), requires
2 distinct IDs, then validates every inspect result. Required metadata: exact ID,
project/service labels, unique ordinal 1/2, running and not paused/restarting,
declared environment, allowed mounts/networks and matching peer image ID.
An explicit model image reference must also match Config.Image. Only after all
instances pass are the complete ID set and image identity recorded. Ordering may
change; count, ID replacement or image change may not. Failed validation does not
record partial success. Checks repeat before and after the existing restart.

## JVM compatibility

Compose declares no JAVA_OPTS/JAVA_TOOL_OPTIONS overrides. The image's fixed
JAVA_OPTS is accepted from inspected metadata; a different/empty/appended value
fails. Nonempty JAVA_TOOL_OPTIONS, JDK_JAVA_OPTIONS or _JAVA_OPTIONS fails.
Declared JAVA_ and JDK_ configuration overrides remain rejected even when their
text looks safe. The entrypoint independently enforces the same exact flags:
UseContainerSupport, MaxRAMPercentage=75.0, ExitOnOutOfMemoryError.
Dashboard remains passive-profile/catalog-OFF; acceptance stays false and authority
disabled for both Workers. No application code, contracts or leases are changed.

## Files changed in this task (in addition to prior Docker work)

- scripts/e2e-harness.py: 21eed1d phase dependency plus count and per-instance checks.
- scripts/test-e2e-harness.py: dependency suite plus replica/JVM/restart regressions.
- scripts/e2e/preflight-v1.md: dependency document plus replica extension notes.
- scripts/e2e/compose.manifest-proposed.yml: OFF Worker count now 2 instead of 1.
- services/match-state-service/src/test/java/com/live_jgv/matchstate/infrastructure/config/ComposeOffProposalTest.java:
  parsed YAML assertion now requires Worker=2 in both base and overlay.
- scripts/docker/academic-requirements.md: update compatibility and offline evidence.
- This review report.

## Executed offline validation

- python -B scripts/test-e2e-harness.py: 37 tests, all passed. Includes all 21eed1d
  phase/security/claim regressions, positive two-instance inspection, both-instance
  failures, count/duplicate/ordinal errors, stopped/paused/restarting instances,
  foreign labels, environment/mount/network/image/JVM changes, replacement, and
  failure after restart preventing the next barrier.
- python -B scripts/test-docker-runtime.py: 5 tests passed, java is a shell double.
- Maven offline -Dtest=ComposeOffProposalTest: 3 tests passed, zero failures/errors.
  Both YAMLs parsed; no Docker invocation, SQL or brokers.
- git diff --check: passed.
- Forward/reverse patch --check: passed without applying to the original branch.

## Remaining risks

This is not a Work Queue demonstration or real E2E certification. Competing
consumption, idempotency across real processes and resource enforcement remain
unverified. The first observed locally built image has no independently approved
digest: peer equality and subsequent stability do not certify its provenance.
Manifests, roles, migrations, topology and actual activation still need independent
approval. Notification's explicit RabbitAdmin remains an existing separate concern
when messaging is eventually activated. No Docker, SQL, brokers, application
services, consumer activation, commits, push, PR or merge were executed.


## Final frozen integration inventory

Functional scope is frozen. Final inventory relative to integrated base:

Modified (7):
- .dockerignore
- Dockerfile
- docker-compose.yml
- scripts/e2e-harness.py
- scripts/e2e/compose.manifest-proposed.yml
- scripts/test-e2e-harness.py
- services/match-state-service/src/test/java/com/live_jgv/matchstate/infrastructure/config/ComposeOffProposalTest.java

Added (6, not accidentally ignored):
- .gitattributes
- scripts/docker/academic-requirements.md
- scripts/docker/java-entrypoint.sh
- scripts/docker/preflight-replicas-review.md
- scripts/e2e/preflight-v1.md (provided first by the 21eed1d dependency)
- scripts/test-docker-runtime.py

No .worktrees/, tmp/, target reports, .env files or transfer bundles belong to the
integration inventory. Docker context also excludes .worktrees and tmp. The parent
clone still sees the nested worktree as untracked; never run a broad git add from
that parent. No changes to the original parent ignore/index files are needed here.

Two ignored handoff artifacts separate the dependency and application order:
- tmp/replica-review/docker-academic-without-preflight.patch: the 10 Docker/Compose,
  JVM, tests and review files, excluding the three 21eed1d preflight files.
- tmp/replica-review/worker-replicas-on-21eed1d.patch: only the three preflight files,
  relative to their exact 21eed1d blobs.

Recommended future integration, not executed against any branch:
1. Independently review and integrate 21eed1de1376a473a524b0528a4dac82e71f7e41.
2. Check the current target diff for subsequent changes. Do not replace whole files
   from this base worktree or accidentally resubmit the dependency as new work.
3. Apply/review the Docker-only delta, then the replica delta as one coherent change.
   Do not execute the intermediate state: the updated YAML count and replica policy
   must land together. Both patches must pass git apply --check on the real target.
4. On conflict, stop and resolve explicitly with owners, preserving all passive
   flags, profile restrictions, fixed JVM options, acceptance=false, authority=disabled,
   catalog/migration/provisioning OFF and Dashboard=1/Worker=2. No blanket ours/theirs,
   force, automatic conflict resolution or .rej files as accepted output.
5. Repeat the offline tests and git diff --check after integration resolution.
   Request authorization separately for commit/publication or any infrastructure.

Final checks repeated successfully: 37 harness tests, 5 shell-double runtime tests,
3 YAML tests, diff whitespace check, patch forward/reverse check and exact-output
comparison. An overlapping foreign edit is rejected and left unchanged. An unrelated
sentinel file remains byte-identical in the temporary integration rehearsal.
A temporary combined rehearsal applies both separated deltas after the exact
21eed1d dependency and compares every one of the 13 resulting files to this worktree.
All such rehearsal writes are confined to disposable directories, not real branches.
