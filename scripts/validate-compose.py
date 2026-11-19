"""Valida el Compose resuelto sin acceder al motor Docker ni crear recursos."""
import json
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[1]
JAVA = {'producer', 'match-state', 'dashboard', 'notification', 'archiver', 'suspension', 'worker'}


def validate(config, resources=None):
    services = config['services']
    assert JAVA <= services.keys()
    assert {'postgres', 'kafka', 'rabbitmq', 'kafka-init'} <= services.keys()
    assert len([s for s in services.values() if s.get('build')]) == 7
    assert services['postgres']['image'] == 'postgres:17'
    for name, service in services.items():
        for port in service.get('ports', []):
            assert port.get('host_ip') == '127.0.0.1', name
        for dep in service.get('depends_on', {}):
            assert dep in services, (name, dep)
        for network in service.get('networks', {}):
            assert network in config['networks'], (name, network)
        if name in JAVA:
            assert service['build']['args']['JAR_FILE'].startswith('services/')
        if name in {'postgres', 'kafka', 'rabbitmq', 'dashboard', 'archiver', 'suspension', 'worker', 'notification'}:
            assert service.get('healthcheck', {}).get('test'), name
    for resource in ('volumes', 'networks'):
        for definition in config.get(resource, {}).values():
            assert not definition.get('external', False), resource
            assert definition['name'].startswith(config['name'] + '_'), definition['name']
    for name in ('archiver', 'suspension', 'worker', 'notification'):
        assert services[name]['profiles'] == ['integration-prepared']
    for name, prefix in [('archiver', 'ARCHIVER'), ('suspension', 'BETTING_CONTROL'), ('worker', 'BETTING_WORKER')]:
        env = services[name]['environment']
        assert env[prefix + '_CONSUMER_ENABLED'] == 'false'
        assert env['SPRING_FLYWAY_ENABLED'] == 'false'
        database = (resources['archiver_database'] if name == 'archiver' else resources['database']) if resources else 'live_jvg'
        assert env[prefix + '_DB_URL'] == 'jdbc:postgresql://postgres:5432/' + database
    worker = services['worker']['environment']
    assert worker['BETTING_WORKER_ACCEPTANCE_ENABLED'] == 'false'
    assert worker['BETTING_WORKER_AUTHORIZATION_MODE'] == 'disabled'
    assert services['suspension']['environment']['BETTING_CONTROL_PUBLICATION_ENABLED'] == 'false'
    notification = services['notification']['environment']
    assert notification['NOTIFICATION_STORAGE_ENABLED'] == 'false'
    assert notification['NOTIFICATION_MESSAGING_ENABLED'] == 'false'
    # Compartir la URL no demuestra permisos, funciones ni aislamiento transaccional.
    print('Compose: seven Java services, isolated resources, dependencies and safe flags validated')
    print('Suspension/Worker share database URL; approved distinct roles and migrations remain required')


if __name__ == '__main__':
    result = subprocess.run(['docker', 'compose', '-p', 'live-jvg-dev1-isolated',
                             '--profile', 'integration-prepared', 'config', '--format', 'json'],
                            cwd=ROOT, capture_output=True, text=True, check=True)
    # La configuración resuelta puede contener contraseñas: no se imprime.
    validate(json.loads(result.stdout))
