"""Offline common-v1 proposal renderer. Never connects, provisions or executes SQL."""
import argparse
import hashlib
import json
from pathlib import Path
import re

BASE = "257662cc6fb5c300e01e5ad4ad967566dd60af88"
ROOT = Path(__file__).resolve().parents[4]
HERE = Path(__file__).resolve().parent
MODULES = [
    ("match_state", "match-state-service", "match_state", ["V1__durable_match_state.sql", "V2__quarantine_and_replay.sql", "V3__custody_integrity.sql", "V4__live_alert_outbox.sql"]),
    ("dashboard", "dashboard-backend", "dashboard", ["V1__dashboard_projection.sql", "V2__persistent_stream_epoch.sql", "V3__source_application_sequence.sql"]),
    ("notification", "notification-backend", "notification", ["V1__notification_inbox_and_alerts.sql", "V2__live_alert_identity.sql"]),
    ("archive", "historical-archiver", "archiver", ["V1__archive_events.sql", "V2__match_scoped_identity.sql"]),
    ("control", "betting-suspension-service", "betting_control", ["V1__betting_control.sql", "V2__atomic_authority.sql", "V3__academic_commands.sql"]),
    ("worker", "betting-worker", "betting_worker", ["V1__simulated_operations.sql", "V2__transactional_authorization.sql", "V3__academic_commands.sql"]),
]
TABLES = {
    "match_state": ["match_catalog", "accepted_event", "score_outbox", "event_identity", "quarantine_case", "input_receipt", "replay_attempt", "alert_outbox"],
    "dashboard": ["matches", "inbox", "stream_epoch"],
    "notification": ["cursor_state", "inbox", "alerts", "live_alert_inbox"],
    "archiver": ["archived_event", "input_receipt"],
    "betting_control": ["market_state", "processed_event", "pending_review", "input_receipt", "closure_command", "transition_history", "suspension_outbox", "academic_outbox", "authorization_authority", "authorization_lease"],
    "betting_worker": ["market_guard", "command_inbox", "operation", "operation_transition", "operation_evidence", "message_audit", "academic_command", "academic_delivery", "operation_authorization"],
}
SEQUENCES = {"dashboard": "inbox_seq_seq", "archiver": "archived_event_seq_seq"}


def sql_text(value):
    return "'" + value.replace("'", "''") + "'"


def build_plan(run):
    if not re.fullmatch(r"[a-f0-9]{12}", run):
        raise ValueError("Exactly twelve lowercase hex digits required")
    prefix = "dev3_it_" + run
    steps = []
    for order, (key, module, schema, names) in enumerate(MODULES, 1):
        files = []
        for name in names:
            relative = f"services/{module}/src/main/resources/db/migration/{name}"
            # LF normalization matches repository text on Windows and Linux.
            content = (ROOT / relative).read_text(encoding="utf-8").replace("\r\n", "\n")
            files.append({"path": relative, "sha256_utf8_lf": hashlib.sha256(content.encode()).hexdigest()})
        steps.append({"order": order, "service": module, "schema": schema,
                      "owner": f"{prefix}_{key}_owner", "migrator": f"{prefix}_{key}_migrator",
                      "runtime": f"{prefix}_{key}_app", "history": f"{schema}.flyway_schema_history",
                      "init_sql": f"SET ROLE {prefix}_{key}_owner", "create_schemas": False, "files": files})
    return {"status": "PROPOSED_NOT_APPROVED", "executed": False, "layout": "common-v1", "base": BASE,
            "run": run, "project": "live-jvg-e2e-" + run, "database": "live_jvg",
            "db_owner": prefix + "_database_owner", "database_host": "127.0.0.1", "database_port": 55991,
            "rabbit_host": "127.0.0.1", "rabbit_port": 56991, "rabbit_management_port": 15991,
            "vhost": "/", "rabbit_user": "notification_e2e_" + run,
            "match": {"match_id": "e2e-" + run, "home_team_id": "home", "away_team_id": "away",
                      "home_name": "Atlético Local", "away_name": "Deportivo Visitante"},
            "migrations": steps,
            "proposal_sources": {name: hashlib.sha256((HERE / name).read_text(encoding="utf-8").encode()).hexdigest()
                                 for name in ["prepare_common_v1.py", "test_prepare_common_v1.py", "README.md"]},
            "flags": {"consumers": False, "publication": False, "acceptance": False,
                      "authority": "disabled", "leases": False, "notification_storage": False, "live_alerts": False, "alert_generation": False, "alert_publication": False},
            "operator_approval": None}


