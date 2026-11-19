"""Check Java dependency direction without starting services or resolving dependencies.

Application owns use cases and ports; domain owns business rules. Frameworks and
transport/persistence implementations must remain outside both inner layers.
Only production sources are checked: adapter-oriented tests may use Spring mocks.
"""
from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parents[1]


def violations(root: Path) -> list[str]:
    errors = []
    for module in sorted((root / 'services').iterdir()):
        java = module / 'src/main/java'
        if not java.exists():
            continue
        for source in sorted(java.rglob('*.java')):
            text = source.read_text(encoding='utf-8')
            # Strip comments before looking for declarations and qualified references.
            text = re.sub(r'/\*.*?\*/|//[^\n]*', '', text, flags=re.S)
            declaration = re.search(r'\bpackage\s+([\w.]+)\s*;', text)
            if not declaration:
                continue
            package = declaration.group(1)
            parts = package.split('.')
            layer = next((part for part in ('domain', 'application') if part in parts), None)
            if layer is None:
                continue
            prefix = package.split('.' + layer)[0]
            for dependency in re.findall(r'\bimport\s+(?:static\s+)?([\w.*]+)\s*;', text):
                allowed = dependency.startswith('java.') or dependency.startswith(prefix + '.domain.')
                if layer == 'application':
                    allowed = allowed or dependency.startswith(prefix + '.application.')
                if not allowed:
                    errors.append(f'{source.relative_to(root)}: {layer} imports {dependency}')
            # Fully qualified references must not bypass the import rule.
            for forbidden in ('org.springframework.', 'jakarta.persistence.', 'com.fasterxml.',
                              'org.apache.kafka.', 'com.rabbitmq.', prefix + '.infrastructure.',
                              prefix + '.entrypoint.', prefix + '.config.'):
                if forbidden in text:
                    errors.append(f'{source.relative_to(root)}: {layer} references {forbidden}')
            if layer == 'domain' and prefix + '.application.' in text:
                errors.append(f'{source.relative_to(root)}: domain depends on application')
    return errors


if __name__ == '__main__':
    errors = violations(ROOT)
    if errors:
        print('\n'.join(errors), file=sys.stderr)
        sys.exit(1)
    print('Architecture validated: domain and application depend only on Java and inner layers.')
