"""Academic V1 rehearsal: plan/validate offline; execution requires separate explicit approval.
No provisioning, DDL, grants, leases or real betting operation submissions.
"""
import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import time
import uuid

SPEC = importlib.util.spec_from_file_location('legacy_harness', Path(__file__).with_name('e2e-harness.py'))
legacy = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(legacy)


def command_id(match, event):
    identity = f'academic-v1:{len(match)}:{match}:{len(event)}:{event}'
    return str(uuid.UUID(bytes=hashlib.md5(identity.encode('utf-8')).digest(), version=3))


def plan(run, timestamp=None, manifest=None):
    base = legacy.scenarios(run, timestamp, manifest)
    events = [dict(base['steps'][i]['event']) for i in [0, 1, 4, 5]]
    events[-1]['reference_event_id']=events[1]['event_id']
    base['scenario'] = 'academic-v1'
    base['excluded_v1']=['real betting acceptance','authority','grants','leases','VAR V2']
    base['prepared_only']=[{'scenario':'Resilience, UI and resource checks','gate':'Separate isolated infrastructure and fault-injection approval'}]
    base['academic_vhost'] = 'dev3_it_' + run + '_academic'
    base['academic_queue'] = 'betting_commands'
    base['steps'] = []
    for index, event in enumerate(events):
        action = 'SUSPEND_BETS' if event['event_type'] in {'GOAL', 'VAR_CHECK'} else 'RESUME_BETS'
        base['steps'].append(dict(event=event, command_id=command_id(event['match_id'], event['event_id']),
            command_type=action, simulated_status='SIMULATED_SUSPENDED' if action == 'SUSPEND_BETS' else 'SIMULATED_RESUMED',
            home_score=1 if index < 2 else 0, away_score=0,
            var_status='IN_PROGRESS' if index == 1 else 'CLEAR', alerts=min(index + 1, 3)))
    base['status'] = 'PREPARED_NOT_EXECUTED'
    return base


def assert_observation(value, scenario, count):
    step = scenario['steps'][count - 1]
    for key in ['match', 'dashboard', 'archive', 'suspension', 'academic_commands', 'academic_outbox', 'academic_published']:
        if value.get(key) != count:
            raise AssertionError('Missing or unexpected evidence: ' + key)
    if value.get('alerts') != step['alerts'] or value.get('alert_published') != step['alerts']:
        raise AssertionError('Independent Fanout evidence differs')
    if value.get('snapshot') != {k: step[k] for k in ['home_score', 'away_score', 'var_status']}:
        raise AssertionError('Dashboard score/VAR differs')
    if any(value.get(k) != 0 for k in ['accepted', 'operations', 'grants', 'leases', 'worker_quarantine']):
        raise AssertionError('Unsafe operation/authority or rejected academic command')
    expected = [(s['command_id'], s['simulated_status']) for s in scenario['steps'][:count]]
    actual = sorted((c['command_id'], c['simulated_status']) for c in value.get('results', []))
    if actual != sorted(expected):
        raise AssertionError('Academic identities or persisted results differ')
    if value.get('annul_reference') != ('%s-g' % scenario['run'] if count >= 3 else None):
        raise AssertionError('Original goal reference not preserved')
    if value.get('source_sequence') != count or value.get('market') != 'SUSPENDED':
        raise AssertionError('Unsafe projection sequence or market state')


