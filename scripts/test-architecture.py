"""Regression checks for dependency direction, including fully qualified bypasses."""
import importlib.util
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location('architecture', Path(__file__).with_name('validate-architecture.py'))
architecture = importlib.util.module_from_spec(spec)
spec.loader.exec_module(architecture)


class DependencyDirectionTest(unittest.TestCase):
    def check_source(self, layer, body):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / 'services/example/src/main/java/com/live_jgv/example' / layer / 'Example.java'
            source.parent.mkdir(parents=True)
            source.write_text(f'package com.live_jgv.example.{layer};\n{body}', encoding='utf-8')
            return architecture.violations(root)

    def test_application_can_use_domain_and_application_ports(self):
        self.assertEqual([], self.check_source('application',
            'import com.live_jgv.example.domain.Match; import com.live_jgv.example.application.port.Store;'))

    def test_domain_cannot_depend_on_application(self):
        self.assertTrue(self.check_source('domain', 'import com.live_jgv.example.application.Store;'))

    def test_inner_layers_reject_frameworks_and_adapters(self):
        for layer in ('domain', 'application'):
            for dependency in ('org.springframework.stereotype.Service', 'jakarta.persistence.Entity',
                               'com.fasterxml.jackson.databind.ObjectMapper',
                               'com.live_jgv.example.infrastructure.Store'):
                with self.subTest(layer=layer, dependency=dependency):
                    self.assertTrue(self.check_source(layer, f'import {dependency};'))

    def test_fully_qualified_reference_cannot_bypass_import_check(self):
        self.assertTrue(self.check_source('application',
            'class Example { org.springframework.jdbc.core.JdbcTemplate jdbc; }'))

    def test_comments_do_not_create_false_dependencies(self):
        self.assertEqual([], self.check_source('domain',
            '/** No org.springframework.stereotype.Service dependency. */ class Example {}'))


if __name__ == '__main__':
    unittest.main()
