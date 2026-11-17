"""Offline regressions for format, identity and full schema-dialect validation."""
import copy
import importlib.util
from pathlib import Path
import unittest
from jsonschema import ValidationError
spec=importlib.util.spec_from_file_location('contracts',Path(__file__).with_name('validate-integrated-contracts.py'))
v=importlib.util.module_from_spec(spec);spec.loader.exec_module(v)

class IntegratedContractTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.alert_check,cls.command_check=v.checks()
        cls.alerts,cls.commands=v.fixtures()
    def test_real_fixtures_pass(self):
        for alert in self.alerts:v.validate_alert(alert,self.alert_check)
        for command in self.commands:self.command_check.validate(command)
    def test_uuid_timestamp_and_extra_fields_rejected(self):
        for field,value in [('alert_id','bad'),('timestamp','not-a-date'),('unknown',True),('contract_version','2.0')]:
            wire=copy.deepcopy(self.alerts[0]);wire[field]=value
            with self.subTest(field=field),self.assertRaises(ValidationError):v.validate_alert(wire,self.alert_check)
    def test_cross_field_coherence_is_checked_beyond_schema(self):
        wire=copy.deepcopy(self.alerts[0]);wire['snapshot']['match_id']='different'
        self.alert_check.validate(wire)  # Structure alone cannot prove the DTO invariant.
        with self.assertRaises(ValueError):v.validate_alert(wire,self.alert_check)
        wire=copy.deepcopy(next(a for a in self.alerts if a['event_type']=='GOAL_ANNULLED'))
        wire['reference_event_id']=None
        with self.assertRaises(ValidationError):v.validate_alert(wire,self.alert_check)
    def test_academic_unknown_fields_versions_and_actions_rejected(self):
        for field,value in [('schema_version',2),('command_type','ACCEPTED'),('unknown',True),('timestamp','invalid')]:
            command=copy.deepcopy(self.commands[0]);command[field]=value
            with self.subTest(field=field),self.assertRaises(ValidationError):self.command_check.validate(command)
if __name__=='__main__':unittest.main()
