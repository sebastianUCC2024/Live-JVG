"""Prepare fixtures offline; optionally exercise an ALREADY provisioned isolated V1 stack.

Never creates containers, migrations, queues, leases or accepted operations.
The default command is plan. No V2 execution path exists before contract approval.
"""
import argparse
import base64
from datetime import datetime, timezone, timedelta
import json
import os
from pathlib import Path
import re
import subprocess
import time
from urllib.parse import quote
from urllib.request import Request, urlopen

ROOT = Path(__file__).resolve().parents[1]
SERVICES = ['producer', 'match-state', 'dashboard', 'notification', 'archiver', 'suspension', 'worker']


def scenarios(run):
    if not re.fullmatch(r'[a-f0-9]{12}', run):
        raise ValueError('run must be twelve lowercase hex digits')
    match = 'e2e-' + run
    now = (datetime.now(timezone.utc) - timedelta(seconds=1)).isoformat().replace('+00:00', 'Z')
    def event(suffix, kind, reference=None):
        return dict(event_id=run + '-' + suffix, match_id=match, event_type=kind,
                    team_id='home' if kind == 'GOAL' else None, minute=10,
                    timestamp=now, reference_event_id=run + '-' + reference if reference else None)
    events = [event('g', 'GOAL'), event('v1', 'VAR_CHECK', 'g'),
              event('v2', 'VAR_CHECK'), event('c1', 'MATCH_CONTINUES', 'v1'),
              event('annul', 'GOAL_ANNULLED', 'g'), event('c2', 'MATCH_CONTINUES', 'v2')]
    steps = []
    # V1 explicitly keeps global closure; do not label it as V2 partial success.
    for e, score, var in zip(events, [1, 1, 1, 1, 0, 0],
                             ['CLEAR', 'IN_PROGRESS', 'IN_PROGRESS', 'CLEAR', 'CLEAR', 'CLEAR']):
        steps.append(dict(event=e, home_score=score, away_score=0, var_status=var))
    return dict(run=run, project='live-jvg-e2e-' + run, match_id=match, steps=steps,
                prepared_only=[
                    {'scenario': 'V2 partial closure', 'expected': 'IN_PROGRESS after c1; CLEAR after c2',
                     'gate': 'Gabriel/Valeria approve discriminator, then wire/persist V2'},
                    {'scenario': 'V2 GOAL_ANNULLED', 'gate': 'approve unambiguous goal AND review identities'},
                    {'scenario': 'Worker rejection caused by suspension',
                     'gate': 'Valeria provides approved authority; disabled mode only proves safe nonacceptance'}])


