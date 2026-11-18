"""Read-only checks for local Markdown links and clean-clone documentation."""
import re
import subprocess
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

class DocumentationTest(unittest.TestCase):
    def test_tracked_markdown_links_resolve(self):
        files = subprocess.check_output(['git', 'ls-files', '-z'], cwd=ROOT).decode().split('\0')
        files += ['docs/CLEAN_CLONE.md']
        for name in files:
            if not name.endswith('.md'):
                continue
            path = ROOT / name
            for match in re.finditer(r'\]\(([^)]+)\)', path.read_text(encoding='utf-8')):
                ref = match[1].split('#')[0]
                if ref and ':' not in ref and not ref.startswith('/'):
                    self.assertTrue((path.parent / ref).exists(), (name, ref))

    def test_documented_script_paths_exist(self):
        text = (ROOT / 'docs/CLEAN_CLONE.md').read_text()
        for name in re.findall(r'(?<![\w/])(scripts/[\w/.-]+\.(?:py|sh|cjs))', text):
            self.assertTrue((ROOT / name).is_file(), name)
        self.assertNotIn('/private/tmp/', text)
        self.assertNotIn('/Users/', text)

    def test_private_output_ignored_templates_kept(self):
        ignored = ['.venv/example', '.m2/repository/example', '.npm-cache/example',
                   '.worktrees/example', 'services/dashboard-frontend/test-results/example',
                   'private.key', '.env.local']
        for name in ignored:
            result = subprocess.run(['git', 'check-ignore', '--no-index', '-q', name], cwd=ROOT)
            self.assertEqual(result.returncode, 0, name)
        for name in ['.env.example', 'services/dashboard-frontend/.env.example',
                     'services/historical-archiver/docs/integration.env.example']:
            result = subprocess.run(['git', 'check-ignore', '--no-index', '-q', name], cwd=ROOT)
            self.assertEqual(result.returncode, 1, name)

    def test_multistage_inputs_not_excluded_by_new_rules(self):
        # Static scope check only; a future Docker build must validate actual context semantics.
        lines = (ROOT / '.dockerignore').read_text().splitlines()
        for rule in ['docs', 'services', 'scripts', 'contracts', '**/src', '**/migration', '**/fixtures']:
            self.assertNotIn(rule, lines)
        for module in ['live-feed-producer', 'match-state-service', 'dashboard-backend',
                       'historical-archiver', 'betting-suspension-service', 'betting-worker',
                       'notification-backend']:
            self.assertTrue((ROOT / 'services' / module / 'pom.xml').is_file())
        dockerfile = (ROOT / 'Dockerfile').read_text()
        self.assertIn('dependency:go-offline', dockerfile)
        self.assertIn('COPY . /build', dockerfile)
        self.assertIn('verify', dockerfile)
        self.assertIn('USER 10001:10001', dockerfile)

if __name__ == '__main__':
    unittest.main()
