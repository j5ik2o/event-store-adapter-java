import importlib.util
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

SCRIPT = Path(__file__).resolve().parents[1] / 'semver-level.py'
SPEC = importlib.util.spec_from_file_location('semver_level', SCRIPT)
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


def commits(*messages):
    return '\n'.join(f'{subject}\x1f{body}\x1e' for subject, body in messages)


class SemverLevelTest(unittest.TestCase):
    def test_breaking_subjects(self):
        for subject in ['feat!: change API', 'fix(scope)!: change API',
                        'refactor!: change API', 'perf(scope)!: change API']:
            with self.subTest(subject=subject):
                self.assertEqual(MODULE.semver_level(commits((subject, ''))), 'major')

    def test_breaking_body(self):
        for footer in ['BREAKING CHANGE:', 'BREAKING-CHANGE:']:
            with self.subTest(footer=footer):
                self.assertEqual(MODULE.semver_level(commits(
                    ('fix: update API', f'Details\nwith\ttabs\n\n{footer} removed API\n'))), 'major')

    def test_minor(self):
        for subject in ['feat: add API', 'feat(scope): add API', 'revert: undo']:
            with self.subTest(subject=subject):
                self.assertEqual(MODULE.semver_level(commits((subject, ''))), 'minor')

    def test_patch_types(self):
        for kind in ['perf', 'build', 'ci', 'fix', 'docs', 'style', 'refactor',
                     'test', 'chore', 'custom']:
            with self.subTest(kind=kind):
                self.assertEqual(MODULE.semver_level(commits((f'{kind}: update', ''))), 'patch')

    def test_mixed_commits(self):
        patch = ('perf: speed up', 'Details\nwith\ttabs\n')
        minor = ('feat: add API', '')
        major = ('fix: update API', 'BREAKING-CHANGE: remove API\n')
        self.assertEqual(MODULE.semver_level(commits(patch, minor)), 'minor')
        self.assertEqual(MODULE.semver_level(commits(minor, patch)), 'minor')
        self.assertEqual(MODULE.semver_level(commits(patch, minor, major)), 'major')
        self.assertEqual(MODULE.semver_level(commits(major, minor, patch)), 'major')

    def test_empty_defaults_to_patch_for_forced_bump(self):
        for log in ['', '\n', '\x1e\n']:
            with self.subTest(log=log):
                self.assertEqual(MODULE.semver_level(log), 'patch')

    def test_breaking_marker_must_start_body_line(self):
        self.assertEqual(MODULE.semver_level(commits(
            ('fix: mention feat!: in prose', 'Discuss BREAKING CHANGE: as an example\n'))), 'patch')

    def test_cli(self):
        result = subprocess.run([sys.executable, str(SCRIPT)],
                                input=commits(('feat!: change\tAPI', 'Details\nwith\ttabs\n')),
                                text=True, capture_output=True, check=True)
        self.assertEqual(result.stdout, 'major\n')

    def test_real_git_log_format_and_filter(self):
        workflow = SCRIPT.parent / 'workflows' / 'bump-version.yml'
        pattern = next(line.split("--grep='")[1].split("'")[0]
                       for line in workflow.read_text().splitlines() if "--grep='" in line)
        with tempfile.TemporaryDirectory() as directory:
            def git(*args):
                return subprocess.run(['git', '-C', directory, *args], check=True,
                                      text=True, capture_output=True).stdout
            git('init', '-q')
            git('config', 'user.name', 'Test')
            git('config', 'user.email', 'test@example.com')
            git('commit', '--allow-empty', '-qm', 'feat!: change\tAPI\n\nDetails\nwith\ttabs')
            git('commit', '--allow-empty', '-qm', 'fix(scope)!: change API')
            git('commit', '--allow-empty', '-qm', 'perf: speed up\n\nDetails\n\nBREAKING-CHANGE: API')
            log = git('log', '--pretty=format:%s%x1f%b%x1e', '--no-merges',
                      '-P', f'--grep={pattern}')
            self.assertEqual(log.count('\x1e'), 3)
            self.assertEqual(MODULE.semver_level(log), 'major')


if __name__ == '__main__':
    unittest.main()
