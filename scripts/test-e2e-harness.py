"""Offline safety/fixture checks. No Docker process is started."""
import importlib.util
from pathlib import Path
import unittest
import json
import re
import tempfile
from types import SimpleNamespace
from unittest.mock import patch, Mock

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

    def test_failed_preflight_can_retry_without_consuming_run_or_plan(self):
        with tempfile.TemporaryDirectory(prefix='offline-harness-') as temporary:
            test_root = Path(temporary).resolve()
            args = ['e2e-harness.py', 'plan', '--run', 'abcdef123456', '--timestamp', '2026-11-16T22:00:00.120Z']
            with patch.object(harness, 'ROOT', test_root), patch('sys.argv', args):
                harness.main()
                plan_file = test_root / 'tmp/live-jvg-e2e-abcdef123456/plan.json'
                original = plan_file.read_bytes()
                with self.assertRaisesRegex(ValueError, 'Plan exists'):
                    harness.main()
                args[1] = 'execute-v1'
                for stage in ['constructor', 'preparation']:
                    runner = Mock()
                    runner.prepare_execution.side_effect = ValueError('preflight refused')
                    with patch.object(harness, 'Harness', side_effect=ValueError('preflight refused')
                                      if stage == 'constructor' else None, return_value=runner) as constructor:
                        for _ in range(2):
                            with self.assertRaisesRegex(ValueError, 'preflight refused'):
                                harness.main()
                        self.assertEqual(2, constructor.call_count)
                        runner._execute_prepared.assert_not_called()
                    self.assertEqual(original, plan_file.read_bytes())
                    self.assertFalse(plan_file.with_name('execute.claim').exists())
                    self.assertFalse(plan_file.with_name('result.json').exists())

    def test_execution_failure_retains_one_shot_claim_and_evidence(self):
        with tempfile.TemporaryDirectory(prefix='offline-harness-') as temporary:
            root = Path(temporary)
            args = ['e2e-harness.py', 'execute-v1', '--run', 'abcdef123456']
            runner = Mock()
            runner._execute_prepared.side_effect = ValueError('after claim')
            runner.observations = []
            with patch.object(harness, 'ROOT', root), patch('sys.argv', args), \
                    patch.object(harness, 'Harness', return_value=runner) as constructor:
                with self.assertRaisesRegex(ValueError, 'after claim'):
                    harness.main()
                output = root / 'tmp/live-jvg-e2e-abcdef123456'
                result = (output / 'result.json').read_bytes()
                self.assertEqual('FAILED', json.loads(result)['status'])
                with self.assertRaisesRegex(ValueError, 'Result exists'):
                    harness.main()
                self.assertEqual(1, constructor.call_count)
                self.assertEqual(result, (output / 'result.json').read_bytes())
                self.assertTrue((output / 'execute.claim').exists())

    def test_concurrent_claim_is_exclusive_and_never_deleted(self):
        with tempfile.TemporaryDirectory(prefix='offline-harness-') as temporary:
            root = Path(temporary)
            claim = root / 'tmp/live-jvg-e2e-abcdef123456/execute.claim'
            runner = Mock()
            runner.prepare_execution.side_effect = lambda: claim.write_text('other executor')
            args = ['e2e-harness.py', 'execute-v1', '--run', 'abcdef123456']
            with patch.object(harness, 'ROOT', root), patch('sys.argv', args), \
                    patch.object(harness, 'Harness', return_value=runner):
                with self.assertRaises(FileExistsError):
                    harness.main()
                self.assertEqual('other executor', claim.read_text())
                runner._execute_prepared.assert_not_called()
                self.assertFalse(claim.with_name('result.json').exists())

    def test_committed_wire_fixture_matches_generated_reproducible_plan(self):
        fixture = json.loads(Path(__file__).with_name('e2e').joinpath('fixtures/v1-sequence.json').read_text())
        self.assertEqual(harness.scenarios('abcdef123456', '2026-11-16T22:00:00.120Z'), fixture)
    def runner(self, phase='active'):
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
        on = 'true' if phase == 'active' else 'false'
        for name in harness.SERVICES:
            env[name].update(SPRING_FLYWAY_ENABLED='false', SPRING_SQL_INIT_MODE='never',
                SPRING_KAFKA_ADMIN_AUTO_CREATE='false', SPRING_RABBITMQ_DYNAMIC='false',
                SPRING_KAFKA_LISTENER_AUTO_STARTUP='false', SPRING_RABBITMQ_LISTENER_SIMPLE_AUTO_STARTUP='false',
                SPRING_RABBITMQ_LISTENER_DIRECT_AUTO_STARTUP='false')
        env['producer']['SIMULATOR_WRITE_ENABLED'] = on
        env['dashboard'].update(SPRING_PROFILES_ACTIVE='passive', DASHBOARD_CATALOG_BOOTSTRAP_ENABLED='false',
            SIMULATOR_WRITE_ENABLED='false', DASHBOARD_RABBIT_LISTENER_ENABLED=on,
            SPRING_RABBITMQ_LISTENER_SIMPLE_AUTO_STARTUP=on)
        env['match-state'].update(MATCH_STATE_CATALOG_BOOTSTRAP_ENABLED='false', MATCH_STATE_RECOVERY_ENABLED=on,
            MATCH_STATE_PUBLICATION_ENABLED=on, KAFKA_LISTENER_AUTO_STARTUP=on, SPRING_KAFKA_LISTENER_AUTO_STARTUP=on)
        for name, prefix in [('archiver', 'ARCHIVER'), ('suspension', 'BETTING_CONTROL'), ('worker', 'BETTING_WORKER')]:
            env[name][prefix + '_CONSUMER_ENABLED'] = on
        env['suspension'].update(BETTING_CONTROL_PUBLICATION_ENABLED='false', ACADEMIC_COMMANDS_ENABLED='false', ACADEMIC_PUBLICATION_ENABLED='false')
        env['worker'].update(ACADEMIC_CONSUMER_ENABLED='false', ACADEMIC_TOPOLOGY_ENABLED='false')
        env['match-state'].update(MATCH_STATE_ALERTS_ENABLED='false', MATCH_STATE_ALERTS_PUBLICATION_ENABLED='false')
        env['notification']['NOTIFICATION_LIVE_ALERTS_ENABLED'] = 'false'
        env['notification'].update(NOTIFICATION_STORAGE_ENABLED=on, NOTIFICATION_MESSAGING_ENABLED=on,
            SPRING_RABBITMQ_LISTENER_SIMPLE_AUTO_STARTUP=on)
        env['worker'].update(BETTING_WORKER_ACCEPTANCE_ENABLED='false', BETTING_WORKER_AUTHORIZATION_MODE='disabled')
        runner.config['services']['worker']['deploy'] = {'replicas': 2}
        runner.config['services']['dashboard']['deploy'] = {'replicas': 1}
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

    def inspected(self, runner, name, container_id='id', number='1'):
        env = runner.config['services'][name]['environment']
        return {'Id': container_id, 'Image': 'sha256:reviewed-test-image', 'Config': {'Labels': {'com.docker.compose.project': runner.plan['project'],
                                     'com.docker.compose.container-number': number,
                                     'com.docker.compose.service': name},
                           'Env': [key + '=' + value for key, value in env.items()]},
                'State': {'Running': True}, 'Mounts': [], 'NetworkSettings': {'Networks': {}}}

    def test_actual_container_ownership_and_runtime_mismatch_are_refused(self):
        for change in ['project', 'environment', 'mount', 'network']:
            runner = self.runner()
            info = self.inspected(runner, 'dashboard')
            if change == 'project': info['Config']['Labels']['com.docker.compose.project'] = 'dev3-lab'
            if change == 'environment': info['Config']['Env'] = ['BETTING_WORKER_ACCEPTANCE_ENABLED=true']
            if change == 'mount': info['Mounts'] = [{'Type': 'volume', 'Name': 'dev3-lab-data'}]
            if change == 'network': info['NetworkSettings']['Networks'] = {'dev3-lab': {}}
            with patch.object(runner, 'call', return_value='id'), \
                    patch.object(harness.subprocess, 'run', return_value=SimpleNamespace(stdout=json.dumps([info]))):
                with self.assertRaises(ValueError):
                    runner.check_container('dashboard')

    def test_valid_running_container_is_recorded_and_replacement_is_refused(self):
        runner = self.runner()
        info = self.inspected(runner, 'dashboard')
        with patch.object(runner, 'call', return_value='id'), \
                patch.object(harness.subprocess, 'run', return_value=SimpleNamespace(stdout=json.dumps([info]))):
            runner.check_container('dashboard')
        self.assertEqual(frozenset({'id'}), runner.container_ids['dashboard'])
        with patch.object(runner, 'call', return_value='new-id'), \
                patch.object(harness.subprocess, 'run', return_value=SimpleNamespace(stdout=json.dumps([info]))):
            with self.assertRaisesRegex(ValueError, 'replaced'):
                runner.check_container('dashboard')

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

    def test_both_phase_models_validate_without_external_calls(self):
        for phase in ['passive', 'active']:
            runner = self.runner(phase)
            with patch.dict(harness.os.environ, {'LIVE_JVG_E2E_APPROVED_PROJECT': runner.plan['project']}), \
                    patch.object(harness.subprocess, 'run', side_effect=AssertionError('No commands')), \
                    patch.object(runner, 'check_container') as check, patch.object(runner, 'sql') as sql:
                runner.validate_config(phase)
                check.assert_not_called()
                sql.assert_not_called()
                if phase == 'passive':
                    with self.assertRaisesRegex(ValueError, 'active control'):
                        runner.preflight()
                    check.assert_not_called()

    def test_every_phase_gate_rejects_missing_and_unsafe_values(self):
        for phase in ['passive', 'active']:
            for name, settings in harness.Harness.runtime_policy(phase).items():
                for key, expected in settings.items():
                    for unsafe in [None, '', False, 'TRUE', 'true' if expected != 'true' else 'false']:
                        with self.subTest(phase=phase, service=name, key=key, unsafe=unsafe):
                            runner = self.runner(phase)
                            if unsafe is None: runner.config['services'][name]['environment'].pop(key)
                            else: runner.config['services'][name]['environment'][key] = unsafe
                            with patch.dict(harness.os.environ, {'LIVE_JVG_E2E_APPROVED_PROJECT': runner.plan['project']}), \
                                    patch.object(runner, 'check_container') as check:
                                with self.assertRaises(ValueError): runner.validate_config(phase)
                                check.assert_not_called()

    def test_passive_policy_matches_every_literal_control_in_committed_overlay(self):
        source = Path(__file__).with_name('e2e').joinpath('compose.manifest-proposed.yml').read_text()
        for name, settings in harness.Harness.runtime_policy('passive').items():
            block = re.search(r'(?ms)^  ' + re.escape(name) + r':\n.*?(?=^  \S|\Z)', source).group()
            for key, expected in settings.items():
                self.assertRegex(block, r'(?m)^      ' + key + ': "?' + expected + '"?$')
            for key in re.findall(r'^      (SPRING_[A-Z_]+):', block, re.M):
                self.assertIn(key, set(settings) | {'SPRING_RABBITMQ_VIRTUAL_HOST', 'SPRING_KAFKA_CONSUMER_GROUP_ID'})

    def test_unexpected_profiles_jvm_overrides_and_startup_dependencies_are_refused(self):
        for name, key, value in [('dashboard', 'SPRING_PROFILES_ACTIVE', 'passive,memory'),
                                 ('dashboard', 'SPRING_PROFILES_ACTIVE', 'prod'),
                                 ('worker', 'SPRING_PROFILES_ACTIVE', 'passive'),
                                 ('producer', 'JAVA_TOOL_OPTIONS', '-Dsimulator.write-enabled=true'),
                                 ('worker', 'JDK_JAVA_OPTIONS', '-Dworker.acceptance.enabled=true')]:
            runner = self.runner('passive')
            runner.config['services'][name]['environment'][key] = value
            with patch.dict(harness.os.environ, {'LIVE_JVG_E2E_APPROVED_PROJECT': runner.plan['project']}):
                with self.assertRaises(ValueError): runner.validate_config('passive')
        for name in harness.SERVICES:
            runner = self.runner('passive')
            runner.config['services'][name]['depends_on'] = {'kafka-init': {'condition': 'service_completed_successfully'}}
            with patch.dict(harness.os.environ, {'LIVE_JVG_E2E_APPROVED_PROJECT': runner.plan['project']}):
                with self.assertRaisesRegex(ValueError, 'dependencies'): runner.validate_config('passive')
        runner = self.runner('passive')
        runner.config['services']['kafka-init'] = {'profiles': ['integration-prepared']}
        with patch.dict(harness.os.environ, {'LIVE_JVG_E2E_APPROVED_PROJECT': runner.plan['project']}):
            with self.assertRaisesRegex(ValueError, 'provisioning profile'): runner.validate_config('passive')
            with self.assertRaisesRegex(ValueError, 'Unknown'): runner.validate_config('typo')

    def test_isolation_and_destination_regressions_fail_before_runtime_access(self):
        for change in ['project', 'port', 'network', 'volume', 'bind', 'privileged', 'network_mode',
                       'volumes_from', 'container_name', 'command', 'entrypoint', 'group', 'topic', 'match', 'destination']:
            with self.subTest(change=change):
                runner = self.runner()
                service = runner.config['services']['worker']
                if change == 'project': runner.config['name'] = 'shared'
                elif change == 'port': service['ports'] = [{'host_ip': '0.0.0.0'}]
                elif change in ['network', 'volume']:
                    runner.config['networks' if change == 'network' else 'volumes'] = {'x': {'name': 'shared', 'external': True}}
                elif change == 'bind': service['volumes'] = [{'type': 'bind', 'source': '/shared'}]
                elif change == 'privileged': service['privileged'] = True
                elif change in ['network_mode', 'container_name', 'command', 'entrypoint']: service[change] = 'unsafe'
                elif change == 'volumes_from': service['volumes_from'] = ['shared']
                elif change == 'group': service['environment']['SPRING_KAFKA_CONSUMER_GROUP_ID'] = 'shared'
                elif change == 'topic': runner.config['services']['producer']['environment']['MATCH_EVENTS_TOPIC'] = 'foreign'
                elif change == 'match': runner.config['services']['dashboard']['environment']['MATCH_ID'] = 'foreign'
                else:
                    runner.plan['resources']['exchange'] = 'approved'
                    service['environment']['BETTING_WORKER_EXCHANGE'] = 'foreign'
                with patch.dict(harness.os.environ, {'LIVE_JVG_E2E_APPROVED_PROJECT': runner.plan['project']}), \
                        patch.object(runner, 'check_container') as check:
                    with self.assertRaises(ValueError): runner.preflight()
                    check.assert_not_called()

    def test_existing_claim_refuses_before_preparation_and_preserves_artifacts(self):
        with tempfile.TemporaryDirectory(prefix='offline-harness-') as temporary:
            root = Path(temporary)
            output = root / 'tmp/live-jvg-e2e-abcdef123456'
            output.mkdir(parents=True)
            claim = output / 'execute.claim'
            claim.write_text('original claim')
            args = ['e2e-harness.py', 'execute-v1', '--run', 'abcdef123456']
            with patch.object(harness, 'ROOT', root), patch('sys.argv', args), patch.object(harness, 'Harness') as constructor:
                with self.assertRaisesRegex(ValueError, 'already claimed'): harness.main()
                constructor.assert_not_called()
                self.assertEqual('original claim', claim.read_text())
                self.assertFalse((output / 'result.json').exists())

    def test_nonfresh_storage_is_refused_before_any_publication(self):
        runner = self.runner()
        initial = dict.fromkeys(['match', 'dashboard', 'notification', 'archive', 'suspension', 'accepted'], 0)
        initial['match'] = 1
        with patch.object(runner, 'preflight'), patch.object(runner, 'observe', return_value=initial), \
                patch.object(runner, '_execute_prepared') as execute:
            with self.assertRaisesRegex(ValueError, 'Fresh run required'): runner.execute()
            execute.assert_not_called()

    def test_check_config_cli_is_offline_and_creates_no_run_artifacts(self):
        for phase in ['passive', 'active']:
            runner = self.runner(phase)
            with tempfile.TemporaryDirectory(prefix='offline-harness-') as temporary:
                root = Path(temporary)
                config = root / 'resolved.json'
                config.write_text(json.dumps(runner.config))
                args = ['e2e-harness.py', 'check-config', '--run', 'abcdef123456',
                        '--config-json', str(config), '--phase', phase]
                with patch.object(harness, 'ROOT', root), patch('sys.argv', args), \
                        patch.dict(harness.os.environ, {'LIVE_JVG_E2E_APPROVED_PROJECT': runner.plan['project']}), \
                        patch.object(harness.subprocess, 'run', side_effect=AssertionError('No commands')):
                    harness.main()
                    self.assertFalse((root / 'tmp').exists())

    def test_approved_replica_counts_in_both_phases(self):
        for phase in ['passive', 'active']:
            for name, invalid in [('worker', 0), ('worker', 1), ('worker', 3), ('worker', '2'),
                                  ('worker', True), ('dashboard', 2), ('producer', 2), ('kafka', 2)]:
                runner = self.runner(phase)
                runner.config['services'][name]['deploy'] = {'replicas': invalid}
                with patch.dict(harness.os.environ, {'LIVE_JVG_E2E_APPROVED_PROJECT': runner.plan['project']}):
                    with self.assertRaisesRegex(ValueError, 'replica count'): runner.validate_config(phase)
            runner = self.runner(phase)
            runner.config['services']['worker'].pop('deploy')
            with patch.dict(harness.os.environ, {'LIVE_JVG_E2E_APPROVED_PROJECT': runner.plan['project']}):
                with self.assertRaisesRegex(ValueError, 'replica count'): runner.validate_config(phase)

    def test_two_workers_are_each_inspected_and_order_is_irrelevant(self):
        runner = self.runner('passive')
        info = [self.inspected(runner, 'worker', 'worker1', '1'), self.inspected(runner, 'worker', 'worker2', '2')]
        with patch.object(runner, 'call', side_effect=['worker1\nworker2', 'worker2\nworker1']) as call, \
                patch.object(harness.subprocess, 'run', return_value=SimpleNamespace(stdout=json.dumps(info))) as inspect:
            runner.check_container('worker')
            runner.check_container('worker')
            self.assertEqual(frozenset({'worker1', 'worker2'}), runner.container_ids['worker'])
            call.assert_called_with(['ps', '--all', '-q', 'worker'])
            self.assertEqual({'worker1', 'worker2'}, set(inspect.call_args.args[0][2:]))
        self.assertEqual('false', runner.config['services']['worker']['environment']['BETTING_WORKER_CONSUMER_ENABLED'])

    def test_worker_count_and_id_duplicates_fail_before_inspect(self):
        for ids in ['', 'worker1', 'worker1\nworker2\nworker3', 'worker1\nworker1']:
            runner = self.runner()
            with patch.object(runner, 'call', return_value=ids), patch.object(harness.subprocess, 'run') as inspect:
                with self.assertRaisesRegex(ValueError, 'count'): runner.check_container('worker')
                inspect.assert_not_called()
                self.assertNotIn('worker', runner.container_ids)

    def test_each_worker_failure_is_rejected_without_recording_partial_success(self):
        for index in [0, 1]:
            for change in ['project', 'service', 'ordinal', 'stopped', 'paused', 'restarting', 'environment',
                           'mount', 'network', 'identity', 'image', 'jvm', 'tool-options']:
                with self.subTest(replica=index, change=change):
                    runner = self.runner('passive')
                    info = [self.inspected(runner, 'worker', 'worker1', '1'), self.inspected(runner, 'worker', 'worker2', '2')]
                    bad = info[index]
                    if change == 'project': bad['Config']['Labels']['com.docker.compose.project'] = 'shared'
                    elif change == 'service': bad['Config']['Labels']['com.docker.compose.service'] = 'other'
                    elif change == 'ordinal': bad['Config']['Labels']['com.docker.compose.container-number'] = '3'
                    elif change == 'stopped': bad['State']['Running'] = False
                    elif change == 'paused': bad['State']['Paused'] = True
                    elif change == 'restarting': bad['State']['Restarting'] = True
                    elif change == 'environment': bad['Config']['Env'] = ['BETTING_WORKER_CONSUMER_ENABLED=true']
                    elif change == 'mount': bad['Mounts'] = [{'Type': 'bind', 'Source': '/shared'}]
                    elif change == 'network': bad['NetworkSettings']['Networks'] = {'shared': {}}
                    elif change == 'identity': bad['Id'] = 'foreign'
                    elif change == 'image': bad['Image'] = 'sha256:other'
                    elif change == 'jvm': bad['Config']['Env'].append('JAVA_OPTS=-Dworker.acceptance.enabled=true')
                    else: bad['Config']['Env'].append('JAVA_TOOL_OPTIONS=-javaagent:unsafe.jar')
                    with patch.object(runner, 'call', return_value='worker1\nworker2'), \
                            patch.object(harness.subprocess, 'run', return_value=SimpleNamespace(stdout=json.dumps(info))):
                        with self.assertRaises(ValueError): runner.check_container('worker')
                        self.assertNotIn('worker', runner.container_ids)
                        self.assertFalse(getattr(runner, 'container_images', {}))

    def test_worker_replacement_and_image_change_do_not_overwrite_baseline(self):
        runner = self.runner()
        info = [self.inspected(runner, 'worker', 'worker1', '1'), self.inspected(runner, 'worker', 'worker2', '2')]
        with patch.object(runner, 'call', return_value='worker1\nworker2'), \
                patch.object(harness.subprocess, 'run', return_value=SimpleNamespace(stdout=json.dumps(info))):
            runner.check_container('worker')
        original = runner.container_ids.copy()
        with patch.object(runner, 'call', return_value='worker1\nreplacement'), patch.object(harness.subprocess, 'run') as inspect:
            with self.assertRaisesRegex(ValueError, 'replaced'): runner.check_container('worker')
            inspect.assert_not_called()
        for item in info: item['Image'] = 'sha256:replaced'
        with patch.object(runner, 'call', return_value='worker1\nworker2'), \
                patch.object(harness.subprocess, 'run', return_value=SimpleNamespace(stdout=json.dumps(info))):
            with self.assertRaisesRegex(ValueError, 'image changed'): runner.check_container('worker')
        self.assertEqual(original, runner.container_ids)

    def test_dashboard_extra_instance_and_worker_inspect_mismatch_fail(self):
        runner = self.runner()
        with patch.object(runner, 'call', return_value='dashboard1\ndashboard2'), patch.object(harness.subprocess, 'run') as inspect:
            with self.assertRaisesRegex(ValueError, 'count'): runner.check_container('dashboard')
            inspect.assert_not_called()
        info = self.inspected(runner, 'worker', 'worker1', '1')
        for inspected in [[info], [info, info]]:
            with patch.object(runner, 'call', return_value='worker1\nworker2'), \
                    patch.object(harness.subprocess, 'run', return_value=SimpleNamespace(stdout=json.dumps(inspected))):
                with self.assertRaises(ValueError): runner.check_container('worker')

    def test_approved_image_jvm_default_passes_without_allowing_declared_overrides(self):
        runner = self.runner('passive')
        info = [self.inspected(runner, 'worker', 'worker1', '1'), self.inspected(runner, 'worker', 'worker2', '2')]
        for item in info:
            item['Config']['Env'].append('JAVA_OPTS=-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError')
        with patch.object(runner, 'call', return_value='worker1\nworker2'), \
                patch.object(harness.subprocess, 'run', return_value=SimpleNamespace(stdout=json.dumps(info))):
            runner.check_container('worker')
        runner.config['services']['worker']['environment']['JAVA_OPTS'] = '-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError'
        with patch.dict(harness.os.environ, {'LIVE_JVG_E2E_APPROVED_PROJECT': runner.plan['project']}):
            with self.assertRaisesRegex(ValueError, 'runtime override'): runner.validate_config('passive')

    def test_invalid_replicas_after_restart_block_the_next_barrier(self):
        runner = self.runner()
        initial = dict.fromkeys(['match', 'dashboard', 'notification', 'archive', 'suspension', 'accepted'], 0)
        restarted = [False]
        def check(name):
            if restarted[0] and name == 'worker': raise ValueError('replica failed after restart')
        def restart(args):
            restarted[0] = True
        with patch.object(runner, 'preflight'), patch.object(runner, 'observe', return_value=initial), \
                patch.object(runner, 'publish_through_producer'), patch.object(runner, 'publish_event') as kafka, \
                patch.object(runner, 'await_state'), patch.object(runner, 'blocked_command'), \
                patch.object(runner, 'check_container', side_effect=check), patch.object(runner, 'call', side_effect=restart):
            with self.assertRaisesRegex(ValueError, 'after restart'): runner.execute()
            self.assertEqual(2, kafka.call_count)  # duplicate and first barrier only



    def test_academic_flags_fail_closed_in_both_phases(self):
        controls={'suspension':['ACADEMIC_COMMANDS_ENABLED','ACADEMIC_PUBLICATION_ENABLED'],
                  'worker':['ACADEMIC_CONSUMER_ENABLED','ACADEMIC_TOPOLOGY_ENABLED']}
        for phase in ['passive','active']:
            for service,keys in controls.items():
                for key in keys:
                    for bad in [None,'true','TRUE','',False,'disabled']:
                        with self.subTest(phase=phase,key=key,bad=bad):
                            runner=self.runner(phase)
                            if bad is None: runner.config['services'][service]['environment'].pop(key)
                            else: runner.config['services'][service]['environment'][key]=bad
                            with patch.dict(harness.os.environ,{'LIVE_JVG_E2E_APPROVED_PROJECT':runner.plan['project']}):
                                with self.assertRaises(ValueError): runner.validate_config(phase)

    def test_passive_model_rejects_property_aliases(self):
        for key in ['academic.consumer.enabled','academic_consumer_enabled','worker.acceptance.enabled','spring.application.json']:
            runner=self.runner('passive');runner.config['services']['worker']['environment'][key]='true'
            with patch.dict(harness.os.environ,{'LIVE_JVG_E2E_APPROVED_PROJECT':runner.plan['project']}),self.assertRaises(ValueError):runner.validate_config('passive')

    def test_every_replica_rejects_academic_and_undeclared_property_overrides(self):
        for ordinal in [0,1]:
            for key,value in [('ACADEMIC_CONSUMER_ENABLED','true'),('ACADEMIC_TOPOLOGY_ENABLED','true'),
                              ('ACADEMIC_OTHER_ENABLED','true'),('SPRING_APPLICATION_JSON','{}'),('academic.consumer.enabled','true'),('worker.acceptance.enabled','true'),('academic_consumer_enabled','true')]:
                runner=self.runner('passive')
                info=[self.inspected(runner,'worker','w1','1'),self.inspected(runner,'worker','w2','2')]
                info[ordinal]['Config']['Env']=[v for v in info[ordinal]['Config']['Env'] if not v.startswith(key+'=')]+[key+'='+value]
                with patch.object(runner,'call',return_value='w1\nw2'),patch.object(harness.subprocess,'run',return_value=SimpleNamespace(stdout=json.dumps(info))):
                    with self.assertRaises(ValueError):runner.check_container('worker')
                self.assertNotIn('worker',runner.container_ids)

if __name__ == '__main__':
    unittest.main()
