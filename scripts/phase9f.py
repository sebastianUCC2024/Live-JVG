#!/usr/bin/env python3
"""Isolated 9F preparation. prepare/validate/plan never invoke Docker or sockets.

execute requires a separately reviewed approval file; preparation grants no actions.
"""
import argparse
import base64
import hashlib
import json
import os
from pathlib import Path
import re
import secrets
import shutil
import subprocess
import sys
import time
import urllib.request
import uuid
import zipfile

ROOT = Path(__file__).resolve().parents[1]
PORTS = dict(AUDIT_POSTGRES_PORT=55449, AUDIT_KAFKA_PORT=29092, TEST_RABBIT_PORT=25672,
             AUDIT_RABBIT_MANAGEMENT_PORT=25673, AUDIT_DASHBOARD_PORT=28080, AUDIT_PRODUCER_PORT=28081)
FORBIDDEN_PORTS = {5432, 5672, 9092, 55434, 5673, 19092, 15673, 18080, 18081}
FLAGS = ("LIVE_JVG_INTEGRATION_APPROVED", "LIVE_JVG_OUTAGE_APPROVED",
         "MATCH_STATE_TEST_BROKERS", "MATCH_STATE_TEST_QUARANTINE", "LIVE_JVG_TOPOLOGY_FAILURE_APPROVED")
REQUIRES = {
    "infra-up": {"infra-up", "database-init"}, "apps-up": {"apps-up", "migrate"},
    "postgres-tests": {"postgres-tests", "migrate"}, "broker-tests": {"broker-tests", "migrate", "outage"},
    "F2": {"F2", "publish"}, "F4": {"F4", "migrate", "publish", "topology-failure"},
    "F5": {"F5"}, "cleanup": {"cleanup", "stop-owned"},
}
SERVICES = {"postgres", "kafka", "kafka-init", "rabbitmq", "producer", "dashboard", "match-state"}
MIGRATIONS = {
    "dashboard-backend": ["V1__dashboard_projection.sql", "V2__persistent_stream_epoch.sql"],
    "match-state-service": ["V1__durable_match_state.sql", "V2__quarantine_and_replay.sql", "V3__custody_integrity.sql"],
}


def fail(message):
    raise ValueError(message)


def sha(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def write_new(path, text, mode=0o600):
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, mode)
    with os.fdopen(fd, "w", encoding="utf-8", newline="\n") as stream:
        stream.write(text)


def private_directory(path):
    if os.name == "nt":
        account = os.environ.get("USERNAME", "")
        if not account:
            fail("Cannot establish private Windows ACL")
        domain = os.environ.get("USERDOMAIN")
        account = f"{domain}\\{account}" if domain else account
        result = subprocess.run(["icacls", str(path), "/inheritance:r", "/grant:r",
                                 account + ":(OI)(CI)F", "/grant:r", "*S-1-5-18:(OI)(CI)F"],
                                stdout=subprocess.PIPE, stderr=subprocess.STDOUT, check=False)
        if result.returncode:
            fail("Private ACL failed; no credentials created")
    else:
        path.chmod(0o700)


def read_env(path):
    result = {}
    for line in Path(path).read_text(encoding="utf-8").splitlines():
        if not line or line.startswith("#"):
            continue
        key, separator, value = line.partition("=")
        if not separator or not re.fullmatch(r"[A-Z][A-Z0-9_]*", key) or key in result or not value:
            fail("Malformed/duplicate isolation variable; values withheld")
        result[key] = value
    return result


