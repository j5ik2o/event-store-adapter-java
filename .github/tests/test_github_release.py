"""release.yml の github-release ジョブの2つの手順を、本物の git と偽の gh で流す（#870）。

- リリースの一覧が読めなければ、作らずに止まる（照会の失敗を「リリースなし」と見ない）。
- ノートの基点は、前にある正式版のタグのうち GitHub のリリースがあるもの（公開されずに飛んだタグを基点にしない）。
- リリースは --latest=false で作り、次の手順が「一覧 ∪ 今のタグ」の最大の版を Latest にする（順序と再実行に依らない）。
"""
import json
import os
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

from release_test_support import FAKE_GH, GITHUB, RELEASE, REPOSITORY, run_body


class GithubReleaseTest(unittest.TestCase):
    def setUp(self):
        self.directory = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, self.directory)
        fake_bin = self.directory / 'bin'
        fake_bin.mkdir()
        (fake_bin / 'gh').write_text(FAKE_GH)
        (fake_bin / 'gh').chmod(0o755)
        self.releases = self.directory / 'releases'
        self.log = self.directory / 'gh-log'
        (self.directory / 'gitconfig').write_text(
            '[user]\n\tname = Test\n\temail = test@example.com\n[init]\n\tdefaultBranch = main\n')
        self.env = {
            **os.environ, 'PATH': f'{fake_bin}{os.pathsep}{os.environ["PATH"]}',
            'GIT_CONFIG_GLOBAL': str(self.directory / 'gitconfig'), 'GIT_CONFIG_NOSYSTEM': '1',
            'FAKE_RELEASES': str(self.releases), 'FAKE_GH_LOG': str(self.log), 'FAKE_PULLS': '/dev/null',
            'FAKE_GH_CALLS': str(self.directory / 'gh-calls'), 'GITHUB_REPOSITORY': REPOSITORY,
            'SERVER_URL': 'https://github.com', 'RUNNER_TEMP': str(self.directory),
        }
        self.work = self.directory / 'work'
        self.git(self.directory, 'init', str(self.work))
        shutil.copytree(GITHUB, self.work / '.github', ignore=shutil.ignore_patterns('__pycache__', 'tests'))
        self.git(self.work, 'add', '.')
        self.git(self.work, 'commit', '-m', 'chore: first')
        self.git(self.work, 'tag', '-a', 'v5.1.0', '-m', 'v5.1.0')
        for version, subject in (('5.2.0', 'feat: in 5.2.0'), ('5.3.0', 'fix: in 5.3.0'), ('5.4.0', 'feat: in 5.4.0'),
                                 ('5.5.0', 'feat: in 5.5.0')):
            self.git(self.work, 'commit', '--allow-empty', '-m', subject)
            self.git(self.work, 'tag', '-a', f'v{version}', '-m', f'v{version}')

    def git(self, cwd, *args):
        return subprocess.run(['git', *args], cwd=cwd, env=self.env, check=True, text=True,
                              capture_output=True).stdout.strip()

    def released(self, *tags):
        self.releases.write_text(''.join(f'{tag}\n' for tag in tags))

    def step(self, step_id, tag, **env):
        return subprocess.run(['bash', '--noprofile', '--norc', '-eo', 'pipefail', '-c', run_body(RELEASE, step_id)],
                              cwd=self.work, env={**self.env, 'GITHUB_WORKSPACE': str(self.work), 'RELEASE_TAG': tag,
                                                  **env}, capture_output=True, text=True)

    def calls(self):
        if not self.log.exists():
            return []
        return [json.loads(line) for line in self.log.read_text().splitlines()]

    def created(self):
        return [call for call in self.calls() if call[:2] == ['release', 'create']]

    def marked(self):
        return [call[2] for call in self.calls() if call[:2] == ['release', 'edit']]

    def release(self, tag, **env):
        created = self.step('github_release', tag, **env)
        self.assertEqual(created.returncode, 0, created.stderr)
        latest = self.step('latest_release', tag, **env)
        self.assertEqual(latest.returncode, 0, latest.stderr)
        return created

    def test_the_notes_start_from_the_nearest_tag_with_a_release(self):
        # v5.3.0 は追い越されて公開されなかった（リリースなし）。v5.4.0 のノートは v5.2.0 からで、5.3.0 の変更も入る
        self.released('v5.2.0')
        result = self.release('v5.4.0')
        self.assertIn('release notes from v5.2.0', result.stdout)
        notes = (self.directory / 'release-notes.md').read_text()
        self.assertIn('v5.2.0...v5.4.0', notes)
        self.assertIn('in 5.3.0', notes)
        self.assertIn('in 5.4.0', notes)
        self.assertEqual(len(self.created()), 1)
        self.assertEqual(self.created()[0][2], 'v5.4.0')
        self.assertIn('--latest=false', self.created()[0])
        self.assertEqual(self.marked(), ['v5.4.0'])

    def test_without_an_earlier_release_the_notes_start_from_the_previous_tag(self):
        self.released()
        result = self.release('v5.4.0')
        self.assertIn('release notes from v5.3.0', result.stdout)

    def test_an_unreadable_release_list_stops_before_anything_is_created(self):
        self.released('v5.2.0')
        for step_id in ('github_release', 'latest_release'):
            with self.subTest(step_id):
                result = self.step(step_id, 'v5.4.0', FAKE_RELEASES_FAIL='1')
                self.assertNotEqual(result.returncode, 0)
        self.assertEqual(self.created(), [])
        self.assertEqual(self.marked(), [])

    def test_an_existing_release_is_not_created_again_and_latest_is_still_set(self):
        self.released('v5.2.0', 'v5.4.0')
        result = self.release('v5.4.0')
        self.assertIn('the GitHub release v5.4.0 already exists', result.stdout)
        self.assertEqual(self.created(), [])
        self.assertEqual(self.marked(), ['v5.4.0'])

    def test_an_older_release_made_later_does_not_take_latest(self):
        # v5.5.0 のリリースのあとで、v5.4.0 の github-release だけが動く（再実行、順の入れ替わり）
        self.released('v5.2.0', 'v5.5.0')
        self.release('v5.4.0')
        self.assertEqual(self.created()[0][2], 'v5.4.0')
        self.assertIn('--latest=false', self.created()[0])
        self.assertEqual(self.marked(), ['v5.5.0'])

    def test_a_release_missing_from_the_list_right_after_creation_is_still_a_candidate(self):
        # 作った直後の一覧に、今のリリースがまだ出ない（設計レビュー M3）。候補に今のタグを入れるので、最大の版を取りこぼさない
        self.released('v5.2.0')
        self.release('v5.4.0', FAKE_RELEASES_LAG='1')
        self.assertEqual(self.marked(), ['v5.4.0'])

    def test_the_notes_do_not_start_from_a_draft_or_a_prerelease(self):
        # 下書き・プレリリースのリリースは、公開を確かめた版ではない（#871 の CodeRabbit の指摘）
        self.releases.write_text('v5.2.0\nv5.3.0\tdraft\n')
        result = self.release('v5.4.0')
        self.assertIn('release notes from v5.2.0', result.stdout)
        self.assertIn('in 5.3.0', (self.directory / 'release-notes.md').read_text())

    def test_a_draft_of_the_current_tag_counts_as_existing(self):
        self.releases.write_text('v5.2.0\nv5.4.0\tdraft\n')
        result = self.release('v5.4.0')
        self.assertIn('the GitHub release v5.4.0 already exists', result.stdout)
        self.assertEqual(self.created(), [])
        self.assertEqual(self.marked(), ['v5.2.0'])

    def test_drafts_and_prereleases_are_not_marked_latest(self):
        # GitHub は下書きとプレリリースを Latest にしない（#871 の Devin の指摘）。今のタグも、一覧で下書きか
        # プレリリースと分かれば候補に入れない
        self.releases.write_text('v5.2.0\nv5.4.0\nv5.5.0\tdraft\n')
        self.release('v5.4.0')
        self.assertEqual(self.marked(), ['v5.4.0'])
        self.log.unlink()
        self.releases.write_text('v5.2.0\nv5.4.0\tprerelease\nv5.5.0\tdraft\n')
        self.release('v5.5.0')
        self.assertEqual(self.marked(), ['v5.2.0'])

    def test_nothing_is_marked_when_no_release_can_be_latest(self):
        self.releases.write_text('v5.5.0\tdraft\n')
        result = self.step('latest_release', 'v5.5.0')
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn('no published vX.Y.Z release can be marked latest', result.stdout)
        self.assertEqual(self.marked(), [])

    def test_any_order_of_runs_ends_with_the_greatest_release_latest(self):
        for order in (('v5.2.0', 'v5.4.0', 'v5.5.0'), ('v5.5.0', 'v5.2.0', 'v5.4.0'), ('v5.4.0', 'v5.5.0', 'v5.4.0')):
            with self.subTest(order=order):
                self.released()
                self.log.unlink(missing_ok=True)
                for tag in order:
                    self.release(tag)
                self.assertEqual(self.marked()[-1], max(order, key=lambda tag: tuple(map(int, tag[1:].split('.')))))


if __name__ == '__main__':
    unittest.main()
