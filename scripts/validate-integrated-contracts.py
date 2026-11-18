"""Offline Draft2020-12 validation of real producer wire fixtures and academic commands.
Run Notification's local Maven suite first to export its real producer record fixtures.
No remote schema retrieval or infrastructure calls are needed.
"""
import json
from pathlib import Path
from jsonschema import Draft202012Validator, FormatChecker

ROOT = Path(__file__).resolve().parents[1]


def validator(path):
    schema=json.loads(path.read_text())
    if schema.get('$schema') != 'https://json-schema.org/draft/2020-12/schema':
        raise ValueError('Expected explicit Draft2020-12 schema')
    Draft202012Validator.check_schema(schema)
    formats=FormatChecker()
    if formats.conforms('invalid', 'date-time') or formats.conforms('invalid', 'uuid'):
        raise RuntimeError('Required date-time/UUID format dependencies are unavailable')
    return Draft202012Validator(schema,format_checker=formats)


def validate_alert(wire,check):
    check.validate(wire)
    # JSON Schema description delegates these cross-field invariants to real DTOs.
    # Validate them here as well rather than confusing structural validation with coherence.
    snapshot=wire['snapshot']
    for envelope,field in [('match_id','match_id'),('source_event_id','event_id'),('event_type','event_type'),
                           ('timestamp','updated_at'),('reference_event_id','reference_event_id')]:
        if wire[envelope] != snapshot[field]:
            raise ValueError('LiveAlert envelope/snapshot mismatch: '+envelope)


def fixtures():
    alert_path=ROOT/'services/notification-backend/target/live-alert-wire-fixtures.json'
    alerts=json.loads(alert_path.read_text())
    if len(alerts)!=3 or {a['event_type'] for a in alerts}!={'GOAL','VAR_CHECK','GOAL_ANNULLED'}:
        raise ValueError('Expected three real producer record fixtures; never substitute fabricated examples')
    academic_path=ROOT/'services/betting-worker/src/test/resources/contracts/academic-v1.json'
    producer_path=ROOT/'services/betting-suspension-service/src/test/resources/contracts/academic-v1.json'
    if academic_path.read_bytes()!=producer_path.read_bytes():
        raise ValueError('Academic producer/worker golden fixtures diverged')
    commands=json.loads(academic_path.read_text())
    if len(commands)!=2 or {c['command_type'] for c in commands}!={'SUSPEND_BETS','RESUME_BETS'}:
        raise ValueError('Missing academic contract fixtures')
    return alerts,commands


def checks():
    return (validator(ROOT/'contracts/events/live-alert.schema.json'),
            validator(ROOT/'services/betting-suspension-service/docs/academic-command-v1.schema.json'))


def main():
    alert_check,command_check=checks()
    alerts,commands=fixtures()
    for alert in alerts:validate_alert(alert,alert_check)
    for command in commands:command_check.validate(command)
    print('PASS Draft2020-12: 3 real producer LiveAlert wire fixtures, cross-field coherence and 2 academic fixtures')
    print('This does not approve contracts or certify broker delivery, PostgreSQL or E2E')


if __name__=='__main__':main()
