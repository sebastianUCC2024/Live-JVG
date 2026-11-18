"""Offline checks of shared schemas against existing wire fixtures; no brokers/services.
Run with the existing isolated validation venv: python scripts/test-published-contracts.py
Requires jsonschema with date-time format support. No schemas are fetched remotely.
"""
import copy
import json
from pathlib import Path
import unittest
from jsonschema import Draft202012Validator, FormatChecker

ROOT = Path(__file__).resolve().parents[1]
REVIEW = ROOT / 'services/match-state-service/docs/architecture-closure/contracts'
SCHEMA_DIR = ROOT / 'contracts/events'
NAMES = ('match-event', 'score-update', 'betting-command')


def load(path):
    return json.loads(path.read_text(encoding='utf-8'))


class PublishedContractTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.schemas = {name: load(SCHEMA_DIR / (name + '.schema.json')) for name in NAMES}
        formats = FormatChecker()
        if formats.conforms('invalid', 'date-time'):
            raise RuntimeError('date-time assertion support is required')
        cls.validators = {name: Draft202012Validator(schema, format_checker=formats)
                          for name, schema in cls.schemas.items()}
        cls.valid = {name: load(REVIEW / (name + '.v1.valid.json')) for name in NAMES}

    def assert_valid(self, name, wire):
        self.validators[name].validate(wire)

    def assert_invalid(self, name, wire):
        self.assertTrue(list(self.validators[name].iter_errors(wire)), (name, wire))

    def test_all_three_schemas_use_a_valid_explicit_dialect(self):
        for name, schema in self.schemas.items():
            with self.subTest(schema=name):
                self.assertEqual('https://json-schema.org/draft/2020-12/schema', schema['$schema'])
                Draft202012Validator.check_schema(schema)

    def test_all_existing_review_valid_and_invalid_fixtures(self):
        for name in NAMES:
            fixtures = sorted(REVIEW.glob(name + '.v1.*.json'))
            checked = 0
            for path in fixtures:
                if not path.name.endswith(('.valid.json', '.invalid.json')):
                    continue
                with self.subTest(fixture=path.name):
                    if path.name.endswith('.invalid.json'):
                        self.assert_invalid(name, load(path))
                    else:
                        self.assert_valid(name, load(path))
                checked += 1
            self.assertGreaterEqual(checked, 4)

    def test_captured_producer_events_match_both_consumer_fixtures(self):
        paths = [ROOT / 'services' / module / 'src/test/resources/fixtures/producer-events.jsonl'
                 for module in ('historical-archiver', 'betting-suspension-service')]
        self.assertEqual(paths[0].read_bytes(), paths[1].read_bytes())
        for row in paths[0].read_text().splitlines():
            self.assert_valid('match-event', json.loads(row))

    def test_match_schema_preserves_the_existing_producer_descriptor(self):
        # Producer already enforces full descriptor equality when the shared file is populated.
        self.assertEqual(load(ROOT / 'services/live-feed-producer/docs/match-event.schema.proposed.json'),
                         self.schemas['match-event'])

    def test_captured_score_updates_consumed_by_dashboard(self):
        rows = load(ROOT / 'services/dashboard-backend/src/test/resources/fixtures/dev1-score-updates.json')
        self.assertEqual(5, len(rows))
        for row in rows:
            self.assert_valid('score-update', row)

    def test_worker_legacy_wire_fixtures(self):
        folder = ROOT / 'services/betting-worker/src/test/resources/contracts/dev1-pr22'
        for path in sorted(folder.glob('betting-command.v1.*.json')):
            with self.subTest(fixture=path.name):
                if path.name.endswith('.invalid.json'):
                    self.assert_invalid('betting-command', load(path))
                else:
                    self.assert_valid('betting-command', load(path))

    def test_missing_required_fields_and_nulls_are_rejected(self):
        for name in NAMES:
            for field in self.schemas[name]['required']:
                for replacement in ('missing', 'null'):
                    wire = copy.deepcopy(self.valid[name])
                    if replacement == 'missing':
                        del wire[field]
                    else:
                        wire[field] = None
                    with self.subTest(schema=name, field=field, replacement=replacement):
                        self.assert_invalid(name, wire)

    def test_unknown_fields_and_invalid_timestamps_are_rejected(self):
        for name in NAMES:
            time_field = 'updated_at' if name == 'score-update' else 'timestamp'
            for field, value in [('unknown_field', True), (time_field, 'invalid'),
                                 (time_field, '2026-02-30T12:00:00Z'),
                                 (time_field, '2026-11-17T12:00:00')]:
                wire = copy.deepcopy(self.valid[name]); wire[field] = value
                with self.subTest(schema=name, field=field, value=value):
                    self.assert_invalid(name, wire)

    def test_non_uuid_ids_and_nanosecond_timestamps_remain_valid(self):
        for name in NAMES:
            wire = copy.deepcopy(self.valid[name])
            time_field = 'updated_at' if name == 'score-update' else 'timestamp'
            wire[time_field] = '2026-11-17T12:00:00.123456789Z'
            self.assert_valid(name, wire)  # Existing fixture IDs are intentionally not UUIDs.

    def test_event_minute_bounds_types_and_goal_team(self):
        for minute in (0, 130):
            wire = copy.deepcopy(self.valid['match-event']); wire['minute'] = minute
            self.assert_valid('match-event', wire)
        for minute in (-1, 131, '10', 1.5, True):
            wire = copy.deepcopy(self.valid['match-event']); wire['minute'] = minute
            self.assert_invalid('match-event', wire)
        for team in (None, '', '   ', 17):
            wire = copy.deepcopy(self.valid['match-event']); wire['team_id'] = team
            self.assert_invalid('match-event', wire)

    def test_annulment_requires_original_goal_reference_in_both_event_types(self):
        for name in ('match-event', 'score-update'):
            for reference in (None, '', '   ', 42):
                wire = copy.deepcopy(self.valid[name]); wire['event_type'] = 'GOAL_ANNULLED'
                wire['reference_event_id'] = reference
                self.assert_invalid(name, wire)
            wire['reference_event_id'] = 'original-goal-id'
            self.assert_valid(name, wire)
            del wire['reference_event_id']
            self.assert_invalid(name, wire)

    def test_optional_absent_and_null_fields_remain_supported(self):
        for name in ('match-event', 'score-update'):
            wire = copy.deepcopy(self.valid[name]); wire.pop('reference_event_id', None)
            self.assert_valid(name, wire)
            wire['reference_event_id'] = None
            self.assert_valid(name, wire)
        wire = copy.deepcopy(self.valid['match-event']); wire['event_type'] = 'MATCH_CONTINUES'
        wire.pop('team_id', None); self.assert_valid('match-event', wire)
        wire['team_id'] = None; self.assert_valid('match-event', wire)

    def test_event_enums_scores_and_var_status(self):
        for name in ('match-event', 'score-update'):
            wire = copy.deepcopy(self.valid[name]); wire['event_type'] = 'UNKNOWN'
            self.assert_invalid(name, wire)
        for field, values in [('home_score', [-1, 2147483648, 1.5, '1', True]),
                              ('away_score', [-1, 2147483648, 1.5, '1', True]),
                              ('var_status', ['OPEN', 'SUSPENDED', 'UNKNOWN'])]:
            for value in values:
                wire = copy.deepcopy(self.valid['score-update']); wire[field] = value
                self.assert_invalid('score-update', wire)
        for state in ('CLEAR', 'IN_PROGRESS'):
            wire = copy.deepcopy(self.valid['score-update']); wire['var_status'] = state
            self.assert_valid('score-update', wire)

    def test_worker_version_and_financial_authority_fields(self):
        for version in (0, 2, '1', True):
            wire = copy.deepcopy(self.valid['betting-command']); wire['schema_version'] = version
            self.assert_invalid('betting-command', wire)
        for field in ('amount', 'market_status', 'grant', 'lease', 'authorization'):
            wire = copy.deepcopy(self.valid['betting-command']); wire[field] = 'not-authority'
            self.assert_invalid('betting-command', wire)

    def test_academic_commands_are_not_legacy_simulated_operations(self):
        academic = load(ROOT / 'services/betting-worker/src/test/resources/contracts/academic-v1.json')
        for wire in academic:
            self.assert_invalid('betting-command', wire)

    def test_mathematical_integer_does_not_certify_json_token_spelling(self):
        # Standard JSON Schema cannot distinguish 1 from 1.0 after numeric interpretation.
        # Jackson's stricter lexical checks remain necessary and are not replaced here.
        wire = copy.deepcopy(self.valid['betting-command']); wire['schema_version'] = 1.0
        self.assert_valid('betting-command', wire)


if __name__ == '__main__':
    unittest.main(verbosity=2)
