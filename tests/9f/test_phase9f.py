"""Local tests only: temporary fixtures and mocked processes; never Docker/network."""
import importlib.util
import json
import os
from pathlib import Path
import shutil
import tempfile
import unittest
from unittest.mock import patch
import zipfile

REPO=Path(__file__).resolve().parents[2]
spec=importlib.util.spec_from_file_location("phase9f",REPO/"scripts/phase9f.py")
h=importlib.util.module_from_spec(spec)
spec.loader.exec_module(h)


class HarnessTest(unittest.TestCase):
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root=Path(self.tmp.name)
        paths=["tests/9f/compose.yml","tests/9f/postgres/Dockerfile","tests/9f/postgres/init-databases.sh","scripts/phase9f.py"]
        paths += [f"services/{m}/src/main/resources/db/migration/{n}" for m,files in h.MIGRATIONS.items() for n in files]
        for p in paths:
            target=self.root/p;target.parent.mkdir(parents=True,exist_ok=True);shutil.copyfile(REPO/p,target)
        jar=self.root/"target/fresh/app.jar";jar.parent.mkdir(parents=True)
        with zipfile.ZipFile(jar,"w") as z:
            z.writestr("org/springframework/boot/loader/launch/JarLauncher.class",b"fixture")
            z.writestr("BOOT-INF/classes/App.class",b"fixture")
            z.writestr("BOOT-INF/lib/dependency.jar",b"fixture")
        self.artifacts={k:"target/fresh/app.jar" for k in ("AUDIT_PRODUCER_JAR","AUDIT_STATE_JAR","AUDIT_DASHBOARD_JAR")}
        with patch.object(h.subprocess,"run",side_effect=AssertionError("No process permitted")):
            self.directory=h.prepare(self.root,"desktop-linux","npipe:////./pipe/dockerDesktopLinuxEngine",self.artifacts,protect=lambda p:None)
        self.state,self.env=h.validate(self.root,self.directory)

    def test_preparation_has_distinct_private_credentials_and_no_approval(self):
        self.assertNotEqual(self.env["TEST_RABBIT_PASSWORD"],self.env["MATCH_STATE_TEST_DB_PASSWORD"])
        self.assertEqual(64,len(self.env["TEST_RABBIT_PASSWORD"]))
        self.assertEqual([],json.loads((self.directory/"approval-template.json").read_text())["actions"])
        with patch.object(h.subprocess,"run",side_effect=AssertionError("No process permitted")):
            h.validate(self.root,self.directory)

    def test_missing_foreign_default_and_duplicate_ports_are_refused(self):
        for key in ("AUDIT_PROJECT","TEST_KAFKA_BOOTSTRAP_SERVERS","TEST_RABBIT_PORT","MATCH_STATE_TEST_DB_PASSWORD"):
            missing=dict(self.env);missing.pop(key)
            with self.assertRaises(ValueError):h.validate_env(missing)
        for key,value in (("AUDIT_POSTGRES_PORT","5432"),("TEST_RABBIT_PORT","5672"),("AUDIT_KAFKA_PORT","9092"),
                          ("AUDIT_PROJECT","live-jvg-mvp"),("TEST_RABBIT_VHOST","/"),("TEST_RABBIT_USERNAME","guest"),
                          ("AUDIT_PRODUCER_PORT",self.env["AUDIT_DASHBOARD_PORT"])):
            bad=dict(self.env);bad[key]=value
            with self.assertRaises(ValueError):h.validate_env(bad)

    def test_unapproved_execution_fails_before_process_or_socket(self):
        with patch.object(h.subprocess,"run",side_effect=AssertionError("No process permitted")),patch.object(h.urllib.request,"urlopen",side_effect=AssertionError("No socket permitted")):
            with self.assertRaises(ValueError):
                h.execute(self.root,self.directory,self.directory/"approval-template.json","infra-up")

    def test_configuration_or_migration_tampering_cannot_reuse_approval(self):
        migration=self.root/"services/dashboard-backend/src/main/resources/db/migration/V2__persistent_stream_epoch.sql"
        migration.write_text("changed",encoding="utf-8")
        with self.assertRaises(ValueError):h.validate(self.root,self.directory)

    def test_approval_is_run_bound_and_requires_specific_destructive_actions(self):
        approved={**{k:self.state[k] for k in ("run_id","project","context","endpoint","config_sha256")},"actions":["broker-tests","migrate"]}
        path=self.directory/"approval.json";path.write_text(json.dumps(approved))
        with self.assertRaises(ValueError):h.approval_for(self.state,path,"broker-tests")
        approved["actions"].append("outage");path.write_text(json.dumps(approved));h.approval_for(self.state,path,"broker-tests")
        approved["run_id"]="000000000000";path.write_text(json.dumps(approved))
        with self.assertRaises(ValueError):h.approval_for(self.state,path,"broker-tests")

    def test_inherited_production_overrides_are_removed(self):
        with patch.dict(os.environ,{"SPRING_FLYWAY_URL":"foreign","MVP_DB_PASSWORD":"foreign","DOCKER_HOST":"foreign","MAVEN_OPTS":"foreign"}):
            child=h.child_environment(self.env,integration=True)
        for key in ("SPRING_FLYWAY_URL","MVP_DB_PASSWORD","DOCKER_HOST","MAVEN_OPTS"):self.assertNotIn(key,child)
        self.assertEqual("true",child["LIVE_JVG_INTEGRATION_APPROVED"])
        self.assertEqual("false",child["LIVE_JVG_OUTAGE_APPROVED"])

    def test_evidence_redacts_passwords_and_never_reuses_resource_manifests(self):
        text="password="+self.env["TEST_RABBIT_PASSWORD"]
        self.assertNotIn(self.env["TEST_RABBIT_PASSWORD"],h.redact(text,self.env))
        for expected,actual in (([],[]),([{"id":"one"}],[{"id":"two"}])):
            with self.assertRaises(ValueError):h.assert_owned(expected,actual)


if __name__=="__main__":unittest.main()