def validate_approval(candidate, expected):
    """Checks an operator attestation offline; does not prove live server identity."""
    if set(candidate) != set(expected):
        raise ValueError("Incomplete, unknown or secret-bearing fields refused")
    for key, value in expected.items():
        if key != "operator_approval" and json.dumps(candidate[key], sort_keys=True) != json.dumps(value, sort_keys=True):
            raise ValueError("Destination, inputs, source fingerprints or OFF flags differ: " + key)
    approval = candidate["operator_approval"]
    fields = {"approved_by", "system_identifier", "new_isolated_server", "exclusive_project", "adopt_empty_database", "postgres_major"}
    if not isinstance(approval, dict) or set(approval) != fields:
        raise ValueError("Explicit complete operator attestation required")
    if not isinstance(approval["approved_by"], str) or not approval["approved_by"].strip():
        raise ValueError("Named operator required")
    if not isinstance(approval["system_identifier"], str) or not re.fullmatch(r"[1-9][0-9]{15,19}", approval["system_identifier"]):
        raise ValueError("Operator-observed PostgreSQL system identifier required")
    if any(approval[key] is not True for key in ["new_isolated_server", "exclusive_project", "adopt_empty_database"]):
        raise ValueError("Shared, existing or unapproved server refused")
    if type(approval["postgres_major"]) is not int or approval["postgres_major"] != 17:
        raise ValueError("PostgreSQL 17 required")


def assertion(expression, label):
    return f"-- {label}\nSELECT ({expression}) AS gate_ok \\gset\n\\if :gate_ok\n\\else\n\\echo 'REFUSED: {label}'\n\\quit 3\n\\endif\n"


def guard(plan, marked=True):
    run = plan["run"]
    result = "-- PROPOSAL ONLY. DBA session, future approval required; never use production.\n\\set ON_ERROR_STOP on\n"
    for name in ["operator_approved_project", "expected_system_identifier", "adopt_empty_database"]:
        result += f"\\if :{{?{name}}}\n\\else\n\\echo 'Missing {name}; refusal before any DDL/DML'\n\\quit 3\n\\endif\n"
    result += assertion(f"current_database()='live_jvg' AND current_setting('server_version_num')::int BETWEEN 170000 AND 179999 AND :'operator_approved_project'={sql_text(plan['project'])} AND :'adopt_empty_database'='true' AND (SELECT system_identifier::text FROM pg_control_system())=:'expected_system_identifier'", "approved target and cluster identity")
    result += assertion("NOT EXISTS(SELECT 1 FROM pg_database WHERE NOT datistemplate AND datname NOT IN ('postgres','live_jvg'))", "no other application databases on server")
    schemas = ",".join(sql_text(s) for s in TABLES)
    result += assertion(f"NOT EXISTS(SELECT 1 FROM pg_namespace WHERE nspname !~ '^pg_' AND nspname NOT IN ('public','information_schema',{schemas}))", "no foreign schemas")
    marker = f"live-jvg/common-v1/{run}/"
    wanted = f"{sql_text(marker)} || :'expected_system_identifier'"
    existing = "obj_description((SELECT oid FROM pg_database WHERE datname=current_database()),'pg_database')"
    if marked:
        result += assertion(f"{existing} IS NOT DISTINCT FROM ({wanted})", "database marker belongs to this run")
    else:
        # The initial Compose-created DB may be adopted only if empty, explicitly attested,
        # on the operator-approved new cluster. An existing arbitrary comment is refused.
        empty = "NOT EXISTS(SELECT 1 FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname !~ '^pg_' AND n.nspname<>'information_schema') AND NOT EXISTS(SELECT 1 FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace WHERE n.nspname !~ '^pg_' AND n.nspname<>'information_schema') AND NOT EXISTS(SELECT 1 FROM pg_type t JOIN pg_namespace n ON n.oid=t.typnamespace WHERE n.nspname !~ '^pg_' AND n.nspname<>'information_schema')"
        result += assertion(f"({existing} IS NOT DISTINCT FROM ({wanted})) OR ({existing} IS NULL AND {empty})", "new empty database or exact previously provisioned run")
    return result


