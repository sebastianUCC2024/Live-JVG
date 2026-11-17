"""Offline Draft-07 proposal/fixture and workflow validation; no infrastructure calls.
Requires fastjsonschema and ruamel.yaml. Published Draft2020-12 schemas use their own validator.
"""
import json
from pathlib import Path
import re
import fastjsonschema
from ruamel.yaml import YAML

ROOT = Path(__file__).resolve().parents[1]


def uuid_format(value):
    """Canonical RFC UUID spelling, including uppercase and nil; no braces or compact IDs."""
    return isinstance(value, str) and re.fullmatch(
        r'[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}', value) is not None


def compile_contract(schema):
    if '2020-12' in schema.get('$schema', ''):
        raise ValueError('Use a Draft202012 validator; do not silently ignore newer keywords')
    return fastjsonschema.compile(schema, formats={'uuid': uuid_format})


def main():
    contracts = ROOT / 'services/match-state-service/docs/architecture-closure/contracts'
    paths = sorted(contracts.glob('*.schema.proposed.json'))
    assert paths, 'No proposed schemas found'
    for path in paths:
        validator = compile_contract(json.loads(path.read_text()))
        prefix = path.name.removesuffix('schema.proposed.json')
        fixtures = sorted(contracts.glob(prefix + '*.json'))
        checked = 0
        for fixture in fixtures:
            if fixture == path:
                continue
            invalid = fixture.name.endswith('.invalid.json')
            try:
                validator(json.loads(fixture.read_text()))
            except fastjsonschema.JsonSchemaException:
                if not invalid:
                    raise
            else:
                assert not invalid, fixture
            checked += 1
        assert checked >= 2, path
        print(path.name + ': ' + str(checked) + ' fixtures checked')
    workflow = YAML(typ='safe').load((ROOT / '.github/workflows/validation.yml').read_text())
    assert len(workflow['jobs']['java']['strategy']['matrix']['service']) == 7
    assert {'java', 'react', 'node'} <= workflow['jobs'].keys()
    for job in workflow['jobs'].values():
        assert not job.get('continue-on-error', False)
        for step in job.get('steps', []):
            assert not step.get('continue-on-error', False)
            command = step.get('run', '')
            assert not any(flag in command for flag in ('skipTests', 'maven.test.skip', '|| true'))
    print('Workflow YAML parsed; seven Java services and existing jobs preserved')


if __name__ == '__main__':
    main()
