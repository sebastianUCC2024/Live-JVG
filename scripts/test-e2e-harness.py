"""Offline safety/fixture checks. No Docker process is started."""
import importlib.util
from pathlib import Path
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('harness', Path(__file__).with_name('e2e-harness.py'))
harness = importlib.util.module_from_spec(spec)
spec.loader.exec_module(harness)


class HarnessTest(unittest.TestCase):
    def test_run_ids_cannot_inject_sql_paths_or_commands(self):
        for value in ['../../shared', "abc'; DROP TABLE x;--", 'ABCDEF123456', 'a'*13]:
            with self.assertRaises(ValueError):
                harness.scenarios(value)

    def test_v1_and_v2_expectations_are_never_confused(self):
        plan = harness.scenarios('abcdef123456')
        self.assertEqual('live-jvg-e2e-abcdef123456', plan['project'])
        self.assertEqual('CLEAR', plan['steps'][3]['var_status'])
        self.assertIn('IN_PROGRESS', plan['prepared_only'][0]['expected'])
        ids = [step['event']['event_id'] for step in plan['steps']]
        self.assertEqual(len(ids), len(set(ids)))
        self.assertEqual(ids[0], plan['steps'][4]['event']['reference_event_id'])

    def test_without_project_ack_preflight_refuses_before_any_command(self):
        runner = object.__new__(harness.Harness)
        runner.plan = harness.scenarios('abcdef123456')
        with patch.dict(harness.os.environ, {}, clear=True), patch.object(runner, 'call') as call:
            with self.assertRaises(ValueError):
                runner.preflight()
            call.assert_not_called()

    def test_acceptance_enabled_is_refused_before_any_publish(self):
        runner = object.__new__(harness.Harness)
        runner.plan = harness.scenarios('abcdef123456')
        runner.config = {'name': runner.plan['project'], 'services': {
            name: {'environment': {}} for name in harness.SERVICES + ['postgres', 'kafka', 'rabbitmq']}}
        runner.config['services']['worker']['environment'] = {
            'BETTING_WORKER_ACCEPTANCE_ENABLED': 'true', 'BETTING_WORKER_AUTHORIZATION_MODE': 'disabled'}
        with patch.dict(harness.os.environ, {'LIVE_JVG_E2E_APPROVED_PROJECT': runner.plan['project']}), \
                patch.object(runner, 'call', return_value='container-id'):
            with self.assertRaisesRegex(ValueError, 'Acceptance'):
                runner.preflight()

    def test_reused_volumes_are_refused(self):
        runner = object.__new__(harness.Harness)
        runner.plan = harness.scenarios('abcdef123456')
        runner.config = {'name': runner.plan['project'], 'volumes': {'data': {'name': 'dev3_lab_data'}}}
        with patch.dict(harness.os.environ, {'LIVE_JVG_E2E_APPROVED_PROJECT': runner.plan['project']}):
            with self.assertRaisesRegex(ValueError, 'reused'):
                runner.preflight()


if __name__ == '__main__':
    unittest.main()