def provision_sql(plan):
    result = guard(plan, False)
    roles = [(plan["db_owner"], False)]
    for step in plan["migrations"]:
        roles.extend([(step["owner"], False), (step["migrator"], True), (step["runtime"], True)])
    result += assertion("obj_description((SELECT oid FROM pg_database WHERE datname=current_database()),'pg_database') IS NOT NULL OR NOT EXISTS(SELECT 1 FROM pg_roles WHERE rolname IN (" + ",".join(sql_text(name) for name, _ in roles) + "))", "unmarked fresh database cannot adopt existing run roles")
    result += assertion(f"(SELECT rolsuper FROM pg_roles WHERE rolname=current_user) AND (SELECT pg_get_userbyid(datdba) FROM pg_database WHERE datname=current_database()) = CASE WHEN obj_description((SELECT oid FROM pg_database WHERE datname=current_database()),'pg_database') IS NULL THEN current_user::text ELSE '{plan['db_owner']}' END", "DBA and approved database owner required")
    result += "BEGIN;\n"
    for name, login in roles:
        result += f"""DO $role$
BEGIN
 IF NOT EXISTS(SELECT 1 FROM pg_roles WHERE rolname='{name}') THEN
  CREATE ROLE {name} {'LOGIN' if login else 'NOLOGIN'} NOINHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;
 ELSE
  IF NOT EXISTS(SELECT 1 FROM pg_roles WHERE rolname='{name}' AND rolcanlogin={'TRUE' if login else 'FALSE'} AND NOT rolinherit AND NOT rolsuper AND NOT rolcreatedb AND NOT rolcreaterole AND NOT rolreplication AND NOT rolbypassrls) THEN
   RAISE EXCEPTION 'Existing role attributes differ; refuse automatic repair';
  END IF;
 END IF;
END $role$;
"""
        if name.endswith("_owner"):
            result += assertion(f"NOT EXISTS(SELECT 1 FROM pg_auth_members WHERE member=(SELECT oid FROM pg_roles WHERE rolname='{name}'))", "owner cannot assume other roles " + name)
    result += f"ALTER DATABASE live_jvg OWNER TO {plan['db_owner']};\nREVOKE ALL ON DATABASE live_jvg FROM PUBLIC;\nREVOKE CREATE ON SCHEMA public FROM PUBLIC;\n"
    logins = []
    for step in plan["migrations"]:
        schema, owner, migrator, runtime = (step[k] for k in ["schema", "owner", "migrator", "runtime"])
        logins += [migrator, runtime]
        result += f"CREATE SCHEMA IF NOT EXISTS {schema} AUTHORIZATION {owner};\n"
        result += assertion(f"(SELECT pg_get_userbyid(nspowner) FROM pg_namespace WHERE nspname='{schema}')='{owner}'", "schema owner matches " + schema)
        result += f"GRANT {owner} TO {migrator};\nGRANT USAGE ON SCHEMA {schema} TO {migrator};\n"
        result += f"ALTER DEFAULT PRIVILEGES FOR ROLE {owner} IN SCHEMA {schema} REVOKE EXECUTE ON FUNCTIONS FROM PUBLIC;\n"
        result += assertion(f"NOT EXISTS(SELECT 1 FROM pg_auth_members WHERE member=(SELECT oid FROM pg_roles WHERE rolname='{runtime}'))", "runtime has no role memberships " + schema)
        result += assertion(f"NOT EXISTS(SELECT 1 FROM pg_auth_members WHERE member=(SELECT oid FROM pg_roles WHERE rolname='{migrator}') AND roleid<>(SELECT oid FROM pg_roles WHERE rolname='{owner}'))", "migrator membership limited to its owner " + schema)
    result += "GRANT CONNECT ON DATABASE live_jvg TO " + ",".join(logins) + ";\n"
    result += f"SELECT format('COMMENT ON DATABASE live_jvg IS %L', 'live-jvg/common-v1/{plan['run']}/' || :'expected_system_identifier') \\gexec\n"
    return result + "COMMIT;\n-- Set LOGIN credentials privately, outside Git. No passwords were generated.\n"


