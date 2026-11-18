"""Offline negative tests and source-contract checks. No infrastructure libraries."""
import ast
import copy
import json
from pathlib import Path
import re
import unittest
import prepare_common_v1 as proposal


class CommonV1ProposalTest(unittest.TestCase):
    def setUp(self):
        self.plan = proposal.build_plan("5c84548cf006")
        self.files = proposal.artifacts(self.plan)

    def approved_fixture(self):
        candidate = copy.deepcopy(self.plan)
        # Synthetic test attestation only: never passed to a database or broker.
        candidate["operator_approval"] = {"approved_by": "offline-test-double",
            "system_identifier": "1234567890123456789", "new_isolated_server": True,
            "exclusive_project": True, "adopt_empty_database": True, "postgres_major": 17}
        return candidate

    def test_accepts_complete_synthetic_attestation_without_connecting(self):
        proposal.validate_approval(self.approved_fixture(), self.plan)

    def test_proposal_without_operator_attestation_is_not_approval(self):
        with self.assertRaises(ValueError):
            proposal.validate_approval(self.plan, self.plan)

    def test_rejects_incomplete_attestation(self):
        for field in self.approved_fixture()["operator_approval"]:
            with self.subTest(field=field):
                candidate = self.approved_fixture()
                del candidate["operator_approval"][field]
                with self.assertRaises(ValueError): proposal.validate_approval(candidate, self.plan)

    def test_rejects_shared_or_unattested_resources(self):
        for field in ["new_isolated_server", "exclusive_project", "adopt_empty_database"]:
            with self.subTest(field=field):
                candidate = self.approved_fixture(); candidate["operator_approval"][field] = False
                with self.assertRaises(ValueError): proposal.validate_approval(candidate, self.plan)

    def test_rejects_standalone_database(self):
        candidate = self.approved_fixture(); candidate["database"] = "dev3_it_5c84548cf006_authority"
        with self.assertRaises(ValueError): proposal.validate_approval(candidate, self.plan)

    def test_rejects_wrong_host_ports_vhost_identity_and_source(self):
        for field, value in [("database_host", "db.company.example"), ("database_port", 5432),
                ("rabbit_port", 5672), ("rabbit_management_port", 15672), ("vhost", "production"),
                ("rabbit_user", "mvp"), ("base", "0" * 40), ("layout", "standalone")]:
            with self.subTest(field=field):
                candidate = self.approved_fixture(); candidate[field] = value
                with self.assertRaises(ValueError): proposal.validate_approval(candidate, self.plan)

    def test_rejects_enabled_or_wrongly_typed_flags(self):
        for field, value in [("consumers", True), ("acceptance", True), ("leases", True),
                ("notification_storage", True), ("publication", True), ("authority", "postgres-atomic"), ("consumers", 0)]:
            with self.subTest(field=field, value=value):
                candidate = self.approved_fixture(); candidate["flags"][field] = value
                with self.assertRaises(ValueError): proposal.validate_approval(candidate, self.plan)

    def test_rejects_missing_unknown_or_credential_fields(self):
        for field in ["password", "DB_PASSWORD", "token"]:
            candidate = self.approved_fixture(); candidate[field] = "not-a-real-secret"
            with self.assertRaises(ValueError): proposal.validate_approval(candidate, self.plan)
        candidate = self.approved_fixture(); del candidate["database_port"]
        with self.assertRaises(ValueError): proposal.validate_approval(candidate, self.plan)

    def test_rejects_wrong_server_fingerprint_or_version(self):
        for field, value in [("system_identifier", "unknown"), ("system_identifier", ""),
                ("postgres_major", 16), ("postgres_major", True), ("approved_by", " ")]:
            candidate = self.approved_fixture(); candidate["operator_approval"][field] = value
            with self.assertRaises(ValueError): proposal.validate_approval(candidate, self.plan)

    def test_rejects_injection_and_invalid_run_names(self):
        for run in ["ABCDEF123456", "../5c84548cf006", "x'; DROP ROLE x;--", "", "5c84548cf006 "]:
            with self.assertRaises(ValueError): proposal.build_plan(run)

    def test_six_schemas_seventeen_migrations_and_control_before_worker(self):
        steps = self.plan["migrations"]
        self.assertEqual(len(steps), 6)
        self.assertEqual(sum(len(step["files"]) for step in steps), 17)
        self.assertEqual([s["schema"] for s in steps],
            ["match_state", "dashboard", "notification", "archiver", "betting_control", "betting_worker"])
        for step in steps:
            self.assertFalse(step["create_schemas"])
            self.assertEqual(step["history"], step["schema"] + ".flyway_schema_history")
            self.assertTrue(step["init_sql"].startswith("SET ROLE "))
            self.assertEqual(len({step["owner"], step["migrator"], step["runtime"]}), 3)

    def test_data_inventory_matches_all_actual_create_table_statements(self):
        for step in self.plan["migrations"]:
            tables = set()
            for file in step["files"]:
                source = (proposal.ROOT / file["path"]).read_text(encoding="utf-8")
                tables.update(re.findall(r"CREATE TABLE (?:IF NOT EXISTS )?(?:\w+\.)?(\w+)\s*\(", source))
                self.assertRegex(file["sha256_utf8_lf"], r"^[a-f0-9]{64}$")
            self.assertEqual(tables, set(proposal.TABLES[step["schema"]]))

    def test_all_sql_refuses_missing_approval_cluster_or_marker_before_work(self):
        for name, sql in self.files.items():
            if not name.endswith(".sql"): continue
            with self.subTest(file=name):
                self.assertIn("Missing expected_system_identifier", sql)
                self.assertIn("pg_control_system()", sql)
                self.assertIn("current_database()='live_jvg'", sql)
                self.assertIn("no other application databases", sql)
                self.assertIn("no foreign schemas", sql)
                self.assertIn("\\quit 3", sql)
                if not name.startswith("10-"):
                    self.assertIn("database marker belongs to this run", sql)

    def test_provision_refuses_role_collision_and_owns_schemas_without_passwords(self):
        sql = self.files["10-provision.proposed.sql"]
        self.assertIn("unmarked fresh database cannot adopt existing run roles", sql)
        self.assertIn("runtime has no role memberships", sql)
        self.assertIn("owner cannot assume other roles", sql)
        self.assertEqual(sql.count("CREATE ROLE "), 19)
        self.assertEqual(sql.count("CREATE SCHEMA IF NOT EXISTS "), 6)
        self.assertNotRegex(sql, r"(?im)^CREATE DATABASE")
        self.assertNotRegex(sql, r"(?i)PASSWORD\s+'")

    def test_worker_owner_is_only_cross_schema_function_grantee(self):
        sql = self.files["20-cross-owner.proposed.sql"]
        self.assertIn("TO dev3_it_5c84548cf006_worker_owner", sql)
        self.assertNotIn(" TO dev3_it_5c84548cf006_worker_app", sql)

    def test_passive_grants_have_no_dml_or_sequence_use(self):
        sql = self.files["30-passive-grants.proposed.sql"]
        self.assertNotRegex(sql, r"(?im)^GRANT\s+(?:INSERT|UPDATE|DELETE|EXECUTE)")
        self.assertNotRegex(sql, r"(?im)^GRANT USAGE ON SEQUENCE")
        self.assertEqual(sql.count("GRANT SELECT ON "), 6)

    def test_fixtures_never_reset_epoch_score_or_cursor(self):
        sql = self.files["40-fixtures.proposed.sql"]
        self.assertEqual(len(re.findall(r"(?im)^INSERT INTO", sql)), 2)
        self.assertNotRegex(sql, r"(?im)^(UPDATE|DELETE|TRUNCATE|DROP|ALTER SEQUENCE)\b")
        self.assertNotIn("DO UPDATE", sql)
        self.assertIn("fresh concordant Dashboard catalog", sql)
        self.assertIn("empty fresh fixture betting_control.authorization_lease", sql)
        self.assertIn("empty fresh fixture betting_worker.operation_authorization", sql)

    def test_preflight_is_select_only_and_does_not_advance_sequences_or_lock_authority(self):
        sql = self.files["50-select-preflight.proposed.sql"]
        self.assertNotRegex(sql, r"(?im)^(INSERT|UPDATE|DELETE|TRUNCATE|DROP|ALTER|CREATE|GRANT|REVOKE|DO|CALL|SET|BEGIN)\b")
        self.assertNotRegex(sql, r"(?i)(nextval|setval|pg_advisory_lock|FOR UPDATE|\\gexec)")
        self.assertNotRegex(sql, r"FROM betting_control\.lock_market_authority\(")
        self.assertIn("last_value,is_called", sql)
        self.assertIn("no runtime authority writes", sql)
        self.assertIn("no unexpected migration versions", sql)

    def test_future_dml_cannot_issue_leases_or_forge_authorizations(self):
        sql = self.files["60-future-runtime-dml.proposed.sql"]
        self.assertIn("Separate runtime DML approval missing", sql)
        self.assertIn("blocked-only:5c84548cf006", sql)
        for line in sql.splitlines():
            if line.startswith("GRANT"):
                self.assertNotIn("authorization_lease", line)
                self.assertNotIn("authorization_authority", line)
                self.assertNotIn("operation_authorization", line)
                self.assertNotIn("EXECUTE", line)
                self.assertNotIn("DELETE", line)
                self.assertNotIn("TRUNCATE", line)
        self.assertNotIn("GRANT INSERT ON match_state.match_catalog", sql)
        self.assertNotRegex(sql, r"(?m)^GRANT.*replay_attempt")

    def test_notification_acl_is_exact_and_does_not_grant_dlq_consumption(self):
        rabbit = proposal.rabbit_plan(self.plan)
        q = rabbit["topology"]["queue"]
        self.assertTrue(q.startswith("notification."))
        self.assertEqual(rabbit["tags"], [])
        self.assertIsNone(rabbit["password"])
        self.assertNotEqual(rabbit["user"], "mvp")
        expected = {
            "configure": {"live_alerts", q, q + ".dlx", q + ".dlq"},
            "read": {"live_alerts", q, q + ".dlx"},
            "write": {q, q + ".dlx", q + ".dlq"},
        }
        for permission, names in expected.items():
            regex = rabbit["permissions"][permission]
            for name in expected["configure"] | {"", "production", "mvp-dashboard-scores", q + ".other", "prefix" + q}:
                self.assertEqual(bool(re.fullmatch(regex, name)), name in names, (permission, name))
        self.assertFalse(rabbit["topic_permissions"]["applicable"])
        self.assertEqual(rabbit["topology"]["type"],"fanout")
        self.assertEqual(rabbit["topology"]["binding"],"")

    def test_no_execution_or_network_modules_in_renderer(self):
        source = (proposal.HERE / "prepare_common_v1.py").read_text(encoding="utf-8")
        tree = ast.parse(source)
        imports = set()
        for node in ast.walk(tree):
            if isinstance(node, ast.Import): imports.update(x.name.split(".")[0] for x in node.names)
            if isinstance(node, ast.ImportFrom): imports.add(node.module.split(".")[0])
            if isinstance(node, ast.Call) and isinstance(node.func, ast.Name):
                self.assertNotIn(node.func.id, {"eval", "exec", "__import__"})
        self.assertTrue(imports <= {"argparse", "hashlib", "json", "pathlib", "re"})

    def test_preflight_checks_definer_owners_search_paths_and_deferred_triggers(self):
        sql = self.files["50-select-preflight.proposed.sql"]
        self.assertIn("p.prosecdef", sql)
        self.assertIn("p.proconfig @> ARRAY['search_path=pg_catalog']", sql)
        self.assertIn("tgdeferrable AND tginitdeferred AND tgenabled='O'", sql)
        self.assertIn("seqincrement=1 AND seqmin=1 AND NOT seqcycle", sql)
        self.assertIn("passive runtime has no DML", sql)

    def test_attestation_binds_proposal_code_tests_and_documentation(self):
        candidate = self.approved_fixture()
        candidate["proposal_sources"]["prepare_common_v1.py"] = "0" * 64
        with self.assertRaises(ValueError): proposal.validate_approval(candidate, self.plan)

    def test_academic_grants_never_write_operations_or_authority(self):
        text=self.files["70-academic-dml.proposed.sql"]
        self.assertIn("academic-only:",text)
        self.assertIn("GRANT UPDATE(delivery_attempts) ON betting_worker.academic_command",text)
        for line in text.splitlines():
            if line.startswith("GRANT"):
                self.assertNotIn("betting_worker.operation",line)
                self.assertNotIn("authorization_lease",line)
                self.assertNotIn("authorization_authority",line)
                self.assertNotIn("EXECUTE",line)

    def test_rendered_artifacts_match_review_files(self):
        for name, content in self.files.items():
            self.assertEqual((proposal.HERE / name).read_text(encoding="utf-8"), content, name)

    def test_review_files_have_no_trailing_whitespace(self):
        names = list(self.files) + ["prepare_common_v1.py", "test_prepare_common_v1.py", "README.md"]
        for name in names:
            content = (proposal.HERE / name).read_text(encoding="utf-8")
            self.assertTrue(content.endswith("\n"), name)
            for number, line in enumerate(content.splitlines(), 1):
                self.assertEqual(line, line.rstrip(), (name, number))


if __name__ == "__main__":
    unittest.main()
