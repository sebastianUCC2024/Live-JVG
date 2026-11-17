"""Resolve the OFF-only manifest proposal with dummy secrets. No daemon/DDL/publications."""
import argparse
import importlib.util
import json
import os
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[1]


def module(path, name):
    spec = importlib.util.spec_from_file_location(name, path)
    result = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(result)
    return result


def environment(manifest):
    harness = module(ROOT / 'scripts/e2e-harness.py', 'review_harness')
    resources = harness.resources_for(manifest['run'], manifest)
    roles = manifest['roles']
    groups = manifest['groups']
    values = dict(E2E_DATABASE=resources['database'], E2E_ARCHIVE_DATABASE=resources['archiver_database'],
                  E2E_TOPIC=resources['topic'], E2E_VHOST=resources['vhost'], E2E_EXCHANGE=resources['exchange'],
                  E2E_WORKER_QUEUE=resources['queue'], E2E_MATCH_ID='e2e-'+manifest['run'],
                  E2E_MATCH_STATE_GROUP=groups['match_state'], E2E_ARCHIVE_GROUP=groups['archive'],
                  E2E_CONTROL_GROUP=groups['control'], E2E_KAFKA_SOURCE_ID=manifest['source_ids']['kafka'],
                  E2E_WORKER_SOURCE_ID=manifest['source_ids']['worker'],
                  E2E_DASHBOARD_QUEUE='dev3_it_'+manifest['run']+'_dashboard',
                  E2E_NOTIFICATION_QUEUE='notification.dev3_it_'+manifest['run'])
    for prefix, key in [('ARCHIVER', 'archive_app'), ('CONTROL', 'control_app'), ('WORKER', 'worker_app'),
                        ('MATCH_STATE', 'match_state_app'), ('DASHBOARD', 'dashboard_app'), ('NOTIFICATION', 'notification_app')]:
        values['E2E_'+prefix+'_ROLE'] = roles[key]
        values['E2E_'+prefix+'_PASSWORD'] = 'validation-only-not-a-secret'
    values.update(MVP_DB_PASSWORD='validation-only-not-a-secret', MVP_RABBIT_PASSWORD='validation-only-not-a-secret')
    return values


def validate(manifest):
    expected = module(ROOT / 'scripts/e2e/prepare-review.py', 'review_generator').prepare(manifest['run'], manifest.get('layout', 'common-v1'))
    assert manifest == expected, 'Review manifest drifted from committed DEV 3 source; review/regenerate explicitly'
    env = dict(os.environ, **environment(manifest))
    base = ['docker', 'compose', '-p', manifest['project'], '-f', 'docker-compose.yml',
            '-f', 'scripts/e2e/compose.manifest-proposed.yml', '--profile', 'integration-prepared',
            '--profile', 'provisioning-explicit', 'config']
    subprocess.run(base + ['--quiet'], cwd=ROOT, env=env, capture_output=True, text=True, check=True)
    result = subprocess.run(base + ['--format', 'json'], cwd=ROOT, env=env, capture_output=True, text=True, check=True)
    config = json.loads(result.stdout)  # Never print resolved credentials.
    module(ROOT / 'scripts/validate-compose.py', 'compose_safety').validate(config, manifest['resources'])
    services = config['services']
    resources = manifest['resources']
    # Both profiles above are used for config inspection only, never for startup.
    assert services['kafka-init']['profiles'] == ['provisioning-explicit']
    for name in ['producer', 'dashboard', 'match-state', 'archiver', 'suspension', 'worker', 'notification']:
        assert not services[name].get('depends_on'), name
        env = services[name]['environment']
        for key in ['SPRING_FLYWAY_ENABLED', 'SPRING_RABBITMQ_DYNAMIC', 'SPRING_KAFKA_ADMIN_AUTO_CREATE',
                    'SPRING_KAFKA_LISTENER_AUTO_STARTUP', 'SPRING_RABBITMQ_LISTENER_SIMPLE_AUTO_STARTUP',
                    'SPRING_RABBITMQ_LISTENER_DIRECT_AUTO_STARTUP']:
            assert env[key] == 'false', (name, key)
        assert env['SPRING_SQL_INIT_MODE'] == 'never'
    for name in ['producer', 'dashboard']:
        assert services[name]['environment']['SIMULATOR_WRITE_ENABLED'] == 'false'
    assert services['dashboard']['environment']['SPRING_PROFILES_ACTIVE'] == 'passive'
    assert services['dashboard']['environment']['DASHBOARD_CATALOG_BOOTSTRAP_ENABLED'] == 'false'
    assert services['dashboard']['environment']['DASHBOARD_RABBIT_LISTENER_ENABLED'] == 'false'
    for key in ['KAFKA_LISTENER_AUTO_STARTUP', 'MATCH_STATE_CATALOG_BOOTSTRAP_ENABLED',
                'MATCH_STATE_RECOVERY_ENABLED', 'MATCH_STATE_PUBLICATION_ENABLED']:
        assert services['match-state']['environment'][key] == 'false'
    assert services['postgres']['environment']['POSTGRES_DB'] == resources['database']
    assert resources['topic'] in services['kafka-init']['command']
    assert not services['dashboard'].get('container_name')
    for name in ['producer', 'match-state', 'dashboard']:
        assert services[name]['environment']['MATCH_ID'] == 'e2e-' + manifest['run']
    for name in ['producer', 'match-state', 'archiver', 'suspension']:
        assert services[name]['environment']['MATCH_EVENTS_TOPIC'] == resources['topic']
    assert services['archiver']['environment']['ARCHIVER_GROUP_ID'] == manifest['groups']['archive']
    assert services['suspension']['environment']['BETTING_CONTROL_GROUP_ID'] == manifest['groups']['control']
    assert services['match-state']['environment']['SPRING_KAFKA_CONSUMER_GROUP_ID'] == manifest['groups']['match_state']
    for name in ['match-state', 'dashboard', 'notification', 'suspension', 'worker']:
        assert services[name]['environment']['SPRING_RABBITMQ_VIRTUAL_HOST'] == resources['vhost']
    assert services['worker']['environment']['BETTING_WORKER_EXCHANGE'] == resources['exchange']
    assert services['worker']['environment']['BETTING_WORKER_QUEUE'] == resources['queue']
    assert services['match-state']['environment']['MATCH_STATE_DB_URL'] == services['worker']['environment']['BETTING_WORKER_DB_URL']
    assert services['suspension']['environment']['BETTING_CONTROL_DB_URL'] == services['worker']['environment']['BETTING_WORKER_DB_URL']
    print('OFF-only proposal validated: ' + manifest['project'] + '; no infrastructure executed')
    return config


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--manifest', required=True)
    args = parser.parse_args()
    validate(json.loads(Path(args.manifest).read_text()))
