import importlib.util
from pathlib import Path
import subprocess
import sys
import unittest

SCRIPT = Path(__file__).resolve().parents[1] / 'validate-version.py'
SPEC = importlib.util.spec_from_file_location('validate_version', SCRIPT)
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)

TAGS = ['v1.2.91', 'v2.0.0']


def run_cli(version, tags):
    return subprocess.run([sys.executable, str(SCRIPT), version],
                          input='\n'.join(tags) + '\n', text=True, capture_output=True)


class ValidateVersionTest(unittest.TestCase):
    def test_accepts_greater_version(self):
        self.assertEqual(MODULE.validate_version('5.0.0', TAGS), '5.0.0')

    def test_accepts_when_no_tags(self):
        self.assertEqual(MODULE.validate_version('5.0.0', []), '5.0.0')
        self.assertEqual(MODULE.validate_version('5.0.0', ['']), '5.0.0')

    def test_rejects_malformed_versions(self):
        for version in ['v5.0.0', '5.0', '5.0.0-rc.1', '5.0.0+build', '5.0.0.0',
                        ' 5.0.0', '5.0.0\n', '', '５.０.０']:
            with self.subTest(version=version):
                with self.assertRaises(ValueError):
                    MODULE.validate_version(version, TAGS)

    def test_rejects_same_as_latest(self):
        with self.assertRaises(ValueError):
            MODULE.validate_version('2.0.0', TAGS)

    def test_rejects_smaller_than_latest(self):
        with self.assertRaises(ValueError):
            MODULE.validate_version('1.9.9', TAGS)

    def test_compares_numerically(self):
        self.assertEqual(MODULE.validate_version('2.0.10', ['v2.0.9']), '2.0.10')

    def test_rejects_existing_tag_name(self):
        with self.assertRaises(ValueError):
            MODULE.validate_version('5.0.0', ['v2.0.0', 'v5.0.0'])

    def test_rejects_existing_tag_even_if_not_latest(self):
        with self.assertRaises(ValueError):
            MODULE.validate_version('3.0.0', ['v3.0.0', 'v4.0.0'])

    def test_rejects_incomparable_latest_tag(self):
        with self.assertRaises(ValueError):
            MODULE.validate_version('5.0.0', ['v1.0.0', 'v2.0.0-rc1'])

    def test_cli_success(self):
        result = run_cli('5.0.0', TAGS)
        self.assertEqual(result.returncode, 0)
        self.assertEqual(result.stdout, '5.0.0\n')

    def test_cli_failure_has_no_output(self):
        result = run_cli('2.0.0', TAGS)
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(result.stdout, '')
        self.assertNotEqual(result.stderr, '')

    def test_cli_requires_version_argument(self):
        result = subprocess.run([sys.executable, str(SCRIPT)], input='',
                                text=True, capture_output=True)
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(result.stdout, '')


if __name__ == '__main__':
    unittest.main()
