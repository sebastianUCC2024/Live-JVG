"""Comprueba la configuración local y externa sin iniciar contenedores ni abrir conexiones a RDS."""
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]


def resolved(overrides=None, web=False):
    env = dict(os.environ)
    # Impide que un .env personal o variables heredadas cambien el caso de prueba.
    for variable in re.findall(r'\$\{([A-Za-z_][A-Za-z_0-9]*)', (ROOT/'docker-compose.yml').read_text(encoding='utf-8')):
        env.pop(variable, None)
    for variable in ('COMPOSE_FILE', 'COMPOSE_PROFILES', 'COMPOSE_PROJECT_NAME'):
        env.pop(variable, None)
    env.update(MVP_DB_PASSWORD='validation-only', MVP_RABBIT_PASSWORD='validation-only')
    env.update(overrides or {})
    with tempfile.NamedTemporaryFile(mode='w', suffix='.env', delete=False) as temporary:
        filename = temporary.name
    try:
        command = ['docker', 'compose', '--project-directory', str(ROOT), '--env-file', filename,
                   '-f', str(ROOT/'docker-compose.yml'), '-p', 'live-jvg-compose-audit', '--profile', 'integration-prepared']
        if web: command += ['--profile', 'web']
        result = subprocess.run(command+['config', '--format', 'json'], env=env, cwd=ROOT,
                                capture_output=True, text=True, check=True, timeout=30)
        return json.loads(result.stdout)
    finally:
        Path(filename).unlink()


