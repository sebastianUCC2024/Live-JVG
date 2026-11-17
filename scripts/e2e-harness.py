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


def resources_for(run, manifest=None):
    legacy = dict(database='live_jvg', archiver_database='live_jvg', topic='match_events',
                  exchange=None, queue=None, routing=None, vhost='/')
    if manifest is None:
        return legacy
    if manifest.get('run') != run or manifest.get('project') != 'live-jvg-e2e-' + run:
        raise ValueError('Manifest run/project mismatch')
    resources = manifest['resources']
    if set(resources) != set(legacy):
        raise ValueError('Manifest resource fields mismatch')
    prefix = 'dev3_it_' + run
    layout = manifest.get('layout', 'standalone')
    if layout not in {'common-v1', 'standalone'}:
        raise ValueError('Unknown manifest layout')
    for key, value in resources.items():
        if layout == 'common-v1' and key in {'database', 'archiver_database', 'topic', 'vhost'}:
            if value != legacy[key]:
                raise ValueError('Common V1 resource differs from published proposal: ' + key)
            continue
        if not isinstance(value, str) or len(value) > 63 or not re.fullmatch(re.escape(prefix) + r'[a-z0-9_.-]*', value):
            raise ValueError('Resource must belong to isolated run: ' + key)
        if key in {'database', 'archiver_database'} and not re.fullmatch(r'[a-z0-9_]+', value):
            raise ValueError('Invalid database identifier')
    return dict(resources)


def scenarios(run, timestamp=None, manifest=None):
    if not re.fullmatch(r'[a-f0-9]{12}', run):
        raise ValueError('run must be twelve lowercase hex digits')
    match = 'e2e-' + run
    now = timestamp or (datetime.now(timezone.utc) - timedelta(seconds=1)).isoformat(timespec='milliseconds').replace('+00:00', 'Z')
    instant_key(now)  # Reject malformed/floating local timestamps before producing fixtures.
    def event(suffix, kind, reference=None):
        return dict(event_id=run + '-' + suffix, match_id=match, event_type=kind,
                    team_id='home' if kind == 'GOAL' else None, minute=10,
                    timestamp=now, reference_event_id=run + '-' + reference if reference else None)
    events = [event('g', 'GOAL'), event('v1', 'VAR_CHECK', 'g'),
              event('v2', 'VAR_CHECK'), event('c1', 'MATCH_CONTINUES', 'v1'),
              event('annul', 'GOAL_ANNULLED', 'g'), event('c2', 'MATCH_CONTINUES', 'v2')]
    late_open = event('late-v3', 'VAR_CHECK')
    late_open['timestamp'] = (datetime.fromisoformat(now.replace('Z', '+00:00')) - timedelta(minutes=5)).isoformat(timespec='milliseconds').replace('+00:00', 'Z')
    events += [late_open, event('late-c3', 'MATCH_CONTINUES')]
    steps = []
    # V1 explicitly keeps global closure; do not label it as V2 partial success.
    for e, score, var in zip(events, [1, 1, 1, 1, 0, 0, 0, 0],
                             ['CLEAR', 'IN_PROGRESS', 'IN_PROGRESS', 'CLEAR', 'CLEAR', 'CLEAR', 'IN_PROGRESS', 'CLEAR']):
        steps.append(dict(event=e, home_score=score, away_score=0, var_status=var))
    return dict(run=run, project='live-jvg-e2e-' + run, match_id=match,
                resources=resources_for(run, manifest), steps=steps,
                prepared_only=[
                    {'scenario': 'V2 partial closure', 'expected': 'IN_PROGRESS after c1; CLEAR after c2',
                     'gate': 'Gabriel/Valeria approve discriminator, then wire/persist V2'},
                    {'scenario': 'V2 GOAL_ANNULLED', 'gate': 'approve unambiguous goal AND review identities'},
                    {'scenario': 'Worker rejection caused by suspension',
                     'gate': 'Valeria provides approved authority; disabled mode only proves safe nonacceptance'}])