class Harness:
    def __init__(self, plan, files):
        self.plan = plan
        self.base = ['docker', 'compose', '-p', plan['project']]
        for file in files:
            self.base += ['-f', str(Path(file).resolve())]
        self.base += ['--profile', 'integration-prepared']
        self.config = json.loads(self.call(['config', '--format', 'json']))
        self.observations = []

    def call(self, args, body=None):
        result = subprocess.run(self.base + args, cwd=ROOT, input=body, text=True,
                                capture_output=True, timeout=90)
        if result.returncode:
            # Compose stderr/config may contain secrets. Retain a safe diagnostic only.
            raise RuntimeError('Compose command failed: ' + args[0] + '; exit=' + str(result.returncode))
        return result.stdout.strip()

    def preflight(self):
        if os.environ.get('LIVE_JVG_E2E_APPROVED_PROJECT') != self.plan['project']:
            raise ValueError('Explicit isolated project acknowledgement missing')
        cfg = self.config
        if cfg['name'] != self.plan['project']:
            raise ValueError('Project mismatch')
        for resource in ('volumes', 'networks'):
            for definition in cfg.get(resource, {}).values():
                if definition.get('external') or not definition['name'].startswith(cfg['name'] + '_'):
                    raise ValueError('External or reused resource refused')
        svc = cfg['services']
        for name in SERVICES + ['postgres', 'kafka', 'rabbitmq']:
            service = svc[name]
            if service.get('network_mode') or service.get('privileged') or service.get('volumes_from'):
                raise ValueError('Host networking, privileged access or inherited volumes refused')
            if service.get('container_name'):
                raise ValueError('Fixed container names refused; supply isolated override')
            for mount in service.get('volumes', []):
                if mount['type'] != 'volume' or mount['source'] not in cfg['volumes']:
                    raise ValueError('Host bind or unknown mount refused')
            ids = self.call(['ps', '-q', name]).splitlines()
            if len(ids) != 1:
                raise ValueError('Exactly one running container required: ' + name)
        env = {name: svc[name].get('environment', {}) for name in SERVICES}
        worker = env['worker']
        if worker.get('BETTING_WORKER_ACCEPTANCE_ENABLED') != 'false' or worker.get('BETTING_WORKER_AUTHORIZATION_MODE') != 'disabled':
            raise ValueError('Acceptance and authority must remain disabled')
        required = [('archiver', 'ARCHIVER_CONSUMER_ENABLED'), ('suspension', 'BETTING_CONTROL_CONSUMER_ENABLED'),
                    ('worker', 'BETTING_WORKER_CONSUMER_ENABLED'), ('notification', 'NOTIFICATION_STORAGE_ENABLED'),
                    ('notification', 'NOTIFICATION_MESSAGING_ENABLED')]
        for name, flag in required:
            if env[name].get(flag) != 'true':
                raise ValueError('Owner must explicitly prepare ' + name + ': ' + flag)
        for name in ['producer', 'match-state', 'dashboard']:
            if env[name].get('MATCH_ID') != self.plan['match_id']:
                raise ValueError('Match registration mismatch: ' + name)
        for name, prefix in [('archiver', 'ARCHIVER'), ('suspension', 'BETTING_CONTROL'), ('worker', 'BETTING_WORKER')]:
            if env[name].get(prefix + '_DB_URL') != 'jdbc:postgresql://postgres:5432/live_jvg':
                raise ValueError('This harness requires isolated common live_jvg database')
        self.worker = worker
        self.sql("SELECT to_regclass('match_state.accepted_event'), to_regclass('dashboard.inbox')")

    def sql(self, statement):
        # Only generated SELECTs are used; identifiers/IDs below are restricted or constants.
        user = self.config['services']['postgres']['environment']['POSTGRES_USER']
        return self.call(['exec', '-T', 'postgres', 'psql', '-X', '-v', 'ON_ERROR_STOP=1',
                          '-U', user, '-d', 'live_jvg', '-Atc', statement])

    def publish_event(self, event):
        self.call(['exec', '-T', 'kafka', '/opt/kafka/bin/kafka-console-producer.sh',
                   '--bootstrap-server', 'kafka:9092', '--topic', 'match_events',
                   '--property', 'parse.key=true', '--property', 'key.separator=|'],
                  event['match_id'] + '|' + json.dumps(event) + '\n')

    def publish_through_producer(self, event):
        request = {key: value for key, value in event.items() if key != 'match_id'}
        response = self.call(['exec', '-T', 'producer', 'curl', '--silent', '--show-error',
                              '--fail', '-X', 'POST', '-H', 'Content-Type: application/json',
                              '--data-binary', '@-', 'http://localhost:8080/api/matches/'
                              + event['match_id'] + '/events'], json.dumps(request))
        if json.loads(response) != event:
            raise AssertionError('Producer did not preserve submitted event identity/payload')

    def observe(self):
        m = self.plan['match_id']
        counts = {key: f"(SELECT count(*) FROM {table} WHERE match_id='{m}')"
                  for key, table in [('match', 'match_state.accepted_event'), ('dashboard', 'dashboard.inbox'),
                                     ('notification', 'notification.inbox'), ('archive', 'archiver.archived_event'),
                                     ('suspension', 'betting_control.processed_event')]}
        fields = ','.join("'" + key + "'," + sql for key, sql in counts.items())
        fields += f",'snapshot',(SELECT row_to_json(x) FROM (SELECT home_score,away_score,var_status FROM dashboard.matches WHERE match_id='{m}') x)"
        fields += f",'market',(SELECT status FROM betting_control.market_state WHERE match_id='{m}')"
        fields += f",'accepted',(SELECT count(*) FROM betting_worker.operation WHERE match_id='{m}' AND status='ACCEPTED')"
        return json.loads(self.sql('SELECT json_build_object(' + fields + ')'))

    def await_state(self, count, step):
        deadline = time.monotonic() + 60
        while True:
            observed = self.observe()
            expected = {key: step[key] for key in ['home_score', 'away_score', 'var_status']}
            if all(observed[key] == count for key in ['match', 'dashboard', 'notification', 'archive', 'suspension']) and observed['snapshot'] == expected:
                if observed['market'] != 'SUSPENDED' or observed['accepted'] != 0:
                    raise AssertionError('Unsafe market/operation state')
                self.observations.append(observed)
                return observed
            if time.monotonic() >= deadline:
                raise AssertionError('Timed out waiting for cross-service persistence: ' + json.dumps(observed))
            time.sleep(1)

    def blocked_command(self):
        exchange = self.worker.get('BETTING_WORKER_EXCHANGE')
        routing = os.environ.get('LIVE_JVG_E2E_WORKER_ROUTING_KEY')
        if not exchange or not routing:
            raise ValueError('Approved worker exchange and routing key required')
        address = self.call(['port', 'rabbitmq', '15672'])
        if not re.fullmatch(r'127\.0\.0\.1:\d+', address):
            raise ValueError('Rabbit management must publish exclusively on loopback')
        rabbit = self.config['services']['rabbitmq']['environment']
        token = base64.b64encode((rabbit['RABBITMQ_DEFAULT_USER'] + ':' + rabbit['RABBITMQ_DEFAULT_PASS']).encode()).decode()
        command = dict(schema_version=1, command_id=self.plan['run']+'-command', operation_id=self.plan['run']+'-operation',
                       match_id=self.plan['match_id'], simulated_user_id='e2e-simulated',
                       timestamp=(datetime.now(timezone.utc)-timedelta(seconds=1)).isoformat().replace('+00:00', 'Z'))
        payload = dict(properties={'delivery_mode': 2, 'content_type': 'application/json', 'message_id': command['command_id']},
                       routing_key=routing, payload=json.dumps(command), payload_encoding='string')
        vhost = os.environ.get('LIVE_JVG_E2E_RABBIT_VHOST', '/')
        url = 'http://' + address + '/api/exchanges/' + quote(vhost, safe='') + '/' + quote(exchange, safe='') + '/publish'
        request = Request(url, data=json.dumps(payload).encode(), headers={'Authorization': 'Basic '+token, 'Content-Type': 'application/json'})
        with urlopen(request, timeout=10) as response:
            if not json.load(response)['routed']:
                raise AssertionError('Command was not routed')
        deadline = time.monotonic() + 60
        while True:
            value = self.sql("SELECT status||':'||coalesce(reason,'') FROM betting_worker.operation WHERE operation_id='" + command['operation_id'] + "'")
            if value.startswith('BLOCKED:'):
                return value
            if value or time.monotonic() >= deadline:
                raise AssertionError('Expected BLOCKED operation, observed ' + (value or 'no operation'))
            time.sleep(1)

    def execute(self):
        self.preflight()
        initial = self.observe()
        if any(initial[key] for key in ['match', 'dashboard', 'notification', 'archive', 'suspension', 'accepted']):
            raise ValueError('Fresh run required; existing evidence will not be overwritten')
        for count, step in enumerate(self.plan['steps'], 1):
            self.publish_through_producer(step['event'])
            self.await_state(count, step)
        self.publish_event(self.plan['steps'][0]['event'])
        # A barrier proves every consumer traversed the duplicate, unlike an immediate count check.
        barrier = dict(self.plan['steps'][-1]['event'], event_id=self.plan['run']+'-barrier')
        self.publish_event(barrier)
        final = self.await_state(7, self.plan['steps'][-1])
        blocked = self.blocked_command()
        self.call(['restart', 'match-state', 'dashboard', 'notification', 'archiver', 'suspension', 'worker'])
        self.await_state(7, self.plan['steps'][-1])
        # Check consumers after restart using another fresh barrier.
        self.publish_event(dict(barrier, event_id=self.plan['run']+'-restart-barrier'))
        recovered = self.await_state(8, self.plan['steps'][-1])
        return dict(status='V1_OBSERVED', observations=self.observations, before_restart=final,
                    recovered=recovered, worker=blocked, pending=self.plan['prepared_only'],
                    limitation='No V2, authority, causal suspension authorization or global E2E certification')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('mode', choices=['plan', 'execute-v1'])
    parser.add_argument('--run', required=True)
    parser.add_argument('--compose-file', action='append', default=[])
    args = parser.parse_args()
    plan = scenarios(args.run)
    output = ROOT / 'tmp' / plan['project']
    output.mkdir(parents=True, exist_ok=True)
    (output / 'plan.json').write_text(json.dumps(plan, indent=2)+'\n')
    if args.mode == 'plan':
        print('PREPARED_ONLY: ' + str(output / 'plan.json'))
        return
    evidence = output / 'result.json'
    if evidence.exists():
        raise ValueError('Result exists; choose a new run rather than overwrite evidence')
    harness = Harness(plan, args.compose_file or [ROOT / 'docker-compose.yml'])
    try:
        result = harness.execute()
    except Exception as failure:
        result = dict(status='FAILED', error_type=type(failure).__name__, observations=harness.observations)
        evidence.write_text(json.dumps(result, indent=2)+'\n')
        raise
    evidence.write_text(json.dumps(result, indent=2)+'\n')
    print('V1 observations saved: ' + str(evidence))


if __name__ == '__main__':
    main()