def cross_owner_sql(plan):
    control = next(s for s in plan["migrations"] if s["schema"] == "betting_control")
    worker = next(s for s in plan["migrations"] if s["schema"] == "betting_worker")
    return guard(plan) + "BEGIN;\n" + f"GRANT USAGE ON SCHEMA betting_control TO {worker['owner']};\nGRANT EXECUTE ON FUNCTION betting_control.lock_market_authority(text) TO {worker['owner']};\nCOMMIT;\n-- Owner-only prerequisite after Control V2, before Worker V1/V2. No runtime authority grant.\n"


def passive_grants_sql(plan):
    result = guard(plan) + "BEGIN;\n"
    for step in plan["migrations"]:
        schema, runtime = step["schema"], step["runtime"]
        tables = ",".join(schema + "." + t for t in TABLES[schema])
        result += f"REVOKE ALL ON ALL TABLES IN SCHEMA {schema} FROM {runtime};\nREVOKE ALL ON ALL SEQUENCES IN SCHEMA {schema} FROM {runtime};\nREVOKE ALL ON ALL FUNCTIONS IN SCHEMA {schema} FROM {runtime};\nREVOKE CREATE ON SCHEMA {schema} FROM {runtime};\nGRANT USAGE ON SCHEMA {schema} TO {runtime};\nGRANT SELECT ON {tables} TO {runtime};\n"
    return result + "COMMIT;\n-- Runtime cannot issue leases, forge authorization, migrate or mutate passive fixtures.\n"


def future_dml_sql(plan):
    result = guard(plan) + "-- NOT FOR PASSIVE STARTUP. Separate operator authorization for a BLOCKED-only V1 rehearsal.\n"
    result += "\\if :{?runtime_dml_approved_run}\n\\else\n\\echo 'Separate runtime DML approval missing'\n\\quit 3\n\\endif\n"
    result += assertion(f":'runtime_dml_approved_run'='blocked-only:{plan['run']}'", "separate BLOCKED-only DML scope")
    result += "BEGIN;\n"
    grants = {
        "match_state": {"INSERT": ["accepted_event", "score_outbox", "event_identity", "quarantine_case", "input_receipt", "alert_outbox"], "UPDATE": ["match_catalog", "score_outbox", "event_identity", "input_receipt", "alert_outbox"]},
        "dashboard": {"INSERT": ["inbox"], "UPDATE(home_score,away_score,var_status,last_event_id,updated_at)": ["matches"]},
        "notification": {"INSERT": ["inbox", "alerts", "live_alert_inbox"], "UPDATE(last_sequence)": ["cursor_state"]},
        "archiver": {"INSERT": ["archived_event", "input_receipt"], "UPDATE": ["input_receipt"]},
        "betting_control": {"INSERT": ["market_state", "processed_event", "pending_review", "input_receipt", "closure_command", "transition_history", "suspension_outbox"], "UPDATE": ["market_state", "pending_review", "input_receipt", "suspension_outbox"]},
        "betting_worker": {"INSERT": ["market_guard", "command_inbox", "operation", "operation_transition", "operation_evidence", "message_audit"], "UPDATE": ["market_guard", "command_inbox", "operation", "operation_evidence"]},
    }
    for step in plan["migrations"]:
        schema, runtime = step["schema"], step["runtime"]
        for privilege, tables in grants[schema].items():
            result += f"GRANT {privilege} ON {','.join(schema+'.'+t for t in tables)} TO {runtime};\n"
        if schema in SEQUENCES:
            result += f"GRANT USAGE ON SEQUENCE {schema}.{SEQUENCES[schema]} TO {runtime};\n"
    return result + "COMMIT;\n-- No grants on authorization_lease/authorization_authority or operation_authorization writes.\n-- No runtime EXECUTE on authority functions. Acceptance OFF and authorization disabled remain mandatory.\n"


