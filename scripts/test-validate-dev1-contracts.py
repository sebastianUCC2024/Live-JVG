"""Offline regression: UUID formats must be checked, never silently disabled."""
import importlib.util
from pathlib import Path
import unittest
import fastjsonschema

spec=importlib.util.spec_from_file_location('validator',Path(__file__).with_name('validate-dev1-contracts.py'))

class ValidatorTest(unittest.TestCase):
    def setUp(self):
        self.module=importlib.util.module_from_spec(spec)
        spec.loader.exec_module(self.module)
    def test_canonical_uuid_format_is_supported(self):
        check=self.module.compile_contract({'type':'string','format':'uuid'})
        for value in ['11111111-1111-4111-8111-111111111111','00000000-0000-0000-0000-000000000000','AAAAAAAA-AAAA-4AAA-8AAA-AAAAAAAAAAAA']:
            check(value)
    def test_malformed_uuid_is_rejected(self):
        check=self.module.compile_contract({'type':'string','format':'uuid'})
        for value in ['bad','11111111111141118111111111111111','{11111111-1111-4111-8111-111111111111}','11111111-1111-4111-8111-11111111111Z']:
            with self.subTest(value=value),self.assertRaises(fastjsonschema.JsonSchemaValueException):check(value)
    def test_newer_schema_dialect_is_not_silently_ignored(self):
        with self.assertRaises(ValueError):self.module.compile_contract({'$schema':'https://json-schema.org/draft/2020-12/schema','type':'string'})
    def test_other_format_checks_remain_active(self):
        check=self.module.compile_contract({'type':'string','format':'date-time'})
        check('2026-11-17T13:00:00.123456789Z')
        with self.assertRaises(fastjsonschema.JsonSchemaValueException):check('not-an-instant')

if __name__=='__main__':unittest.main()