class ConsolidationTest(unittest.TestCase):
    def test_database_launcher_selects_local_external_and_rejects_unknown_mode(self):
        shell = shutil.which('sh') or ('C:/Program Files/Git/bin/sh.exe' if os.name == 'nt' else None)
        self.assertTrue(shell, 'Se requiere sh para comprobar el lanzador sin iniciar PostgreSQL')
        # Compose convierte $$ en un dólar literal al preparar el comando del contenedor.
        script = resolved()['services']['postgres']['command'][-1].replace('$$', '$')
        with tempfile.TemporaryDirectory(prefix='jvg-db-launcher-') as directory:
            for name, marker in [('local-double.sh', 'local'), ('external-double.sh', 'external')]:
                path = Path(directory)/name
                path.write_text(f'#!/bin/sh\nprintf "%s\\n" "{marker}" "$@"\n', encoding='ascii')
                path.chmod(0o755)
            # Usa rutas absolutas: ningún fallo del PATH puede ejecutar PostgreSQL o sleep reales.
            script = script.replace('docker-entrypoint.sh', "'"+(Path(directory)/'local-double.sh').as_posix()+"'")
            script = script.replace('exec sleep ', "exec '"+(Path(directory)/'external-double.sh').as_posix()+"' ")
            for mode, expected in [('local', ['local', 'postgres']), ('external', ['external', 'infinity']), ('invalid', [])]:
                with self.subTest(mode=mode):
                    env = dict(os.environ, JVG_DB_MODE=mode, MSYS_NO_PATHCONV='1')
                    env['PATH'] = directory + os.pathsep + env.get('PATH', '')
                    result = subprocess.run([shell, '-ec', script], env=env, capture_output=True, text=True, timeout=10)
                    self.assertEqual(64 if mode == 'invalid' else 0, result.returncode)
                    self.assertEqual(expected, result.stdout.splitlines())

    def test_local_defaults_keep_ports_volumes_and_disabled_optional_consumers(self):
        config = resolved()
        services = config['services']
        self.assertEqual('local', services['postgres']['environment']['JVG_DB_MODE'])
        self.assertEqual('127.0.0.1', services['postgres']['ports'][0]['host_ip'])
        self.assertEqual('55434', str(services['postgres']['ports'][0]['published']))
        self.assertEqual({'postgres_data', 'kafka_data', 'rabbit_data'}, set(config['volumes']))
        for name, key in [('archiver','ARCHIVER'), ('suspension','BETTING_CONTROL'), ('worker','BETTING_WORKER')]:
            self.assertEqual('false', services[name]['environment'][key+'_CONSUMER_ENABLED'])
            self.assertEqual('jdbc:postgresql://postgres:5432/live_jvg', services[name]['environment'][key+'_DB_URL'])
        self.assertEqual('false', services['notification']['environment']['NOTIFICATION_MESSAGING_ENABLED'])
        for name in ('producer', 'dashboard'):
            self.assertEqual('true', services[name]['environment']['SIMULATOR_WRITE_ENABLED'])

    def test_external_configuration_preserves_aws_connections_and_alert_flags(self):
        config = resolved(dict(JVG_DB_MODE='external', JVG_DB_HOST='rds.example.invalid',
            JVG_DB_OPTIONS='?sslmode=require', JVG_PGSSLMODE='require',
            DASHBOARD_ALLOWED_ORIGINS='https://livejvg.duckdns.org', SIMULATOR_AUTO_ENABLED='true',
            MATCH_STATE_ALERTS_ENABLED='true', MATCH_STATE_ALERTS_PUBLICATION_ENABLED='true',
            NOTIFICATION_STORAGE_ENABLED='true', NOTIFICATION_MESSAGING_ENABLED='true',
            NOTIFICATION_LIVE_ALERTS_ENABLED='true', NOTIFICATION_AMQP_AUTO_STARTUP='true'))
        services = config['services']
        for name, key in [('dashboard','DASHBOARD'), ('match-state','MATCH_STATE'), ('archiver','ARCHIVER'),
                          ('suspension','BETTING_CONTROL'), ('worker','BETTING_WORKER'), ('notification','NOTIFICATION')]:
            self.assertEqual('jdbc:postgresql://rds.example.invalid:5432/live_jvg?sslmode=require', services[name]['environment'][key+'_DB_URL'])
        self.assertEqual('external', services['postgres']['environment']['JVG_DB_MODE'])
        self.assertEqual('require', services['postgres']['environment']['PGSSLMODE'])
        self.assertEqual('true', services['notification']['environment']['SPRING_RABBITMQ_LISTENER_SIMPLE_AUTO_STARTUP'])
        self.assertEqual('false', services['archiver']['environment']['SPRING_RABBITMQ_LISTENER_SIMPLE_AUTO_STARTUP'])
        for index in range(7):
            self.assertIn(f'MATCHSTATE_MATCHES_{index}_MATCHID', services['match-state']['environment'])

    def test_frontend_profile_uses_browser_addresses_and_all_ports_remain_loopback(self):
        config = resolved(web=True)
        self.assertIn('frontend', config['services'])
        self.assertEqual('ws://localhost:18080/ws', config['services']['frontend']['build']['args']['VITE_WS_URL'])
        for service in config['services'].values():
            for port in service.get('ports', []): self.assertEqual('127.0.0.1', port['host_ip'])
        self.assertEqual('bridge', config['networks']['default']['driver'])

    def test_per_service_database_override_remains_available(self):
        config = resolved({'ARCHIVER_DB_URL':'jdbc:postgresql://127.0.0.1:6543/archive_test'})
        self.assertEqual('jdbc:postgresql://127.0.0.1:6543/archive_test', config['services']['archiver']['environment']['ARCHIVER_DB_URL'])
        self.assertEqual('jdbc:postgresql://postgres:5432/live_jvg', config['services']['worker']['environment']['BETTING_WORKER_DB_URL'])

    def test_simulation_can_be_disabled_without_an_override_file(self):
        config = resolved({'SIMULATOR_WRITE_ENABLED':'false'})
        for name in ('producer', 'dashboard'):
            self.assertEqual('false', config['services'][name]['environment']['SIMULATOR_WRITE_ENABLED'])


if __name__ == '__main__':
    unittest.main()