def validate_env(env):
    run = env.get("AUDIT_RUN_ID", "")
    if not re.fullmatch(r"[a-f0-9]{12}", run) or env.get("AUDIT_PROJECT") != "live-jvg-9f-" + run:
        fail("Invalid isolated run/project")
    if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_.-]*", env.get("AUDIT_DOCKER_CONTEXT", "")):
        fail("Explicit Docker context required")
    if not env.get("AUDIT_DOCKER_ENDPOINT", "").startswith(("npipe:////./pipe/", "unix:///")):
        fail("Explicit local Docker endpoint required")
    try:
        ports = {key: int(env[key]) for key in PORTS}
    except (KeyError, ValueError):
        fail("All alternate ports are mandatory")
    if len(set(ports.values())) != len(ports) or any(p < 1024 or p > 65535 or p in FORBIDDEN_PORTS for p in ports.values()):
        fail("Ports must be distinct, alternate and non-MVP")
    expected = {"TEST_KAFKA_BOOTSTRAP_SERVERS": f"127.0.0.1:{ports['AUDIT_KAFKA_PORT']}",
                "TEST_RABBIT_HOST": "127.0.0.1", "TEST_RABBIT_USERNAME": "jvg9f_" + run,
                "TEST_RABBIT_VHOST": "9f_" + run, "MATCH_STATE_TEST_DB_USERNAME": "jvg9f_" + run,
                "DASHBOARD_TEST_DB_USERNAME": "jvg9f_" + run,
                "MATCH_STATE_TEST_POSTGRES_CONTAINER": env["AUDIT_PROJECT"] + "-postgres"}
    for key, suite in (("MATCH_STATE_TEST_DB_URL", "state"), ("MATCH_STATE_BROKER_DB_URL", "brokers"),
                      ("MATCH_STATE_QUARANTINE_DB_URL", "quarantine"), ("DASHBOARD_INTEGRATION_DB_URL", "dashboard")):
        expected[key] = f"jdbc:postgresql://127.0.0.1:{ports['AUDIT_POSTGRES_PORT']}/live_jvg_test_{run}_{suite}"
    for key, suffix in (("AUDIT_TOPIC", "match_events"), ("AUDIT_EXCHANGE", "updates"),
                        ("AUDIT_QUEUE", "dashboard"), ("AUDIT_MATCH_ID", "match")):
        expected[key] = f"9f_{run}_{suffix}"
    if any(env.get(key) != value for key, value in expected.items()):
        fail("Cross-run identity, endpoint or database mismatch")
    for key in ("MATCH_STATE_TEST_DB_PASSWORD", "DASHBOARD_TEST_DB_PASSWORD", "TEST_RABBIT_PASSWORD"):
        if not re.fullmatch(r"[a-f0-9]{64}", env.get(key, "")):
            fail("Fresh private test credentials required")
    if env["DASHBOARD_TEST_DB_PASSWORD"] != env["MATCH_STATE_TEST_DB_PASSWORD"] or env["TEST_RABBIT_PASSWORD"] == env["MATCH_STATE_TEST_DB_PASSWORD"]:
        fail("Test credential relationship invalid")
    if any(env.get(key) != "false" for key in FLAGS):
        fail("Prepared environment cannot grant execution approval")
    if not re.fullmatch(r"[A-Za-z0-9_-]{22}", env.get("AUDIT_KAFKA_CLUSTER_ID", "")):
        fail("Fresh KRaft cluster ID required")
    return ports


def artifact(root, relative):
    path = (root / relative).resolve()
    if not path.is_relative_to(root.resolve()) or not path.is_file() or path.suffix != ".jar":
        fail("Executable artifact must be an existing repository-relative JAR")
    with zipfile.ZipFile(path) as archive:
        names = archive.namelist()
        if "org/springframework/boot/loader/launch/JarLauncher.class" not in names or not any(n.startswith("BOOT-INF/classes/") for n in names) or not any(n.startswith("BOOT-INF/lib/") for n in names):
            fail("Thin/non-executable JAR refused")
    return {"path": path.relative_to(root.resolve()).as_posix(), "sha256": sha(path)}


def source_bundle(root):
    paths = ["tests/9f/compose.yml", "tests/9f/postgres/Dockerfile", "tests/9f/postgres/init-databases.sh", "scripts/phase9f.py"]
    for module, files in MIGRATIONS.items():
        paths.extend(f"services/{module}/src/main/resources/db/migration/{name}" for name in files)
    return {p: sha(root / p) for p in paths}


