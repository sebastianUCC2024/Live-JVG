"""Offline contracts for the PREPARED academic rehearsal; never touches infrastructure."""
import importlib.util
import copy
from pathlib import Path
import unittest
from unittest.mock import patch
S=importlib.util.spec_from_file_location('academic',Path(__file__).with_name('academic-e2e.py'))
a=importlib.util.module_from_spec(S);S.loader.exec_module(a)

class AcademicPlanTest(unittest.TestCase):
    def scenario(self):return a.plan('abcdef123456','2026-11-17T00:00:00Z')
    def observed(self,p,count):
        step=p['steps'][count-1]
        return dict({k:count for k in ['match','dashboard','archive','suspension','academic_commands','academic_outbox','academic_published']},
            alerts=step['alerts'],alert_published=step['alerts'],accepted=0,operations=0,grants=0,leases=0,worker_quarantine=0,
            snapshot={k:step[k] for k in ['home_score','away_score','var_status']},results=[{k:s[k] for k in ['command_id','simulated_status']} for s in p['steps'][:count]],
            annul_reference='abcdef123456-g' if count>=3 else None,source_sequence=count,market='SUSPENDED')
    def test_four_actions_identity_and_original_goal(self):
        with patch.object(a.legacy.subprocess,'run',side_effect=AssertionError('No infrastructure')):
            p=self.scenario()
        self.assertEqual(['SUSPEND_BETS','SUSPEND_BETS','RESUME_BETS','RESUME_BETS'],[s['command_type'] for s in p['steps']])
        self.assertEqual(p['steps'][0]['event']['event_id'],p['steps'][2]['event']['reference_event_id'])
        self.assertEqual(p['steps'][1]['event']['event_id'],p['steps'][3]['event']['reference_event_id'])
        self.assertEqual(4,len({s['command_id'] for s in p['steps']}))
        self.assertEqual('PREPARED_NOT_EXECUTED',p['status'])
        self.assertIn('authority',p['excluded_v1'])
    def test_real_golden_contract_identity_matches(self):
        import json
        fixture=Path(__file__).resolve().parents[1]/'services/betting-worker/src/test/resources/contracts/academic-v1.json'
        for c in json.loads(fixture.read_text()):self.assertEqual(c['command_id'],a.command_id(c['match_id'],c['source_event_id']))
    def test_observable_score_alert_and_academic_barriers(self):
        p=self.scenario()
        for count in range(1,5):a.assert_observation(self.observed(p,count),p,count)
    def test_unsafe_incomplete_conflicting_results_fail(self):
        p=self.scenario()
        for field in ['accepted','operations','grants','leases','worker_quarantine','academic_published','source_sequence','alerts']:
            value=self.observed(p,4);value[field]+=1
            with self.subTest(field=field),self.assertRaises(AssertionError):a.assert_observation(value,p,4)
        for field in ['annul_reference','market','snapshot','results']:
            value=self.observed(p,4);value[field]=None
            with self.subTest(field=field),self.assertRaises((AssertionError,TypeError)):a.assert_observation(value,p,4)
    def test_duplicate_uses_actual_producer_wire_timestamp_spelling(self):
        p=self.scenario();runner=object.__new__(a.AcademicHarness);runner.plan=p;runner.container_ids={};runner.container_images={}
        wire=[dict(step['event'],timestamp='2026-11-17T00:00:00.000000000Z') for step in p['steps']]
        barrier=dict(p['steps'][-1]['event'],event_id=p['run']+'-academic-barrier')
        p5=dict(p,steps=p['steps']+[dict(event=barrier,command_id=a.command_id(p['match_id'],barrier['event_id']),simulated_status='SIMULATED_RESUMED',home_score=0,away_score=0,var_status='CLEAR',alerts=3)])
        initial={k:0 for k in ['match','dashboard','archive','suspension','academic_commands','alerts','operations','grants','leases']}
        values=[initial]+[self.observed(p,n) for n in range(1,5)]+[self.observed(p5,5)]
        with patch.object(runner,'preflight'),patch.object(runner,'observe_academic',side_effect=values),patch.object(runner,'publish_through_producer',side_effect=wire+[barrier]),patch.object(runner,'publish_event') as duplicate,patch.object(a.legacy.subprocess,'run',side_effect=AssertionError('No infrastructure')):
            result=runner.execute_academic()
            duplicate.assert_called_once_with(wire[0]);self.assertFalse(result['certified'])

    def test_academic_mode_is_separate_and_never_authorizes_operations(self):
        with self.assertRaises(ValueError):a.AcademicHarness.runtime_policy('passive')
        p=a.AcademicHarness.runtime_policy('active')
        self.assertEqual('false',p['worker']['BETTING_WORKER_ACCEPTANCE_ENABLED'])
        self.assertEqual('disabled',p['worker']['BETTING_WORKER_AUTHORIZATION_MODE'])
        self.assertEqual('false',p['worker']['BETTING_WORKER_CONSUMER_ENABLED'])
        self.assertEqual('false',p['worker']['ACADEMIC_TOPOLOGY_ENABLED'])
        self.assertEqual('true',p['worker']['ACADEMIC_CONSUMER_ENABLED'])
        self.assertEqual('true',p['dashboard']['DASHBOARD_SCORE_SEQUENCE_REQUIRED'])
    def test_separate_approval_before_model_access(self):
        h=object.__new__(a.AcademicHarness);h.plan=self.scenario()
        with patch.dict(a.os.environ,{},clear=True),self.assertRaises(ValueError):h.validate_config('active')
    def test_full_academic_model_requires_scoped_roles_and_own_vhost(self):
        spec=importlib.util.spec_from_file_location('legacy_tests',Path(__file__).with_name('test-e2e-harness.py'))
        tests=importlib.util.module_from_spec(spec);spec.loader.exec_module(tests)
        legacy_runner=tests.HarnessTest().runner('active')
        runner=object.__new__(a.AcademicHarness);runner.plan=self.scenario();runner.config=legacy_runner.config
        for service,values in runner.runtime_policy('active').items():runner.config['services'][service]['environment'].update(values)
        for service in ['suspension','worker']:runner.config['services'][service]['environment']['SPRING_RABBITMQ_VIRTUAL_HOST']=runner.plan['academic_vhost']
        for service,key,suffix in [('worker','RABBITMQ_USERNAME','academic_worker'),('suspension','RABBITMQ_USERNAME','academic_pub'),('match-state','RABBITMQ_USERNAME','score_pub'),('dashboard','RABBITMQ_USERNAME','score_reader'),('notification','NOTIFICATION_RABBIT_USERNAME','alert_reader')]:
            runner.config['services'][service]['environment'][key]='dev3_it_abcdef123456_'+suffix
        runner.config['services']['archiver']['environment']['RABBITMQ_USERNAME']='unused-no-rabbit-access'
        runner.config['services']['notification']['environment']['NOTIFICATION_QUEUE']='notification.dev3_it_abcdef123456_alerts'
        with patch.dict(a.os.environ,{'LIVE_JVG_ACADEMIC_APPROVED_PROJECT':runner.plan['project'],'LIVE_JVG_E2E_APPROVED_PROJECT':runner.plan['project']}),patch.object(a.legacy.subprocess,'run',side_effect=AssertionError('No infrastructure')):
            runner.validate_config('active')
            runner.config['services']['worker']['environment']['RABBITMQ_USERNAME']='mvp'
            with self.assertRaises(ValueError):runner.validate_config('active')

    def test_cli_cannot_execute_without_new_approval(self):
        import tempfile
        with tempfile.TemporaryDirectory() as tmp:
            output=Path(tmp)/'result.json'
            import json
            reviewed=Path(tmp)/'plan.json';reviewed.write_text(json.dumps(self.scenario()))
            with patch.dict(a.os.environ,{},clear=True),patch('sys.argv',['academic-e2e.py','execute-approved','--run','abcdef123456','--output',str(output),'--plan-json',str(reviewed)]),patch.object(a.legacy.subprocess,'run',side_effect=AssertionError('No external command')):
                with self.assertRaises(ValueError):a.main()
            self.assertFalse(output.exists())
    def test_modified_plan_refused_before_any_external_command(self):
        import tempfile,json
        with tempfile.TemporaryDirectory() as tmp:
            reviewed=Path(tmp)/'plan.json';bad=self.scenario();bad['steps'][0]['command_id']='tampered';reviewed.write_text(json.dumps(bad))
            with patch('sys.argv',['academic-e2e.py','execute-approved','--run','abcdef123456','--output',str(Path(tmp)/'result.json'),'--plan-json',str(reviewed)]),patch.object(a.legacy.subprocess,'run',side_effect=AssertionError('No infrastructure')):
                with self.assertRaises(ValueError):a.main()

    def test_two_distinct_vhosts(self):
        h=object.__new__(a.AcademicHarness);h.plan=self.scenario()
        self.assertNotEqual(h.expected_vhost('worker'),h.expected_vhost('dashboard'))
        self.assertEqual(h.expected_vhost('worker'),h.expected_vhost('suspension'))
if __name__=='__main__':unittest.main()