def academic_dml_sql(plan):
    """Prepared only, separate authority from the legacy BLOCKED-operation rehearsal."""
    result=guard(plan)+"-- ACADEMIC ONLY; separate authorization, no real operation or grant writes.\n"
    result+="\\if :{?academic_dml_approved_run}\n\\else\n\\echo 'Separate academic DML approval required'\n\\quit 3\n\\endif\n"
    result+=assertion(f":'academic_dml_approved_run'='academic-only:{plan['run']}'","separate academic DML scope")
    roles={step['schema']:step['runtime'] for step in plan['migrations']}
    control,worker=roles['betting_control'],roles['betting_worker']
    result+="BEGIN;\n"
    result+=f"GRANT INSERT ON betting_control.academic_outbox TO {control};\nGRANT UPDATE(published_at) ON betting_control.academic_outbox TO {control};\n"
    result+=f"GRANT INSERT ON betting_worker.academic_command,betting_worker.academic_delivery TO {worker};\nGRANT UPDATE(delivery_attempts) ON betting_worker.academic_command TO {worker};\n"
    sports={
        "match_state":{"INSERT":["accepted_event","score_outbox","event_identity","quarantine_case","input_receipt","alert_outbox"],"UPDATE":["match_catalog","score_outbox","event_identity","input_receipt","alert_outbox"]},
        "dashboard":{"INSERT":["inbox"],"UPDATE(home_score,away_score,var_status,last_event_id,updated_at)":["matches"]},
        "notification":{"INSERT":["inbox","alerts","live_alert_inbox"],"UPDATE(last_sequence)":["cursor_state"]},
        "archiver":{"INSERT":["archived_event","input_receipt"],"UPDATE":["input_receipt"]},
        "betting_control":{"INSERT":["market_state","processed_event","pending_review","input_receipt","transition_history","suspension_outbox"],"UPDATE":["market_state","input_receipt"]}}
    for schema,actions in sports.items():
        for privilege,tables in actions.items():
            result+=f"GRANT {privilege} ON {','.join(schema+'.'+table for table in tables)} TO {roles[schema]};\n"
        if schema in SEQUENCES:result+=f"GRANT USAGE ON SEQUENCE {schema}.{SEQUENCES[schema]} TO {roles[schema]};\n"
    result+="COMMIT;\n-- Includes sports runtime grants; do NOT apply legacy 60 in the academic scenario. No Worker operation DML.\n"
    return result


def fixtures_sql(plan):
    m = plan["match"]
    match, home, away, hn, an = (sql_text(m[k]) for k in ["match_id", "home_team_id", "away_team_id", "home_name", "away_name"])
    result = guard(plan) + "BEGIN;\n"
    result += f"INSERT INTO match_state.match_catalog(match_id,home_team_id,away_team_id) VALUES ({match},{home},{away}) ON CONFLICT DO NOTHING;\n"
    result += f"INSERT INTO dashboard.matches(match_id,home_team_id,away_team_id,home_name,away_name) VALUES ({match},{home},{away},{hn},{an}) ON CONFLICT DO NOTHING;\n"
    result += assertion(f"(SELECT count(*) FROM match_state.match_catalog)=1 AND EXISTS(SELECT 1 FROM match_state.match_catalog WHERE match_id={match} AND home_team_id={home} AND away_team_id={away} AND revision=0 AND rules_version=1)", "fresh concordant Match State catalog")
    result += assertion(f"(SELECT count(*) FROM dashboard.matches)=1 AND EXISTS(SELECT 1 FROM dashboard.matches WHERE match_id={match} AND home_team_id={home} AND away_team_id={away} AND home_name={hn} AND away_name={an} AND home_score=0 AND away_score=0 AND var_status='CLEAR' AND last_event_id IS NULL AND updated_at IS NULL)", "fresh concordant Dashboard catalog")
    for schema, table in [(s,t) for s, names in TABLES.items() for t in names if t not in {"match_catalog", "matches", "stream_epoch", "cursor_state", "authorization_authority"}]:
        result += assertion(f"NOT EXISTS(SELECT 1 FROM {schema}.{table})", "empty fresh fixture " + schema + "." + table)
    for expression, label in singleton_checks():
        result += assertion(expression, label)
    return result + "COMMIT;\n-- Existing epochs are preserved. Never seed events, OPEN markets, leases or accepted operations.\n"


