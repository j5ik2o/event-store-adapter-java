import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

from release_test_support import GATE_SCRIPT, REPOSITORY, VERSION_FILE, load_gate, pull

gate = load_gate()


class ReleaseGateTest(unittest.TestCase):
    SHA = 'a' * 40
    PARENT = '5.2.1-SNAPSHOT\n'
    TARGET = '5.3.0\n'

    def check(self, mode='tag', parent=PARENT, current=TARGET, changed=(VERSION_FILE,), pulls=None,
              tags=None, on_main=True, tag=None, required=None, parents=1):
        candidate = gate.Candidate(
            repository=REPOSITORY, sha=self.SHA, parent=parent, current=current,
            changed_files=tuple(changed), pulls=tuple([pull(self.SHA)] if pulls is None else pulls),
            tags={'v5.1.0': 'b' * 40, 'v5.2.0': 'c' * 40} if tags is None else tags, on_main=on_main,
            required=required, parents=parents,
        )
        return gate.check_candidate(mode, candidate, tag)

    def assert_rejected(self, message, **kwargs):
        with self.assertRaises(gate.GateError) as raised:
            self.check(**kwargs)
        self.assertIn(message, str(raised.exception))

    def test_squash_of_the_version_pull_request_is_tagged(self):
        self.assertEqual(self.check(), 'v5.3.0')
        self.assertEqual(self.check(parent='5.2.0\n'), 'v5.3.0')
        self.assertEqual(self.check(parent='5.2.1-SNAPSHOT', current='5.3.0'), 'v5.3.0')

    def test_parent_without_a_version_file_is_not_released(self):
        self.assertIsNone(self.check(parent=None, pulls=[]))
        self.assert_rejected('has no version', mode='publish', parent=None, tag='v5.3.0')

    def test_commit_without_a_version_file_fails(self):
        self.assert_rejected('has no version', current=None)

    def test_unchanged_version_is_skipped(self):
        self.assertIsNone(self.check(parent='5.3.0\n', current='5.3.0', pulls=[]))
        self.assertIsNone(self.check(parent=self.PARENT, current=self.PARENT, pulls=[]))

    def test_malformed_version_file_fails(self):
        for text in ('5.3.0\r\n', '﻿5.3.0\n', ' 5.3.0\n', '5.3.0 \n', '5.3.0\n\n', 'v5.3.0\n',
                     '5.3.0+build\n', '', '\n', '5.3.0\n5.4.0\n', '５.３.０\n'):
            with self.subTest(text=text):
                self.assert_rejected('must hold a single version', current=text)
        self.assert_rejected('must hold a single version', parent='5.2.1-SNAPSHOT\r\n')

    def test_line_ending_change_fails(self):
        self.assert_rejected('not the line ending', current='5.3.0')

    def test_version_changed_by_another_pull_request_fails(self):
        self.assert_rejected('is not the squash merge', pulls=[pull(self.SHA, ref='feature/x')])

    def test_commit_without_an_associated_pull_request_asks_for_a_re_run(self):
        self.assert_rejected('no pull request is associated', pulls=[])
        self.assert_rejected('re-run the job', pulls=[])

    def test_fork_pull_request_fails(self):
        self.assert_rejected('is not the squash merge', pulls=[pull(self.SHA, repo='someone/r')])
        self.assert_rejected('is not the squash merge',
                             pulls=[{**pull(self.SHA), 'head': {'ref': 'release/v5.3.0', 'repo': None}}])

    def test_unmerged_or_other_commit_fails(self):
        self.assert_rejected('is not the squash merge', pulls=[pull(self.SHA, merged=False)])
        self.assert_rejected('is not the squash merge', pulls=[pull('d' * 40)])
        self.assert_rejected('is not the squash merge', pulls=[pull(self.SHA, base='develop')])
        self.assert_rejected('is not the squash merge', pulls=[pull(self.SHA), pull(self.SHA)])

    def test_other_changes_fail(self):
        self.assert_rejected('must change only version', changed=(VERSION_FILE, 'core/build.gradle.kts'))
        self.assert_rejected('must change only version', changed=('build.gradle.kts',))

    def test_downgrade_fails(self):
        self.assert_rejected('must be greater than the previous version 5.3.1', parent='5.3.1\n')
        self.assert_rejected('must be greater than the previous version 5.3.0-SNAPSHOT',
                             parent='5.3.0-SNAPSHOT\n', current='5.2.9\n',
                             pulls=[pull(self.SHA, ref='release/v5.2.9')])

    def test_version_not_above_the_latest_tag_fails(self):
        self.assert_rejected('must be greater than the latest tag v5.4.0', tags={'v5.4.0': 'b' * 40})

    def test_non_release_target_is_not_tagged(self):
        for version in ('5.3.1-SNAPSHOT', '5.4.0-rc.1'):
            with self.subTest(version=version):
                self.assertIsNone(self.check(parent='5.3.0\n', current=version + '\n', pulls=[]))
        self.assert_rejected('is not a release version', mode='publish', current='5.4.0-rc.1\n',
                             tag='v5.4.0-rc.1')

    def test_commit_off_main_fails(self):
        self.assert_rejected('is not reachable from main', on_main=False)

    def test_merge_commit_fails(self):
        self.assert_rejected('has 2 parents; only a squash merge is released', parents=2)
        self.assert_rejected('has 2 parents', mode='publish', parents=2, tags={'v5.3.0': self.SHA}, tag='v5.3.0')

    def test_existing_tag(self):
        self.assertIsNone(self.check(tags={'v5.2.0': 'c' * 40, 'v5.3.0': self.SHA}))
        self.assertIsNone(self.check(tags={'v5.3.0': self.SHA, 'v5.4.0': 'f' * 40}))
        self.assert_rejected('already exists on another commit', tags={'v5.3.0': 'e' * 40})

    def test_required_version_must_be_published_first(self):
        waiting = gate.Required('5.2.0', published=False)
        self.assert_rejected('must wait until 5.2.0 is published', required=waiting)
        self.assertEqual(self.check(required=gate.Required('5.2.0', published=True)), 'v5.3.0')

    def test_publish_mode(self):
        tags = {'v5.2.0': 'c' * 40, 'v5.3.0': self.SHA}
        self.assertEqual(self.check(mode='publish', tags=tags, tag='v5.3.0'), 'v5.3.0')
        self.assert_rejected('does not point at', mode='publish', tags={'v5.3.0': 'e' * 40}, tag='v5.3.0')
        self.assert_rejected('does not point at', mode='publish', tags={'v5.2.0': 'c' * 40}, tag='v5.3.0')
        self.assert_rejected('does not match the version', mode='publish', tags=tags, tag='v5.3.1')
        self.assert_rejected('must be greater than the latest tag v5.4.0', mode='publish',
                             tags={**tags, 'v5.4.0': 'f' * 40}, tag='v5.3.0')
        self.assert_rejected('is not the squash merge', mode='publish', tags=tags, tag='v5.3.0',
                             pulls=[pull(self.SHA, ref='feature/x')])

    def test_latest_tag(self):
        names = ['v5.2.0', 'v5.10.0', 'v5.9.1', 'v6.0.0-rc.1', 'v$(id)', 'release', '5.11.0', 'v05.0.0',
                 'V7.0.0', 'v5.11.0+build']
        self.assertEqual(gate.latest_tag(names), 'v5.10.0')
        self.assertIsNone(gate.latest_tag(['V5.2.0', 'v05.2.0']))

    def test_remote_tags_use_the_peeled_commit(self):
        self.assertEqual(
            gate.remote_tags('1' * 40 + '\trefs/tags/v5.3.0\n' + '2' * 40 + '\trefs/tags/v5.3.0^{}\n'
                             + '3' * 40 + '\trefs/tags/v5.2.0\n'),
            {'v5.3.0': '2' * 40, 'v5.2.0': '3' * 40})

    def test_set_version_keeps_the_line_ending(self):
        self.assertEqual(gate.set_version('5.2.1-SNAPSHOT\n', '5.3.0'), '5.3.0\n')
        self.assertEqual(gate.set_version('5.2.1-SNAPSHOT', '5.3.0'), '5.3.0')
        with self.assertRaises(gate.GateError):
            gate.set_version('5.2.1-SNAPSHOT\r\n', '5.3.0')
        with self.assertRaises(gate.GateError):
            gate.set_version('5.2.1-SNAPSHOT\n', '5.3.0-rc.1')

    def cli(self, *args, stdin='', cwd=None):
        return subprocess.run([sys.executable, str(GATE_SCRIPT), *args], input=stdin, cwd=cwd,
                              text=True, capture_output=True, check=False)

    def test_cli_latest_tag(self):
        self.assertEqual(self.cli('latest-tag', stdin='v5.1.0\nv5.2.0\nv6.0.0-rc.1\n').stdout, 'v5.2.0\n')
        self.assertEqual(self.cli('latest-tag', stdin='').stdout, '')

    def test_cli_set_version(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'version'
            path.write_bytes(b'5.2.1-SNAPSHOT\n')
            result = self.cli('set-version', '5.3.0', cwd=directory)
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertEqual(path.read_bytes(), b'5.3.0\n')
            path.write_bytes(b'5.2.1-SNAPSHOT\r\n')
            result = self.cli('set-version', '5.3.0', cwd=directory)
            self.assertEqual(result.returncode, 1)
            self.assertIn('::error::', result.stderr)
            self.assertEqual(path.read_bytes(), b'5.2.1-SNAPSHOT\r\n')

    def check_cli(self, directory, *extra, parent=b'5.2.1-SNAPSHOT\n'):
        root = Path(directory)
        if parent is not None:
            (root / 'parent-version').write_bytes(parent)
        (root / 'version').write_bytes(b'5.3.0\n')
        (root / 'changed-files').write_text('version\n')
        (root / 'pulls.json').write_text(json.dumps([pull(self.SHA)]))
        (root / 'remote-tags').write_text('c' * 40 + '\trefs/tags/v5.2.0\n')
        (root / 'parents').write_text('1\n')
        return self.cli('check', '--mode', 'tag', '--repository', REPOSITORY, '--sha', self.SHA,
                        '--on-main', 'true', '--parents', str(root / 'parents'), '--parent-version', str(root / 'parent-version'),
                        '--version-file', str(root / 'version'), '--changed-files', str(root / 'changed-files'),
                        '--pulls', str(root / 'pulls.json'), '--remote-tags', str(root / 'remote-tags'), *extra)

    def test_cli_check(self):
        with tempfile.TemporaryDirectory() as directory:
            result = self.check_cli(directory)
            self.assertEqual((result.returncode, result.stdout), (0, 'v5.3.0\n'), result.stderr)
            result = self.check_cli(directory, '--required-version', '5.2.0', '--required-published', 'false')
            self.assertEqual((result.returncode, result.stdout), (1, ''))
            self.assertIn('::error::5.3.0 must wait until 5.2.0 is published', result.stderr)
            result = self.check_cli(directory, '--required-version', '5.2.0')
            self.assertEqual(result.returncode, 1)
        with tempfile.TemporaryDirectory() as directory:
            result = self.check_cli(directory, parent=None)
            self.assertEqual((result.returncode, result.stdout), (0, ''), result.stderr)
            self.assertIn('has no version', result.stderr)
        with tempfile.TemporaryDirectory() as directory:
            result = self.check_cli(directory, parent=b'\xff5.2.1\n')
            self.assertEqual(result.returncode, 1)
            self.assertIn('is not UTF-8', result.stderr)


if __name__ == '__main__':
    unittest.main()
