import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location('planner', Path(__file__).with_name('prepare-isolated-dev3.py'))
planner = importlib.util.module_from_spec(spec)
spec.loader.exec_module(planner)


class PlanTest(unittest.TestCase):
    def test_rejects_injected_or_shared_names(self):
        for token in ('live-jvg-integration', "x';DROP DATABASE x", 'ABCDEF123456', ''):
            with self.assertRaises(ValueError):
                planner.plan(token)

    def test_names_are_new_scoped_and_flags_remain_disabled(self):
        plan = planner.plan('abcdef123456')
        self.assertFalse(plan['executed'])
        self.assertFalse(plan['flags']['acceptance'])
        self.assertEqual('disabled', plan['flags']['authority'])
        self.assertEqual(6, len(set(plan['roles'].values())))
        self.assertNotEqual(plan['database'], planner.plan('abcdef123457')['database'])

    def test_runtime_startup_can_read_authorization_tables_without_writing_them(self):
        plan = planner.plan('abcdef123456')
        sql = plan['post_migration_sql']
        self.assertIn('GRANT SELECT ON betting_worker.operation_authorization TO ' + plan['roles']['worker_app'], sql)
        self.assertIn('GRANT SELECT ON betting_control.authorization_authority,betting_control.authorization_lease TO ' + plan['roles']['control_app'], sql)
        self.assertNotIn('GRANT ALL', sql)

    def test_scripts_refuse_reuse_and_do_not_grant_direct_authorization(self):
        plan = planner.plan('abcdef123456')
        self.assertIn('AS fresh', plan['pre_migration_sql'])
        self.assertIn('\\quit 3', plan['pre_migration_sql'])
        self.assertNotIn('DROP ', plan['pre_migration_sql'])
        grants = [s for s in plan['post_migration_sql'].splitlines() if s.startswith('GRANT ')]
        self.assertFalse(any(('operation_authorization' in s or 'authorization_lease' in s) and not s.startswith('GRANT SELECT ON ') for s in grants))
        self.assertNotIn('PASSWORD', plan['pre_migration_sql'])


if __name__ == '__main__':
    unittest.main()