class AcademicHarness(legacy.Harness):
    def expected_vhost(self, name):
        return self.plan['academic_vhost'] if name in {'worker', 'suspension'} else super().expected_vhost(name)

    @staticmethod
    def runtime_policy(phase):
        if phase != 'active':
            raise ValueError('Academic ON is separate from PASSIVE/OFF')
        policy = legacy.Harness.runtime_policy('active')
        policy['match-state'].update(MATCH_STATE_ALERTS_ENABLED='true', MATCH_STATE_ALERTS_PUBLICATION_ENABLED='true')
        policy['dashboard']['DASHBOARD_SCORE_SEQUENCE_REQUIRED'] = 'true'
        policy['dashboard']['DASHBOARD_ALLOWED_ORIGINS']='http://127.0.0.1:15173'
        policy['notification']['NOTIFICATION_ALLOWED_ORIGINS']='http://127.0.0.1:15173'
        policy['notification']['NOTIFICATION_LIVE_ALERTS_ENABLED'] = 'true'
        policy['suspension'].update(ACADEMIC_COMMANDS_ENABLED='true', ACADEMIC_PUBLICATION_ENABLED='true', ACADEMIC_QUEUE='betting_commands')
        policy['worker'].update(BETTING_WORKER_CONSUMER_ENABLED='false', ACADEMIC_CONSUMER_ENABLED='true',
            ACADEMIC_TOPOLOGY_ENABLED='false', ACADEMIC_QUEUE='betting_commands')
        return policy

    def validate_config(self, phase):
        if os.environ.get('LIVE_JVG_ACADEMIC_APPROVED_PROJECT') != self.plan['project']:
            raise ValueError('Separate academic ON approval missing')
        super().validate_config(phase)
        roles={'suspension':('RABBITMQ_USERNAME','academic_pub'),'worker':('RABBITMQ_USERNAME','academic_worker'),
               'match-state':('RABBITMQ_USERNAME','score_pub'),'dashboard':('RABBITMQ_USERNAME','score_reader'),
               'notification':('NOTIFICATION_RABBIT_USERNAME','alert_reader')}
        for service,(key,suffix) in roles.items():
            if self.config['services'][service]['environment'].get(key) != 'dev3_it_'+self.plan['run']+'_'+suffix:
                raise ValueError('Dedicated scoped Rabbit identity required: '+service)
        if self.config['services']['archiver']['environment'].get('RABBITMQ_USERNAME') != 'unused-no-rabbit-access':
            raise ValueError('Archiver must not inherit broker administrative credentials')
        expected_queue='notification.dev3_it_'+self.plan['run']+'_alerts'
        if self.config['services']['notification']['environment'].get('NOTIFICATION_QUEUE') != expected_queue:
            raise ValueError('Separate Fanout-only Notification queue required')
        if self.plan['academic_vhost'] == self.plan['resources']['vhost']:
            raise ValueError('Academic default-exchange publishing requires a separate vhost')

    def observe_academic(self):
        m = self.plan['match_id']
        counts = {'match':'match_state.accepted_event', 'dashboard':'dashboard.inbox',
                  'suspension':'betting_control.processed_event','academic_commands':'betting_worker.academic_command',
                  'academic_outbox':'betting_control.academic_outbox', 'alerts':'notification.live_alert_inbox',
                  'operations':'betting_worker.operation', 'grants':'betting_worker.operation_authorization',
                  'leases':'betting_control.authorization_lease'}
        fields = [f"'{key}',(SELECT count(*) FROM {table}" + (')' if key in {'operations','grants','leases'} else f" WHERE match_id='{m}')") for key, table in counts.items()]
        fields += [f"'accepted',(SELECT count(*) FROM betting_worker.operation WHERE status='ACCEPTED')",
                   f"'academic_published',(SELECT count(*) FROM betting_control.academic_outbox WHERE match_id='{m}' AND published_at IS NOT NULL)",
                   f"'alert_published',(SELECT count(*) FROM match_state.alert_outbox WHERE match_id='{m}' AND published)",
                   "'worker_quarantine',(SELECT count(*) FROM betting_worker.academic_delivery WHERE outcome NOT IN ('RECORDED','DUPLICATE'))",
                   f"'snapshot',(SELECT row_to_json(s) FROM (SELECT home_score,away_score,var_status FROM dashboard.matches WHERE match_id='{m}') s)",
                   f"'source_sequence',(SELECT max(source_application_seq) FROM dashboard.inbox WHERE match_id='{m}')",
                   f"'market',(SELECT status FROM betting_control.market_state WHERE match_id='{m}')",
                   f"'annul_reference',(SELECT source_reference_event_id FROM betting_control.academic_outbox WHERE match_id='{m}' AND command_type='RESUME_BETS' AND source_reference_event_id IS NOT NULL LIMIT 1)",
                   f"'results',(SELECT coalesce(json_agg(r),'[]') FROM (SELECT command_id,simulated_status FROM betting_worker.academic_command WHERE match_id='{m}') r)"]
        value = json.loads(self.sql('SELECT json_build_object(' + ','.join(fields) + ')'))
        value['archive'] = int(self.sql(f"SELECT count(*) FROM archiver.archived_event WHERE match_id='{m}'", database=self.plan['resources']['archiver_database']))
        return value

    def execute_academic(self):
        self.preflight()  # strict model + both actual replicas; no provisioning
        before = self.observe_academic()
        if any(before[k] for k in ['match', 'dashboard', 'archive', 'suspension', 'academic_commands', 'alerts','operations','grants','leases']):
            raise ValueError('Fresh rehearsal required; preserve previous evidence')
        observations = []
        transmitted = []
        for count, step in enumerate(self.plan['steps'], 1):
            transmitted.append(self.publish_through_producer(step['event']))
            deadline = time.monotonic() + 60
            while True:
                value = self.observe_academic()
                try:
                    assert_observation(value, self.plan, count)
                    observations.append(value)
                    break
                except AssertionError:
                    if any(value.get(k,0) for k in ['accepted','operations','grants','leases','worker_quarantine']) or time.monotonic() > deadline:
                        raise
                    time.sleep(1)
        # Duplicate followed by a new continuation barrier: every consumer must traverse it.
        self.publish_event(transmitted[0])
        barrier = dict(self.plan['steps'][-1]['event'], event_id=self.plan['run']+'-academic-barrier')
        self.publish_through_producer(barrier)
        barrier_plan = dict(self.plan,steps=list(self.plan['steps']))
        barrier_plan['steps'].append(dict(event=barrier,command_id=command_id(barrier['match_id'],barrier['event_id']),
            command_type='RESUME_BETS',simulated_status='SIMULATED_RESUMED',home_score=0,away_score=0,var_status='CLEAR',alerts=3))
        deadline=time.monotonic()+60
        while True:
            value=self.observe_academic()
            try:
                assert_observation(value,barrier_plan,5)
                observations.append(value)
                break
            except AssertionError:
                if any(value.get(k,0) for k in ['accepted','operations','grants','leases','worker_quarantine']) or time.monotonic()>deadline:raise
                time.sleep(1)
        return dict(status='FUNCTIONAL_SEQUENCE_AND_KAFKA_DUPLICATE_OBSERVED',observations=observations,
                    plan=self.plan,containers={k:sorted(v) for k,v in self.container_ids.items()},images=self.container_images,
                    pending=['replica work distribution','Rabbit redelivery','DLQ','restarts','induced failures','REST/STOMP/React'],certified=False)