def prepare(root, context, endpoint, artifacts, protect=private_directory):
    run = secrets.token_hex(6)
    directory = root / ".9f" / run
    directory.mkdir(parents=True, exist_ok=False)
    protect(directory)  # Before generating/writing any secret.
    env = {key: str(value) for key, value in PORTS.items()}
    env.update(AUDIT_RUN_ID=run, AUDIT_PROJECT="live-jvg-9f-" + run,
               AUDIT_DOCKER_CONTEXT=context, AUDIT_DOCKER_ENDPOINT=endpoint,
               MATCH_STATE_TEST_DB_PASSWORD=secrets.token_hex(32), TEST_RABBIT_PASSWORD=secrets.token_hex(32),
               AUDIT_KAFKA_CLUSTER_ID=base64.urlsafe_b64encode(uuid.uuid4().bytes).decode().rstrip("="))
    env.update(DASHBOARD_TEST_DB_PASSWORD=env["MATCH_STATE_TEST_DB_PASSWORD"],
               TEST_KAFKA_BOOTSTRAP_SERVERS=f"127.0.0.1:{env['AUDIT_KAFKA_PORT']}", TEST_RABBIT_HOST="127.0.0.1",
               TEST_RABBIT_USERNAME="jvg9f_" + run, TEST_RABBIT_VHOST="9f_" + run,
               MATCH_STATE_TEST_DB_USERNAME="jvg9f_" + run, DASHBOARD_TEST_DB_USERNAME="jvg9f_" + run,
               MATCH_STATE_TEST_POSTGRES_CONTAINER=env["AUDIT_PROJECT"] + "-postgres")
    for key, suite in (("MATCH_STATE_TEST_DB_URL", "state"), ("MATCH_STATE_BROKER_DB_URL", "brokers"),
                      ("MATCH_STATE_QUARANTINE_DB_URL", "quarantine"), ("DASHBOARD_INTEGRATION_DB_URL", "dashboard")):
        env[key] = f"jdbc:postgresql://127.0.0.1:{env['AUDIT_POSTGRES_PORT']}/live_jvg_test_{run}_{suite}"
    for key, suffix in (("AUDIT_TOPIC", "match_events"), ("AUDIT_EXCHANGE", "updates"), ("AUDIT_QUEUE", "dashboard"), ("AUDIT_MATCH_ID", "match")):
        env[key] = f"9f_{run}_{suffix}"
    env.update({key: "false" for key in FLAGS})
    checked = {key: artifact(root, value) for key, value in artifacts.items()}
    env.update({key: item["path"] for key, item in checked.items()})
    validate_env(env)
    write_new(directory / ".env", "".join(f"{key}={value}\n" for key, value in sorted(env.items())))
    state = {"run_id": run, "project": env["AUDIT_PROJECT"], "context": context, "endpoint": endpoint,
             "config_sha256": sha(directory / ".env"), "artifacts": checked, "sources": source_bundle(root), "resources": []}
    write_new(directory / "run.json", json.dumps(state, indent=2))
    approval = {key: state[key] for key in ("run_id", "project", "context", "endpoint", "config_sha256")}
    approval["actions"] = []
    write_new(directory / "approval-template.json", json.dumps(approval, indent=2))
    return directory


def validate(root, directory):
    directory = Path(directory).resolve()
    if not directory.is_relative_to((root / ".9f").resolve()):
        fail("Run directory must belong to this repository's private .9f area")
    state = json.loads((directory / "run.json").read_text(encoding="utf-8"))
    env = read_env(directory / ".env")
    validate_env(env)
    if directory.name != env["AUDIT_RUN_ID"] or state["run_id"] != env["AUDIT_RUN_ID"] or state["project"] != env["AUDIT_PROJECT"] or state["context"] != env["AUDIT_DOCKER_CONTEXT"] or state["endpoint"] != env["AUDIT_DOCKER_ENDPOINT"]:
        fail("Run manifest identity mismatch")
    if sha(directory / ".env") != state["config_sha256"] or source_bundle(root) != state["sources"]:
        fail("Configuration/migration/source hashes changed; prepare a fresh run")
    for key, item in state["artifacts"].items():
        if env.get(key) != item["path"] or artifact(root, item["path"]) != item:
            fail("Executable artifact changed")
    return state, env


