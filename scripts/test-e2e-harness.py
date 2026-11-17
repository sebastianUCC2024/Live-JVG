"""Offline safety/fixture checks. No Docker process is started."""
import importlib.util
from pathlib import Path
import unittest
import json
import tempfile
from types import SimpleNamespace
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('harness', Path(__file__).with_name('e2e-harness.py'))
harness = importlib.util.module_from_spec(spec)
spec.loader.exec_module(harness)


class HarnessTest(unittest.TestCase):
    def test_notification_environment_matches_ownership_and_override_contract(self):
        root = Path(__file__).resolve().parents[1]
        manifest = json.loads((root / 'scripts/e2e/live-jvg-e2e-5c84548cf006.proposed.json').read_text())
        spec = importlib.util.spec_from_file_location('proposal_environment', root / 'scripts/validate-e2e-proposal.py')
        proposal = importlib.util.module_from_spec(spec)
        with patch('subprocess.run', side_effect=AssertionError('Offline environment must not run commands')) as run:
            spec.loader.exec_module(proposal)
            values = proposal.environment(manifest)
            run.assert_not_called()
        queue = values['E2E_NOTIFICATION_QUEUE']
        self.assertEqual('notification.dev3_it_5c84548cf006', queue)
        # Same prefix and full-name allowlist as RabbitConfiguration.topology().
        self.assertTrue(queue.startswith('notification.'))
        self.assertRegex(queue, r'\A[A-Za-z0-9_.-]{1,200}\Z')
        self.assertEqual('e2e-5c84548cf006', values['E2E_MATCH_ID'])
        self.assertEqual(manifest['resources']['vhost'], values['E2E_VHOST'])
        self.assertEqual('/', values['E2E_VHOST'])
        self.assertEqual(manifest['resources']['exchange'], values['E2E_EXCHANGE'])
        self.assertEqual(manifest['resources']['queue'], values['E2E_WORKER_QUEUE'])
        self.assertEqual('dev3_it_5c84548cf006_dashboard', values['E2E_DASHBOARD_QUEUE'])
        override = (root / 'scripts/e2e/compose.manifest-proposed.yml').read_text().split('  notification:\n', 1)[1]
        for key, variable in [('NOTIFICATION_QUEUE', 'E2E_NOTIFICATION_QUEUE'),
                              ('NOTIFICATION_MATCH_IDS', 'E2E_MATCH_ID'),
                              ('SPRING_RABBITMQ_VIRTUAL_HOST', 'E2E_VHOST')]:
            self.assertIn(key + ': ${' + variable + '}', override)

    def manifest(self, run='abcdef123456'):
        prefix = 'dev3_it_' + run
        return dict(run=run, project='live-jvg-e2e-'+run, resources=dict(database=prefix+'_authority',
                    archiver_database=prefix+'_archive', topic=prefix+'_events', exchange=prefix+'_commands',
                    queue=prefix+'_worker', routing=prefix+'.command', vhost=prefix))

    def test_manifest_parameterizes_database_and_topic_without_using_existing_resources(self):
        runner = self.runner()
        manifest = self.manifest()
        runner.plan = harness.scenarios('abcdef123456', '2026-11-16T22:00:00Z', manifest)
        runner.config['services']['postgres']['environment']['POSTGRES_USER'] = 'isolated_admin'
        with patch.object(runner, 'call', return_value='') as call:
            runner.sql('SELECT 1')
            args = call.call_args.args[0]
            self.assertEqual(manifest['resources']['database'], args[args.index('-d') + 1])
            runner.publish_event(runner.plan['steps'][0]['event'])
            args = call.call_args.args[0]
            self.assertEqual(manifest['resources']['topic'], args[args.index('--topic') + 1])
        with patch.object(runner, 'sql', side_effect=['{"match":1}', '1']) as sql:
            observed = runner.observe()
            self.assertEqual(1, observed['archive'])
            self.assertEqual({'database': manifest['resources']['archiver_database']}, sql.call_args.kwargs)

    def test_manifest_rejects_foreign_names_and_identifier_injection(self):
        for key, value in [('database', 'live_jvg'), ('topic', 'dev3_it_other_events'),
                           ('database', "dev3_it_abcdef123456_x';--"), ('vhost', '/')]:
            manifest = self.manifest()
            manifest['resources'][key] = value
            with self.assertRaises(ValueError):
                harness.scenarios('abcdef123456', manifest=manifest)
        manifest = self.manifest()
        manifest['run'] = '5c84548cf006'
        with self.assertRaises(ValueError):
            harness.scenarios('abcdef123456', manifest=manifest)

    def test_proposed_manifest_matches_committed_dev3_generator_without_executing_sql(self):
        path = Path(__file__).with_name('e2e')
        spec = importlib.util.spec_from_file_location('review_manifest', path / 'prepare-review.py')
        generator = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(generator)
        proposed = json.loads((path / 'live-jvg-e2e-5c84548cf006.proposed.json').read_text())
        self.assertEqual(generator.prepare('5c84548cf006'), proposed)
        self.assertFalse(proposed['executed'])
        self.assertEqual('PROPOSED_NOT_APPROVED', proposed['status'])
        self.assertEqual('live_jvg', proposed['resources']['database'])
        self.assertEqual('live_jvg', proposed['resources']['archiver_database'])
        self.assertEqual('match_events', proposed['resources']['topic'])
        self.assertEqual('/', proposed['resources']['vhost'])
        standalone = generator.prepare('5c84548cf006', 'standalone')
        self.assertEqual('dev3_it_5c84548cf006_authority', standalone['resources']['database'])
        self.assertEqual('dev3_it_5c84548cf006_archive', standalone['resources']['archiver_database'])
        harness.resources_for('5c84548cf006', proposed)
        harness.resources_for('5c84548cf006', standalone)
        self.assertEqual('dev3_it_5c84548cf006_control_app', proposed['roles']['control_app'])
        self.assertFalse(proposed['safe_flags']['acceptance'])

    def test_plan_and_execution_claim_never_overwrite_previous_evidence(self):
        with tempfile.TemporaryDirectory(prefix='offline-harness-') as temporary:
            test_root = Path(temporary).resolve()
            args = ['e2e-harness.py', 'plan', '--run', 'abcdef123456', '--timestamp', '2026-11-16T22:00:00.120Z']
            with patch.object(harness, 'ROOT', test_root), patch('sys.argv', args):
                harness.main()
                plan_file = test_root / 'tmp/live-jvg-e2e-abcdef123456/plan.json'
                original = plan_file.read_bytes()
                with self.assertRaisesRegex(ValueError, 'Plan exists'):
                    harness.main()
                self.assertEqual(original, plan_file.read_bytes())
            args[1] = 'execute-v1'
            with patch.object(harness, 'ROOT', test_root), patch('sys.argv', args), \
                    patch.object(harness, 'Harness', side_effect=ValueError('preflight refused')) as constructor:
                with self.assertRaisesRegex(ValueError, 'preflight refused'):
                    harness.main()
                result_file = plan_file.with_name('result.json')
                result = result_file.read_bytes()
                self.assertEqual('FAILED', json.loads(result)['status'])
                with self.assertRaisesRegex(ValueError, 'Result exists'):
                    harness.main()
                self.assertEqual(1, constructor.call_count)
                self.assertEqual(original, plan_file.read_bytes())
                self.assertEqual(result, result_file.read_bytes())
                self.assertTrue(plan_file.with_name('execute.claim').exists())

    def test_committed_wire_fixture_matches_generated_reproducible_plan(self):
        fixture = json.loads(Path(__file__).with_name('e2e').joinpath('fixtures/v1-sequence.json').read_text())
        self.assertEqual(harness.scenarios('abcdef123456', '2026-11-16T22:00:00.120Z'), fixture)
    def runner(self):
        runner = object.__new__(harness.Harness)
        runner.plan = harness.scenarios('abcdef123456', '2026-11-16T22:00:00.120Z')
        runner.container_ids = {}
        runner.observations = []
        runner.config = {'name': runner.plan['project'], 'volumes': {}, 'networks': {}, 'services': {
            name: {'environment': {}} for name in harness.SERVICES + ['postgres', 'kafka', 'rabbitmq']}}
        env = {name: item['environment'] for name, item in runner.config['services'].items()}
        for name in ['producer', 'match-state', 'dashboard']:
            env[name]['MATCH_ID'] = runner.plan['match_id']
        for name, prefix in [('archiver', 'ARCHIVER'), ('suspension', 'BETTING_CONTROL'), ('worker', 'BETTING_WORKER')]:
            env[name][prefix + '_CONSUMER_ENABLED'] = 'true'
            env[name][prefix + '_DB_URL'] = 'jdbc:postgresql://postgres:5432/live_jvg'
        for name, key in [('match-state', 'MATCH_STATE_DB_URL'), ('dashboard', 'DASHBOARD_DB_URL'), ('notification', 'NOTIFICATION_DB_URL')]:
            env[name][key] = 'jdbc:postgresql://postgres:5432/live_jvg'
        for name in ['producer', 'match-state', 'archiver', 'suspension']:
            env[name]['KAFKA_BOOTSTRAP_SERVERS'] = 'kafka:9092'
        for name in ['match-state', 'dashboard', 'suspension', 'worker', 'notification']:
            prefix = 'NOTIFICATION_RABBIT_' if name == 'notification' else 'RABBITMQ_'
            env[name][prefix + 'HOST'] = 'rabbitmq'
            env[name][prefix + 'PORT'] = '5672'
        env['notification'].update(NOTIFICATION_STORAGE_ENABLED='true', NOTIFICATION_MESSAGING_ENABLED='true')
        env['worker'].update(BETTING_WORKER_ACCEPTANCE_ENABLED='false', BETTING_WORKER_AUTHORIZATION_MODE='disabled')
        return runner

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

    def test_fixed_timestamp_makes_plans_reproducible_and_late_event_stays_late(self):
        first = harness.scenarios('abcdef123456', '2026-11-16T22:00:00.120Z')
        self.assertEqual(first, harness.scenarios('abcdef123456', '2026-11-16T22:00:00.120Z'))
        self.assertLess(harness.instant_key(first['steps'][-2]['event']['timestamp']),
                        harness.instant_key(first['steps'][0]['event']['timestamp']))
        self.assertEqual('IN_PROGRESS', first['steps'][-2]['var_status'])
        self.assertIsNone(first['steps'][-1]['event']['reference_event_id'])

    def test_instant_comparison_preserves_nanoseconds_and_equivalent_spellings(self):
        self.assertEqual(harness.instant_key('2026-11-16T22:00:00.120Z'),
                         harness.instant_key('2026-11-16T17:00:00.12-05:00'))
        self.assertNotEqual(harness.instant_key('2026-11-16T22:00:00.000000001Z'),
                            harness.instant_key('2026-11-16T22:00:00Z'))
        with self.assertRaises(ValueError):
            harness.instant_key('2026-11-16T22:00:00')

    def test_external_databases_and_runtime_overrides_fail_before_container_access(self):
        for name, key, value in [('dashboard', 'DASHBOARD_DB_URL', 'jdbc:postgresql://shared:5432/live_jvg'),
                                 ('worker', 'SPRING_APPLICATION_JSON', '{"worker.acceptance.enabled":true}'),
                                 ('producer', 'KAFKA_BOOTSTRAP_SERVERS', 'shared:9092'),
                                 ('notification', 'NOTIFICATION_RABBIT_HOST', 'shared')]:
            runner = self.runner()
            runner.config['services'][name]['environment'][key] = value
            with patch.dict(harness.os.environ, {'LIVE_JVG_E2E_APPROVED_PROJECT': runner.plan['project']}), \
                    patch.object(runner, 'check_container') as check:
                with self.assertRaises(ValueError):
                    runner.preflight()
                check.assert_not_called()

    def test_reviewed_vhost_must_match_all_consumers_before_access(self):
        runner = self.runner()
        with patch.dict(harness.os.environ, {'LIVE_JVG_E2E_APPROVED_PROJECT': runner.plan['project'],
                                            'LIVE_JVG_E2E_RABBIT_VHOST': 'isolated'}), \
                patch.object(runner, 'check_container') as check:
            with self.assertRaisesRegex(ValueError, 'vhost'):
                runner.preflight()
            check.assert_not_called()
            for name in ['match-state', 'dashboard', 'suspension', 'worker', 'notification']:
                runner.config['services'][name]['environment']['SPRING_RABBITMQ_VIRTUAL_HOST'] = 'isolated'
            runner.plan['resources']['vhost'] = 'isolated'
            with patch.object(runner, 'sql'):
                runner.preflight()
            self.assertEqual(10, check.call_count)

    def inspected(self, runner, name):
        env = runner.config['services'][name]['environment']
        return {'Config': {'Labels': {'com.docker.compose.project': runner.plan['project'],
                                     'com.docker.compose.service': name},
                           'Env': [key + '=' + value for key, value in env.items()]},
                'State': {'Running': True}, 'Mounts': [], 'NetworkSettings': {'Networks': {}}}

    def test_actual_container_ownership_and_runtime_mismatch_are_refused(self):
        for change in ['project', 'environment', 'mount', 'network']:
            runner = self.runner()
            info = self.inspected(runner, 'worker')
            if change == 'project': info['Config']['Labels']['com.docker.compose.project'] = 'dev3-lab'
            if change == 'environment': info['Config']['Env'] = ['BETTING_WORKER_ACCEPTANCE_ENABLED=true']
            if change == 'mount': info['Mounts'] = [{'Type': 'volume', 'Name': 'dev3-lab-data'}]
            if change == 'network': info['NetworkSettings']['Networks'] = {'dev3-lab': {}}
            with patch.object(runner, 'call', return_value='id'), \
                    patch.object(harness.subprocess, 'run', return_value=SimpleNamespace(stdout=json.dumps([info]))):
                with self.assertRaises(ValueError):
                    runner.check_container('worker')

    def test_valid_running_container_is_recorded_and_replacement_is_refused(self):
        runner = self.runner()
        info = self.inspected(runner, 'worker')
        with patch.object(runner, 'call', return_value='id'), \
                patch.object(harness.subprocess, 'run', return_value=SimpleNamespace(stdout=json.dumps([info]))):
            runner.check_container('worker')
        self.assertEqual('id', runner.container_ids['worker'])
        with patch.object(runner, 'call', return_value='new-id'), \
                patch.object(harness.subprocess, 'run', return_value=SimpleNamespace(stdout=json.dumps([info]))):
            with self.assertRaisesRegex(ValueError, 'replaced'):
                runner.check_container('worker')

    def test_producer_response_accepts_java_instant_spelling_but_not_changed_payload(self):
        runner = self.runner()
        event = runner.plan['steps'][0]['event']
        response = dict(event, timestamp='2026-11-16T22:00:00.12Z')
        with patch.object(runner, 'call', return_value=json.dumps(response)):
            runner.publish_through_producer(event)
        response['event_id'] = 'other'
        with patch.object(runner, 'call', return_value=json.dumps(response)):
            with self.assertRaises(AssertionError):
                runner.publish_through_producer(event)

    def test_all_v1_steps_and_barriers_are_observed_before_and_after_restart(self):
        runner = self.runner()
        initial = dict.fromkeys(['match', 'dashboard', 'notification', 'archive', 'suspension', 'accepted'], 0)
        with patch.object(runner, 'preflight'), patch.object(runner, 'observe', return_value=initial), \
                patch.object(runner, 'publish_through_producer') as publish, \
                patch.object(runner, 'publish_event') as kafka, patch.object(runner, 'check_container'), \
                patch.object(runner, 'await_state', return_value={'observed': True}) as wait, \
                patch.object(runner, 'blocked_command', return_value='BLOCKED:STATE_UNAVAILABLE_OR_UNKNOWN'), \
                patch.object(runner, 'call') as call:
            result = runner.execute()
        count = len(runner.plan['steps'])
        self.assertEqual(count, publish.call_count)
        self.assertEqual([*range(1, count + 1), count + 1, count + 1, count + 2],
                         [item.args[0] for item in wait.call_args_list])
        self.assertEqual(3, kafka.call_count)
        call.assert_called_once_with(['restart', 'match-state', 'dashboard', 'notification', 'archiver', 'suspension', 'worker'])
        self.assertEqual('V1_OBSERVED', result['status'])


if __name__ == '__main__':
    unittest.main()