def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('mode', choices=['plan', 'check-config', 'execute-approved'])
    parser.add_argument('--run', required=True)
    parser.add_argument('--manifest', type=Path)
    parser.add_argument('--timestamp')
    parser.add_argument('--plan-json',type=Path,help='Reviewed immutable academic plan; required for execution')
    parser.add_argument('--config-json', type=Path)
    parser.add_argument('--compose-file', action='append')
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    scenario = plan(args.run,args.timestamp,json.loads(args.manifest.read_text()) if args.manifest else None)
    if args.plan_json:
        reviewed=json.loads(args.plan_json.read_text())
        expected=plan(args.run,reviewed['steps'][0]['event']['timestamp'],json.loads(args.manifest.read_text()) if args.manifest else None)
        if reviewed!=expected or (args.timestamp and args.timestamp!=reviewed['steps'][0]['event']['timestamp']):
            raise ValueError('Reviewed academic plan changed; preserve evidence and prepare a new run')
        scenario=reviewed
    if args.mode=='execute-approved' and not args.plan_json:
        raise ValueError('Reviewed academic plan required before execution')
    if args.mode == 'plan':
        with args.output.open('x') as out:json.dump(scenario,out,indent=2)
        print('PREPARED ONLY; no external commands')
        return
    if args.mode == 'check-config':
        AcademicHarness(scenario,[],config=json.loads(args.config_json.read_text())).validate_config('active')
        print('ACADEMIC CONFIG CHECKED OFFLINE; no execution authorized')
        return
    if os.environ.get('LIVE_JVG_ACADEMIC_EXECUTION_APPROVAL') != 'I_APPROVE_ISOLATED_ACADEMIC_EXECUTION':
        raise ValueError('Separate infrastructure execution approval required')
    if not args.compose_file:raise ValueError('Explicit reviewed isolated Compose files required')
    # Reserve evidence before even requesting Docker config. Never overwrite a failed run.
    with args.output.open('x') as out:
        try:
            harness = AcademicHarness(scenario,args.compose_file)
            result = harness.execute_academic()
        except Exception as failure:
            json.dump(dict(status='FAILED',error_type=type(failure).__name__,certified=False),out)
            raise
        json.dump(result,out,indent=2)


if __name__ == '__main__':
    main()
