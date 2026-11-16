"""Requires fastjsonschema and ruamel.yaml; validates proposals, fixtures and workflow YAML."""
import json
from pathlib import Path
import fastjsonschema
from ruamel.yaml import YAML

root = Path(__file__).resolve().parents[1]
contracts = root / 'services/match-state-service/docs/architecture-closure/contracts'
for path in sorted(contracts.glob('*.schema.proposed.json')):
    schema = json.loads(path.read_text())
    validator = fastjsonschema.compile(schema)
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
workflow = YAML(typ='safe').load((root / '.github/workflows/validation.yml').read_text())
assert len(workflow['jobs']['java']['strategy']['matrix']['service']) == 7
assert {'java', 'react', 'node'} <= workflow['jobs'].keys()
for job in workflow['jobs'].values():
    assert not job.get('continue-on-error', False)
    for step in job.get('steps', []):
        assert not step.get('continue-on-error', False)
        command = step.get('run', '')
        assert not any(flag in command for flag in ('skipTests', 'maven.test.skip', '|| true'))
print('Workflow YAML parsed; seven Java services and existing jobs preserved')