def instant_key(value):
    """Compare Java Instant spelling without losing nanoseconds or accepting local time."""
    match = re.fullmatch(r'(\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d)(?:\.(\d{1,9}))?(Z|[+-]\d\d:\d\d)', value)
    if not match:
        raise ValueError('Explicit ISO instant required')
    seconds = datetime.fromisoformat(match[1] + match[3].replace('Z', '+00:00'))
    return seconds.astimezone(timezone.utc), int((match[2] or '').ljust(9, '0'))


class Harness:
    def __init__(self, plan, files):
        self.plan = plan
        self.base = ['docker', 'compose', '-p', plan['project']]
        for file in files:
            self.base += ['-f', str(Path(file).resolve())]
        self.base += ['--profile', 'integration-prepared']
        self.config = json.loads(self.call(['config', '--format', 'json']))
        self.observations = []
        self.container_ids = {}

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
        for service in cfg['services'].values():
            for port in service.get('ports', []):
                if port.get('host_ip') != '127.0.0.1':
                    raise ValueError('Published ports must remain on loopback')
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
        resources = self.plan['resources']
        for name, prefix in [('archiver', 'ARCHIVER'), ('suspension', 'BETTING_CONTROL'), ('worker', 'BETTING_WORKER')]:
            database = resources['archiver_database'] if name == 'archiver' else resources['database']
            if env[name].get(prefix + '_DB_URL') != 'jdbc:postgresql://postgres:5432/' + database:
                raise ValueError('Database differs from reviewed manifest: ' + name)
        for name, key in [('match-state', 'MATCH_STATE_DB_URL'), ('dashboard', 'DASHBOARD_DB_URL'),
                          ('notification', 'NOTIFICATION_DB_URL')]:
            if env[name].get(key) != 'jdbc:postgresql://postgres:5432/' + resources['database']:
                raise ValueError('External database refused: ' + name)
        # Refuse profile/property overrides that could supersede the checked flags/URLs.
        for name in SERVICES:
            service = svc[name]
            if service.get('command') or service.get('entrypoint'):
                raise ValueError('Entrypoint/property overrides refused: ' + name)
            for key in env[name]:
                if key.startswith(('SPRING_', 'JAVA_', 'JDK_')) and key not in {
                        'SPRING_FLYWAY_ENABLED', 'SPRING_KAFKA_LISTENER_AUTO_STARTUP',
                        'SPRING_RABBITMQ_LISTENER_SIMPLE_AUTO_STARTUP', 'SPRING_RABBITMQ_VIRTUAL_HOST',
                        'SPRING_KAFKA_CONSUMER_GROUP_ID'}:
                    raise ValueError('Unreviewed runtime override refused: ' + name + '/' + key)
            group = env[name].get('SPRING_KAFKA_CONSUMER_GROUP_ID')
            if group and (name != 'match-state' or group != 'dev3_it_' + self.plan['run'] + '_match_state'):
                raise ValueError('Unreviewed Kafka consumer group: ' + name)
        for name in ['producer', 'match-state', 'archiver', 'suspension']:
            if env[name].get('KAFKA_BOOTSTRAP_SERVERS') != 'kafka:9092':
                raise ValueError('External Kafka refused: ' + name)
            if env[name].get('MATCH_EVENTS_TOPIC', 'match_events') != resources['topic']:
                raise ValueError('Unexpected Kafka topic: ' + name)
        for name in ['match-state', 'dashboard', 'suspension', 'worker', 'notification']:
            prefix = 'NOTIFICATION_RABBIT_' if name == 'notification' else 'RABBITMQ_'
            if env[name].get(prefix + 'HOST') != 'rabbitmq' or str(env[name].get(prefix + 'PORT')) != '5672':
                raise ValueError('External RabbitMQ refused: ' + name)
            if env[name].get('SPRING_RABBITMQ_VIRTUAL_HOST', '/') != resources['vhost']:
                raise ValueError('Rabbit vhost differs from approved publishing destination: ' + name)
        if os.environ.get('LIVE_JVG_E2E_RABBIT_VHOST', resources['vhost']) != resources['vhost']:
            raise ValueError('Rabbit publishing vhost differs from manifest')
        for key, setting in [('exchange', 'BETTING_WORKER_EXCHANGE'), ('queue', 'BETTING_WORKER_QUEUE')]:
            if resources[key] is not None and worker.get(setting) != resources[key]:
                raise ValueError('Worker destination differs from manifest: ' + key)
        for name in SERVICES + ['postgres', 'kafka', 'rabbitmq']:
            self.check_container(name)
        self.worker = worker
        self.sql("SELECT to_regclass('match_state.accepted_event'), to_regclass('dashboard.inbox')")

    def check_container(self, name):
        ids = self.call(['ps', '-q', name]).splitlines()
        if len(ids) != 1:
            raise ValueError('Exactly one running container required: ' + name)
        inspected = subprocess.run(['docker', 'inspect', ids[0]], capture_output=True, text=True, check=True, timeout=15)
        container = json.loads(inspected.stdout)[0]
        labels = container['Config'].get('Labels', {})
        if (labels.get('com.docker.compose.project') != self.plan['project']
                or labels.get('com.docker.compose.service') != name
                or not container['State'].get('Running')):
            raise ValueError('Container ownership/running state mismatch: ' + name)
        declared = self.config['services'][name]
        actual_env = dict(item.split('=', 1) for item in container['Config'].get('Env', []) if '=' in item)
        for key, value in declared.get('environment', {}).items():
            if actual_env.get(key) != str(value):
                raise ValueError('Running environment differs from reviewed config: ' + name + '/' + key)
        volumes = {item['name'] for item in self.config.get('volumes', {}).values()}
        for mount in container.get('Mounts', []):
            if mount.get('Type') != 'volume' or mount.get('Name') not in volumes:
                raise ValueError('Actual container uses an unreviewed mount: ' + name)
        networks = {item['name'] for item in self.config.get('networks', {}).values()}
        if not set(container['NetworkSettings']['Networks']) <= networks:
            raise ValueError('Actual container uses an external network: ' + name)
        previous = self.container_ids.get(name)
        if previous and previous != ids[0]:
            raise ValueError('Container replaced during harness run: ' + name)
        self.container_ids[name] = ids[0]

    def sql(self, statement, database=None):
        # Only generated SELECTs are used; identifiers/IDs below are restricted or constants.
        user = self.config['services']['postgres']['environment']['POSTGRES_USER']
        return self.call(['exec', '-T', 'postgres', 'psql', '-X', '-v', 'ON_ERROR_STOP=1',
                          '-U', user, '-d', database or self.plan['resources']['database'], '-Atc', statement])

    def publish_event(self, event):
        self.call(['exec', '-T', 'kafka', '/opt/kafka/bin/kafka-console-producer.sh',
                   '--bootstrap-server', 'kafka:9092', '--topic', self.plan['resources']['topic'],
                   '--property', 'parse.key=true', '--property', 'key.separator=|'],
                  event['match_id'] + '|' + json.dumps(event) + '\n')

    def publish_through_producer(self, event):
        request = {key: value for key, value in event.items() if key != 'match_id'}
        response = self.call(['exec', '-T', 'producer', 'curl', '--silent', '--show-error',
                              '--fail', '-X', 'POST', '-H', 'Content-Type: application/json',
                              '--data-binary', '@-', 'http://localhost:8080/api/matches/'
                              + event['match_id'] + '/events'], json.dumps(request))
        actual = json.loads(response)
        if actual.keys() != event.keys() or any(actual[key] != event[key] for key in event if key != 'timestamp') or instant_key(actual['timestamp']) != instant_key(event['timestamp']):
            raise AssertionError('Producer did not preserve submitted event identity/payload')

    def observe(self):
        m = self.plan['match_id']
        counts = {key: f"(SELECT count(*) FROM {table} WHERE match_id='{m}')"
                  for key, table in [('match', 'match_state.accepted_event'), ('dashboard', 'dashboard.inbox'),
                                     ('notification', 'notification.inbox'),
                                     ('suspension', 'betting_control.processed_event')]}
        fields = ','.join("'" + key + "'," + sql for key, sql in counts.items())
        fields += f",'snapshot',(SELECT row_to_json(x) FROM (SELECT home_score,away_score,var_status FROM dashboard.matches WHERE match_id='{m}') x)"
        fields += f",'market',(SELECT status FROM betting_control.market_state WHERE match_id='{m}')"
        fields += f",'accepted',(SELECT count(*) FROM betting_worker.operation WHERE match_id='{m}' AND status='ACCEPTED')"
        observed = json.loads(self.sql('SELECT json_build_object(' + fields + ')'))
        observed['archive'] = int(self.sql("SELECT count(*) FROM archiver.archived_event WHERE match_id='" + m + "'",
                                          database=self.plan['resources']['archiver_database']))
        return observed

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
        resources = self.plan['resources']
        routing = resources['routing'] or os.environ.get('LIVE_JVG_E2E_WORKER_ROUTING_KEY')
        if os.environ.get('LIVE_JVG_E2E_WORKER_ROUTING_KEY', routing) != routing:
            raise ValueError('Routing override differs from manifest')
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
        vhost = resources['vhost']
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
        final = self.await_state(count + 1, self.plan['steps'][-1])
        blocked = self.blocked_command()
        for name in SERVICES + ['postgres', 'kafka', 'rabbitmq']:
            self.check_container(name)
        self.call(['restart', 'match-state', 'dashboard', 'notification', 'archiver', 'suspension', 'worker'])
        self.await_state(count + 1, self.plan['steps'][-1])
        # Check consumers after restart using another fresh barrier.
        self.publish_event(dict(barrier, event_id=self.plan['run']+'-restart-barrier'))
        recovered = self.await_state(count + 2, self.plan['steps'][-1])
        return dict(status='V1_OBSERVED', observations=self.observations, before_restart=final,
                    recovered=recovered, worker=blocked, pending=self.plan['prepared_only'],
                    limitation='No V2, authority, causal suspension authorization or global E2E certification')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('mode', choices=['plan', 'execute-v1'])
    parser.add_argument('--run', required=True)
    parser.add_argument('--timestamp', help='Fixed ISO instant for reproducible offline plans')
    parser.add_argument('--manifest', help='Reviewed resource manifest; never executes provisioning statements')
    parser.add_argument('--compose-file', action='append', default=[])
    args = parser.parse_args()
    manifest = json.loads(Path(args.manifest).read_text()) if args.manifest else None
    plan = scenarios(args.run, args.timestamp, manifest)
    output = ROOT / 'tmp' / plan['project']
    output.mkdir(parents=True, exist_ok=True)
    plan_file = output / 'plan.json'
    if plan_file.exists():
        if args.mode == 'plan':
            raise ValueError('Plan exists; preserve evidence and choose a new run')
        saved = json.loads(plan_file.read_text())
        expected = scenarios(args.run, saved['steps'][0]['event']['timestamp'], manifest)
        if saved != expected or (args.timestamp and args.timestamp != saved['steps'][0]['event']['timestamp']):
            raise ValueError('Existing plan differs from reviewed scenario; choose a new run')
        plan = saved
    else:
        with plan_file.open('x') as handle:
            handle.write(json.dumps(plan, indent=2)+'\n')
    if args.mode == 'plan':
        print('PREPARED_ONLY: ' + str(output / 'plan.json'))
        return
    evidence = output / 'result.json'
    if evidence.exists():
        raise ValueError('Result exists; choose a new run rather than overwrite evidence')
    # A run is one-shot, even when preflight fails. Preserve this claim as evidence;
    # a second process must not publish the same plan concurrently.
    with (output / 'execute.claim').open('x') as handle:
        handle.write('Isolated V1 execution claimed; never reuse this run.\n')
    harness = None
    try:
        harness = Harness(plan, args.compose_file or [ROOT / 'docker-compose.yml'])
        result = harness.execute()
    except Exception as failure:
        result = dict(status='FAILED', error_type=type(failure).__name__,
                      observations=harness.observations if harness else [])
        with evidence.open('x') as handle:
            handle.write(json.dumps(result, indent=2)+'\n')
        raise
    with evidence.open('x') as handle:
        handle.write(json.dumps(result, indent=2)+'\n')
    print('V1 observations saved: ' + str(evidence))


if __name__ == '__main__':
    main()
