"""Offline entrypoint tests: java is a temporary shell double, never a JVM/service."""
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
SH = shutil.which('sh') or ('C:/Program Files/Git/bin/sh.exe' if os.name == 'nt' else None)
OPTIONS = '-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError'


class RuntimeTest(unittest.TestCase):
    def run_entrypoint(self, overrides=None, arguments=()):
        if not SH:
            self.fail('POSIX sh is required for offline entrypoint tests')
        with tempfile.TemporaryDirectory(prefix='offline-java-double-') as temporary:
            java = Path(temporary) / 'java'
            java.write_text('#!/bin/sh\nprintf "<%s>\\n" "$@"\n', encoding='ascii')
            java.chmod(0o755)
            env = dict(os.environ)
            for key in ['JAVA_OPTS', 'JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS', '_JAVA_OPTIONS']:
                env.pop(key, None)
            env.update(PATH=temporary + os.pathsep + env.get('PATH', ''), MSYS_NO_PATHCONV='1')
            env.update(overrides or {})
            return subprocess.run([SH, str(ROOT / 'scripts/docker/java-entrypoint.sh'), *arguments],
                                  env=env, text=True, capture_output=True, timeout=10)

    def test_defaults_and_exact_options_use_only_fixed_java_arguments(self):
        expected = ['<-XX:+UseContainerSupport>', '<-XX:MaxRAMPercentage=75.0>',
                    '<-XX:+ExitOnOutOfMemoryError>', '<-jar>', '</app/application.jar>']
        for overrides in [{}, {'JAVA_OPTS': OPTIONS}]:
            result = self.run_entrypoint(overrides)
            self.assertEqual(0, result.returncode, result.stderr)
            self.assertEqual(expected, result.stdout.splitlines())

    def test_unsafe_empty_reordered_and_shell_options_are_rejected(self):
        for value in ['', '-Xmx8g', OPTIONS + ' -Dspring.flyway.enabled=true',
                      OPTIONS + ' -javaagent:/tmp/agent.jar', '$(touch /tmp/forbidden)',
                      '; echo forbidden', OPTIONS.replace('75.0', '90.0'),
                      ' '.join(reversed(OPTIONS.split()))]:
            with self.subTest(value=value):
                result = self.run_entrypoint({'JAVA_OPTS': value})
                self.assertEqual(64, result.returncode)
                self.assertEqual('', result.stdout)
                self.assertIn('Unapproved JVM options refused', result.stderr)

    def test_jvm_injection_channels_are_rejected(self):
        for key in ['JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS', '_JAVA_OPTIONS']:
            result = self.run_entrypoint({key: '-Dworker.acceptance.enabled=true'})
            self.assertEqual(64, result.returncode)
            self.assertEqual('', result.stdout)

    def test_application_argument_overrides_are_rejected(self):
        result = self.run_entrypoint(arguments=['--spring.profiles.active=memory'])
        self.assertEqual(64, result.returncode)
        self.assertEqual('', result.stdout)

    def test_entrypoint_has_lf_and_no_eval_or_user_option_expansion(self):
        source = (ROOT / 'scripts/docker/java-entrypoint.sh').read_bytes()
        self.assertNotIn(b'\r', source)
        self.assertNotIn(b'eval ', source)
        self.assertIn(b'exec java ' + OPTIONS.encode() + b' -jar /app/application.jar', source)


if __name__ == '__main__':
    unittest.main()