def singleton_checks():
    return [
        ("(SELECT count(*) FROM dashboard.stream_epoch)=1 AND EXISTS(SELECT 1 FROM dashboard.stream_epoch WHERE singleton=TRUE AND epoch IS NOT NULL)", "Dashboard epoch singleton"),
        ("(SELECT count(*) FROM notification.cursor_state)=1 AND EXISTS(SELECT 1 FROM notification.cursor_state WHERE singleton=TRUE AND epoch IS NOT NULL AND last_sequence=0)", "Notification fresh cursor singleton"),
        ("(SELECT count(*) FROM betting_control.authorization_authority)=1 AND EXISTS(SELECT 1 FROM betting_control.authorization_authority WHERE singleton=TRUE AND epoch IS NOT NULL)", "Control authority singleton without leases"),
    ]


def select_preflight_sql(plan):
    result = guard(plan)
    # Pure SELECT and psql control flow. Run with a DBA read-only connection;
    # no nextval/setval, advisory locks, authority functions or SELECT FOR UPDATE.
    for step in plan["migrations"]:
        schema, runtime = step["schema"], step["runtime"]
        for table in TABLES[schema]:
            name = schema + "." + table
            result += assertion(f"to_regclass('{name}') IS NOT NULL AND has_schema_privilege('{runtime}','{schema}','USAGE') AND has_table_privilege('{runtime}','{name}','SELECT')", "readable " + name)
            result += assertion(f"NOT has_any_column_privilege('{runtime}','{name}','INSERT') AND NOT has_any_column_privilege('{runtime}','{name}','UPDATE') AND NOT has_table_privilege('{runtime}','{name}','DELETE') AND NOT has_table_privilege('{runtime}','{name}','TRUNCATE')", "passive runtime has no DML " + name)
            result += f"SELECT * FROM {name} LIMIT 0;\n"
        result += assertion(f"NOT has_schema_privilege('{runtime}','{schema}','CREATE')", "runtime cannot create objects " + schema)
        result += f"SELECT installed_rank,version,description,checksum,success FROM {schema}.flyway_schema_history ORDER BY installed_rank;\n"
        for index, file in enumerate(step["files"], 1):
            result += assertion(f"EXISTS(SELECT 1 FROM {schema}.flyway_schema_history WHERE version='{index}' AND success) AND NOT EXISTS(SELECT 1 FROM {schema}.flyway_schema_history WHERE NOT success)", "migration history version " + schema + "." + str(index))
        versions = ",".join(sql_text(str(i)) for i in range(1, len(step["files"]) + 1))
        result += assertion(f"NOT EXISTS(SELECT 1 FROM {schema}.flyway_schema_history WHERE version IS NULL OR version NOT IN ({versions}))", "no unexpected migration versions " + schema)
    for expression, label in singleton_checks():
        result += assertion(expression, label)
    m = plan["match"]
    result += assertion(f"(SELECT count(*) FROM dashboard.matches)=1 AND (SELECT count(*) FROM match_state.match_catalog)=1 AND EXISTS(SELECT 1 FROM dashboard.matches d JOIN match_state.match_catalog c USING(match_id) WHERE d.match_id='{m['match_id']}' AND d.home_team_id=c.home_team_id AND d.away_team_id=c.away_team_id AND d.home_team_id='{m['home_team_id']}' AND d.away_team_id='{m['away_team_id']}' AND d.home_name={sql_text(m['home_name'])} AND d.away_name={sql_text(m['away_name'])} AND d.home_score=0 AND d.away_score=0 AND d.var_status='CLEAR' AND d.last_event_id IS NULL AND d.updated_at IS NULL AND c.revision=0 AND c.rules_version=1)", "concordant initial catalogs")
    for schema, sequence in SEQUENCES.items():
        table = "inbox" if schema == "dashboard" else "archived_event"
        result += assertion(f"pg_get_serial_sequence('{schema}.{table}','seq')='{schema}.{sequence}'", "serial ownership " + schema)
        result += assertion(f"EXISTS(SELECT 1 FROM pg_sequence WHERE seqrelid='{schema}.{sequence}'::regclass AND seqincrement=1 AND seqmin=1 AND NOT seqcycle)", "monotonic noncycling sequence " + schema)
        result += assertion(f"(SELECT s.last_value >= COALESCE((SELECT max(seq) FROM {schema}.{table}),0) AND (s.is_called OR s.last_value>COALESCE((SELECT max(seq) FROM {schema}.{table}),0)) FROM {schema}.{sequence} s)", "sequence will not reuse existing keys " + schema)
        result += f"SELECT last_value,is_called FROM {schema}.{sequence};\n"
    for schema, names in TABLES.items():
        for table in names:
            if table not in {"match_catalog", "matches", "stream_epoch", "cursor_state", "authorization_authority"}:
                result += assertion(f"NOT EXISTS(SELECT 1 FROM {schema}.{table})", "fresh baseline empty " + schema + "." + table)
    for schema, tables in {"betting_control": ["authorization_authority", "authorization_lease"], "betting_worker": ["operation_authorization"]}.items():
        runtime = next(s["runtime"] for s in plan["migrations"] if s["schema"] == schema)
        for table in tables:
            name = schema + "." + table
            result += assertion(f"NOT has_any_column_privilege('{runtime}','{name}','INSERT') AND NOT has_any_column_privilege('{runtime}','{name}','UPDATE') AND NOT has_table_privilege('{runtime}','{name}','DELETE') AND NOT has_table_privilege('{runtime}','{name}','TRUNCATE')", "no runtime authority writes " + name)
    worker = next(s["runtime"] for s in plan["migrations"] if s["schema"] == "betting_worker")
    result += assertion(f"NOT has_function_privilege('{worker}','betting_worker.record_atomic_authorization(text,text,bigint,uuid,uuid)','EXECUTE') AND NOT has_function_privilege('{worker}','betting_control.lock_market_authority(text)','EXECUTE')", "runtime authority functions remain denied")
    functions = {"betting_control": ["lock_market_authority"], "betting_worker": ["record_atomic_authorization", "require_transactional_authorization", "require_accepted_authorization"]}
    for schema, names in functions.items():
        owner = next(s["owner"] for s in plan["migrations"] if s["schema"] == schema)
        for name in names:
            result += assertion(f"(SELECT count(*) FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace WHERE n.nspname='{schema}' AND p.proname='{name}')=1 AND EXISTS(SELECT 1 FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace WHERE n.nspname='{schema}' AND p.proname='{name}' AND p.prosecdef AND pg_get_userbyid(p.proowner)='{owner}' AND p.proconfig @> ARRAY['search_path=pg_catalog'])", "SECURITY DEFINER owner and search path " + name)
    for table, trigger in [("operation", "accepted_requires_atomic_authorization"), ("operation_authorization", "authorization_requires_accepted_operation")]:
        result += assertion(f"EXISTS(SELECT 1 FROM pg_trigger WHERE tgrelid='betting_worker.{table}'::regclass AND tgname='{trigger}' AND tgdeferrable AND tginitdeferred AND tgenabled='O')", "enabled deferred trigger " + trigger)
    result += "SELECT n.nspname,p.proname,pg_get_userbyid(p.proowner) AS owner,p.prosecdef,p.proconfig FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace WHERE n.nspname IN ('betting_control','betting_worker');\n"
    result += "SELECT conname,condeferrable,condeferred FROM pg_constraint WHERE conrelid IN ('betting_worker.operation'::regclass,'betting_worker.operation_authorization'::regclass);\n"
    return result