def approval_for(state, path, action):
    if action not in REQUIRES:
        fail("Unsupported action")
    if not path:
        fail("Separate reviewed approval file required; preparation is not authorization")
    approval = json.loads(Path(path).read_text(encoding="utf-8"))
    if any(approval.get(key) != state[key] for key in ("run_id", "project", "context", "endpoint", "config_sha256")):
        fail("Approval belongs to another run/configuration")
    if not REQUIRES[action].issubset(set(approval.get("actions", []))):
        fail("Required actions have not been approved")


def child_environment(env, integration=False, outage=False, topology=False):
    prefixes = ("AUDIT_", "TEST_", "SPRING_", "DB_", "POSTGRES_", "MVP_", "COMPOSE_", "MATCH_STATE_", "DASHBOARD_", "RABBITMQ_", "KAFKA_", "SIMULATOR_", "PRODUCER_", "VITE_", "LIVE_JVG_")
    forbidden = {"DOCKER_HOST", "DOCKER_CONTEXT", "DOCKER_TLS_VERIFY", "DOCKER_CERT_PATH", "JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "MAVEN_OPTS", "MAVEN_ARGS", "MATCH_ID", "HOME_TEAM_ID", "AWAY_TEAM_ID"}
    child = {k: v for k, v in os.environ.items() if not k.startswith(prefixes) and k not in forbidden and k not in env}
    child.update(env)
    child.update(LIVE_JVG_INTEGRATION_APPROVED=str(integration).lower(), LIVE_JVG_OUTAGE_APPROVED=str(outage).lower(),
                 LIVE_JVG_TOPOLOGY_FAILURE_APPROVED=str(topology).lower(), MATCH_STATE_TEST_BROKERS=str(outage).lower(), MATCH_STATE_TEST_QUARANTINE=str(outage).lower())
    return child


def redact(text, env):
    for key in ("MATCH_STATE_TEST_DB_PASSWORD", "DASHBOARD_TEST_DB_PASSWORD", "TEST_RABBIT_PASSWORD"):
        text = text.replace(env[key], "[REDACTED]")
    basic = base64.b64encode((env["TEST_RABBIT_USERNAME"] + ":" + env["TEST_RABBIT_PASSWORD"]).encode()).decode()
    return text.replace(basic, "[REDACTED]")


def invoke(root, directory, env, args, **flags):
    result = subprocess.run(args, cwd=root, env=child_environment(env, **flags), stdout=subprocess.PIPE,
                            stderr=subprocess.STDOUT, timeout=900, check=False)
    output = redact(result.stdout.decode("utf-8", errors="replace"), env)
    write_new(directory / ("evidence-" + uuid.uuid4().hex + ".log"), output)
    if result.returncode:
        fail("Command failed; inspect redacted evidence in the private run directory")
    return output.strip()


def compose_args(root, directory, state):
    return ["docker", "--context", state["context"], "compose", "-p", state["project"],
            "--env-file", str(directory / ".env"), "-f", str(root / "tests/9f/compose.yml")]


def resources(root, directory, state, env):
    rows = []
    for kind, listing, inspect in (("container", ["ps", "-aq"], ["inspect", "--type", "container"]),
                                   ("volume", ["volume", "ls", "-q"], ["volume", "inspect"]),
                                   ("network", ["network", "ls", "-q"], ["network", "inspect"])):
        ids = invoke(root, directory, env, ["docker", "--context", state["context"], *listing,
                     "--filter", "label=com.docker.compose.project=" + state["project"]]).splitlines()
        for identifier in ids:
            labels = json.loads(invoke(root, directory, env, ["docker", "--context", state["context"], *inspect,
                                "--format", "{{json .Config.Labels}}" if kind == "container" else "{{json .Labels}}", identifier]))
            if labels.get("com.docker.compose.project") != state["project"] or labels.get("live-jvg.audit.run") != state["run_id"]:
                fail("Foreign/mislabeled resource refused")
            if kind == "container" and labels.get("com.docker.compose.service") not in SERVICES:
                fail("Unknown service in isolated project")
            rows.append({"kind": kind, "id": identifier, "labels": labels})
    return sorted(rows, key=lambda x: (x["kind"], x["id"]))


def assert_owned(expected, actual):
    if not expected or expected != actual:
        fail("Resource inventory changed or missing; inspect ownership before proceeding")


def http_json(url, body=None):
    data = None if body is None else body.encode("utf-8")
    request = urllib.request.Request(url, data=data, headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(request, timeout=8) as response:
        return json.load(response)


def sporting_sequence(env):
    base = f"http://127.0.0.1:{env['AUDIT_DASHBOARD_PORT']}"
    match = env["AUDIT_MATCH_ID"]
    initial = http_json(base + "/api/matches/" + match)
    if (initial["home_score"], initial["away_score"], initial["last_event_id"]) != (0, 0, None):
        fail("F2 requires a fresh empty run match; never reuse prior fixtures")
    kinds = [("GOAL", "home", 1, 0, "CLEAR"), ("VAR_CHECK", None, 1, 0, "IN_PROGRESS"),
             ("GOAL_ANNULLED", None, 0, 0, "CLEAR"), ("MATCH_CONTINUES", None, 0, 0, "CLEAR"), ("GOAL", "away", 0, 1, "CLEAR")]
    evidence = []
    goal = None
    for index, (kind, team, home, away, status) in enumerate(kinds):
        event = f"9f_{env['AUDIT_RUN_ID']}_f2_{index}"
        body = json.dumps(dict(event_id=event, event_type=kind, team_id=team, minute=10,
                              timestamp="2026-11-15T00:00:00Z", reference_event_id=goal if kind in ("VAR_CHECK", "GOAL_ANNULLED") else None), separators=(",", ":"))
        reply = http_json(base + f"/api/matches/{match}/events", body)
        if reply.get("event_id") != event:
            fail("Publication identity changed")
        deadline = time.monotonic() + 45
        while time.monotonic() < deadline:
            view = http_json(base + "/api/matches/" + match)
            if view["last_event_id"] == event:
                break
            time.sleep(.2)
        else:
            fail("Event did not reach the durable projection")
        if (view["home_score"], view["away_score"], view["var_status"]) != (home, away, status):
            fail("Authoritative score/VAR mismatch")
        if index == 0:
            goal = event
            http_json(base + f"/api/matches/{match}/events", body)  # Byte-identical duplicate.
        if not http_json(base + f"/api/matches/{match}/events/{event}").get("processed"):
            fail("Identity confirmation missing")
        evidence.append({"event_id": event, "event_type": kind, "home": home, "away": away, "var": status})
    return evidence


def execute(root, directory, approval, action):
    state, env = validate(root, directory)
    approval_for(state, approval, action)  # Before any subprocess, socket or infrastructure access.
    endpoint = json.loads(invoke(root, directory, env, ["docker", "context", "inspect", state["context"], "--format", "{{json .Endpoints.docker.Host}} "]))
    if endpoint != state["endpoint"]:
        fail("Docker context endpoint changed")
    actual = resources(root, directory, state, env)
    compose = compose_args(root, directory, state)
    if action == "infra-up":
        if actual or state["resources"]:
            fail("Initial provisioning requires an unused project and volumes")
        for listing, forbidden in ((["volume", "ls", "--format", "{{.Name}}"], {state["project"]+"_"+v for v in ("pgdata","kafkadata","rabbitdata")}),
                                   (["network", "ls", "--format", "{{.Name}}"], {state["project"]+"_isolated"}),
                                   (["ps", "-a", "--format", "{{.Names}}"], {state["project"]+"-postgres",state["project"]+"-dashboard"})):
            existing=set(invoke(root,directory,env,["docker","--context",state["context"],*listing]).splitlines())
            if existing & forbidden:
                fail("Namespace collision, including unlabelled resources; provisioning refused")
        invoke(root, directory, env, compose + ["config", "--quiet"])
        invoke(root, directory, env, compose + ["up", "-d", "--build", "postgres", "kafka", "rabbitmq", "kafka-init"])
    else:
        assert_owned(state["resources"], actual)
        if action == "apps-up":
            invoke(root, directory, env, compose + ["--profile", "apps", "up", "-d", "--build", "producer", "dashboard", "match-state"])
        elif action in ("postgres-tests", "broker-tests", "F4"):
            if any(r["labels"].get("com.docker.compose.service") in {"producer", "dashboard", "match-state"} for r in actual):
                fail("JUnit infrastructure suites must run before Compose apps; avoid competing consumers")
            modules = ["match-state-service", "dashboard-backend"] if action != "broker-tests" else ["match-state-service"]
            for module in modules:
                maven=shutil.which("mvn")
                if not maven: fail("Maven is required")
                args = [maven, "-o", "-B", "-Dmaven.repo.local="+str(root/".m2/repository"), "-f", f"services/{module}/pom.xml", "-P" + ("integration-postgres" if action == "postgres-tests" else "integration-brokers")]
                if action == "F4":
                    args.append("-Dtest=" + ("ConcurrentRabbitOutboxIntegrationTest" if module == "match-state-service" else "IsolatedDlqIntegrationTest"))
                invoke(root, directory, env, args + ["test"], integration=True, outage=action == "broker-tests", topology=action == "F4")
        elif action == "F2":
            write_new(directory / ("F2-" + uuid.uuid4().hex + ".json"), json.dumps(sporting_sequence(env), indent=2))
        elif action == "F5":
            invoke(root, directory, env, ["node", "scripts/phase9f-ws.mjs", "--run-dir", str(directory), "--approval", str(approval), "--execute"])
        elif action == "cleanup":
            invoke(root, directory, env, compose + ["--profile", "apps", "down"])
            return  # No volume deletion; original ownership manifest retained for review.
    if action in ("infra-up", "apps-up"):
        state["resources"] = resources(root, directory, state, env)
        # Metadata only; no credentials. Retain previous revisions independently.
        write_new(directory / ("resources-" + uuid.uuid4().hex + ".json"), json.dumps(state["resources"], indent=2))
        (directory / "run.json").write_text(json.dumps(state, indent=2), encoding="utf-8")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)
    prep = sub.add_parser("prepare")
    prep.add_argument("--context", required=True); prep.add_argument("--endpoint", required=True)
    for option in ("producer-jar", "state-jar", "dashboard-jar"):
        prep.add_argument("--" + option, required=True)
    for name in ("validate", "plan", "execute"):
        part = sub.add_parser(name); part.add_argument("--run-dir", required=True, type=Path)
        if name == "execute":
            part.add_argument("--approval", required=True, type=Path); part.add_argument("--action", choices=REQUIRES, required=True)
    args = parser.parse_args()
    if args.command == "prepare":
        directory = prepare(ROOT, args.context, args.endpoint, {"AUDIT_PRODUCER_JAR": args.producer_jar,
                            "AUDIT_STATE_JAR": args.state_jar, "AUDIT_DASHBOARD_JAR": args.dashboard_jar})
        print(json.dumps({"run_dir": str(directory), "approved_actions": [], "credentials": "private; values withheld"}))
    elif args.command == "execute":
        execute(ROOT, args.run_dir.resolve(), args.approval, args.action)
        print("Approved action completed; review private redacted evidence")
    else:
        state, _ = validate(ROOT, args.run_dir)
        print(json.dumps({"run_id": state["run_id"], "project": state["project"], "validation": "PASS",
                          "resources_recorded": len(state["resources"]), "future_actions": {k: sorted(v) for k, v in REQUIRES.items()}}, indent=2))


if __name__ == "__main__":
    try:
        main()
    except (ValueError, OSError, KeyError, zipfile.BadZipFile, subprocess.SubprocessError):
        print("9F refused: configuration, approval, ownership or operation invalid; sensitive details withheld", file=sys.stderr)
        sys.exit(1)