def rabbit_plan(plan):
    q = "notification.dev3_it_" + plan["run"]
    escaped = re.escape(q)
    return {"status": "PROPOSED_NOT_APPROVED", "executed": False, "host": plan["rabbit_host"],
            "port": plan["rabbit_port"], "management_port": plan["rabbit_management_port"],
            "vhost": plan["vhost"], "user": plan["rabbit_user"], "tags": [], "password": None,
            "permissions": {"configure": "^(live_alerts|" + escaped + r"(\.dlx|\.dlq)?)$",
                            "read": "^(live_alerts|" + escaped + r"(\.dlx)?)$",
                            "write": "^" + escaped + r"(\.dlx|\.dlq)?$"},
            "topic_permissions": {"applicable": False, "reason": "Fanout and direct exchanges do not use topic permissions"},
            "topology": {"exchange": "live_alerts", "type": "fanout", "durable": True,
                         "queue": q, "binding": "",
                         "dlx": q + ".dlx", "dlx_type": "direct", "dlq": q + ".dlq", "recovery_key": "rejected"},
            "environment_proposal": {"NOTIFICATION_LIVE_ALERTS_ENABLED": "false", "NOTIFICATION_MESSAGING_ENABLED": "false", "NOTIFICATION_RABBIT_USERNAME": plan["rabbit_user"], "SPRING_RABBITMQ_VIRTUAL_HOST": plan["vhost"]},
            "requires_operator": ["attest a new exclusive Rabbit server/project and its instance identity; PostgreSQL identity is insufficient", "private password, scoped vhost permissions, no global grants or management tags", "separate DLQ operator; do not grant DLQ consumption to Notification", "explicit RabbitAdmin remains active when messaging is enabled, even with spring.rabbitmq.dynamic=false"],
            "flags": plan["flags"]}


def artifacts(plan):
    result = {
        "plan.proposed.json": json.dumps(plan, ensure_ascii=False, indent=2) + "\n",
        "10-provision.proposed.sql": provision_sql(plan),
        "20-cross-owner.proposed.sql": cross_owner_sql(plan),
        "30-passive-grants.proposed.sql": passive_grants_sql(plan),
        "40-fixtures.proposed.sql": fixtures_sql(plan),
        "50-select-preflight.proposed.sql": select_preflight_sql(plan),
        "60-future-runtime-dml.proposed.sql": future_dml_sql(plan),
        "70-academic-dml.proposed.sql": academic_dml_sql(plan),
        "notification-rabbit.proposed.json": json.dumps(rabbit_plan(plan), ensure_ascii=False, indent=2) + "\n",
    }
    checksums = {name: hashlib.sha256(content.encode()).hexdigest() for name, content in result.items()}
    checksums.update(plan["proposal_sources"])
    result["SHA256SUMS"] = "".join(f"{digest}  {name}\n" for name, digest in sorted(checksums.items()))
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--run", required=True)
    parser.add_argument("--output-dir", type=Path, default=HERE, help="Proposal text directory only; no SQL execution")
    commands = parser.add_mutually_exclusive_group(required=True)
    commands.add_argument("--render", action="store_true", help="Write review text only; never apply it")
    commands.add_argument("--check", action="store_true", help="Compare committed proposals with deterministic rendering")
    commands.add_argument("--validate-approval", type=Path, help="Read and validate private attestation offline only")
    args = parser.parse_args()
    plan = build_plan(args.run)
    if args.validate_approval:
        validate_approval(json.loads(args.validate_approval.read_text(encoding="utf-8-sig")), plan)
        print("Attestation structure accepted OFFLINE; no server identity or infrastructure verified")
        return
    if args.render: args.output_dir.mkdir(parents=True, exist_ok=True)
    for name, content in artifacts(plan).items():
        path = args.output_dir / name
        if args.check:
            if path.read_text(encoding="utf-8") != content:
                raise SystemExit("Proposal differs: " + name)
        else:
            with path.open("w", encoding="utf-8", newline="\n") as handle:
                handle.write(content)
    print("Proposal text " + ("checked" if args.check else "rendered") + "; no SQL or infrastructure executed")


if __name__ == "__main__":
    main()
