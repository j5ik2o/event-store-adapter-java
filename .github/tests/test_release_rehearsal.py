import json
import os
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

from release_test_support import BUMP, GITHUB, REPOSITORY, VERSION_FILE, outputs, pull, run_body

FAKE_GH = '''#!/bin/sh
# 偽の gh。api は FAKE_GH_FAIL 回だけ失敗し、続く FAKE_GH_EMPTY 回は [] を返し、その後に $FAKE_PULLS を返す。
calls=$(cat "$FAKE_GH_CALLS" 2>/dev/null || echo 0)
calls=$((calls + 1))
echo "$calls" > "$FAKE_GH_CALLS"
if [ "$calls" -le "${FAKE_GH_FAIL:-0}" ]; then
  echo "HTTP 502" >&2
  exit 1
fi
if [ "$calls" -le $(( ${FAKE_GH_FAIL:-0} + ${FAKE_GH_EMPTY:-0} )) ]; then
  echo '[]'
  exit 0
fi
cat "$FAKE_PULLS"
'''


class ReleaseRehearsalTest(unittest.TestCase):
    """bare のリモートに対して、版の枝 → squash 相当のマージ → 関門 → 打つはずのタグの報告までを手元で通す。"""

    def setUp(self):
        self.directory = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, self.directory)
        self.remote = self.directory / 'remote.git'
        self.work = self.directory / 'work'
        fake_bin = self.directory / 'bin'
        fake_bin.mkdir()
        (fake_bin / 'gh').write_text(FAKE_GH)
        (fake_bin / 'gh').chmod(0o755)
        self.pulls = self.directory / 'pulls.json'
        self.pulls.write_text('[]')
        self.summary = self.directory / 'summary.md'
        self.env = {
            **os.environ,
            'PATH': f'{fake_bin}{os.pathsep}{os.environ["PATH"]}',
            'FAKE_PULLS': str(self.pulls),
            'FAKE_GH_CALLS': str(self.directory / 'gh-calls'),
            'PULLS_RETRY_SECONDS': '0',
            'GIT_CONFIG_GLOBAL': str(self.directory / 'gitconfig'),
            'GIT_CONFIG_NOSYSTEM': '1',
            'RUNNER_TEMP': str(self.directory),
            'GITHUB_STEP_SUMMARY': str(self.summary),
        }
        (self.directory / 'gitconfig').write_text(
            '[user]\n\tname = Test\n\temail = test@example.com\n[init]\n\tdefaultBranch = main\n')
        self.git(self.directory, 'init', '--bare', str(self.remote))
        self.git(self.directory, 'init', str(self.work))
        shutil.copytree(GITHUB, self.work / '.github', ignore=shutil.ignore_patterns('__pycache__', 'tests'))
        (self.work / VERSION_FILE).write_text('5.2.0\n')
        self.git(self.work, 'add', '.')
        self.git(self.work, 'commit', '-m', 'version up to v5.2.0')
        self.git(self.work, 'tag', '-a', 'v5.2.0', '-m', 'v5.2.0')
        (self.work / VERSION_FILE).write_text('5.2.1-SNAPSHOT\n')
        self.git(self.work, 'commit', '-am', 'version up to 5.2.1-SNAPSHOT')
        (self.work / 'feature.txt').write_text('x\n')
        self.git(self.work, 'add', 'feature.txt')
        self.git(self.work, 'commit', '-m', 'feat: add a feature')
        self.git(self.work, 'remote', 'add', 'origin', str(self.remote))
        self.git(self.work, 'push', 'origin', 'main', '--tags')

    def git(self, cwd, *args):
        return subprocess.run(['git', *args], cwd=cwd, env=self.env, check=True, text=True,
                              capture_output=True).stdout.strip()

    def step(self, step_id, cwd, **env):
        output = self.directory / ('output-' + step_id)
        output.write_text('')
        result = subprocess.run(
            ['bash', '-e', '-o', 'pipefail', '-c', run_body(BUMP, step_id)], cwd=cwd,
            env={**self.env, 'GITHUB_WORKSPACE': str(cwd), 'GITHUB_OUTPUT': str(output),
                 'GITHUB_REPOSITORY': REPOSITORY, 'SERVER_URL': 'https://github.com',
                 'PUSH_TOKEN': 'dummy', **env},
            capture_output=True, text=True,
        )
        return result, outputs(output)

    def remote_ref(self, ref):
        return self.git(self.remote, 'rev-parse', ref)

    def remote_tags(self):
        return self.git(self.remote, 'tag', '-l')

    def clone(self, ref='main'):
        target = Path(tempfile.mkdtemp(dir=self.directory)) / 'clone'
        self.git(self.directory, 'clone', str(self.remote), str(target))
        self.git(target, 'checkout', ref)
        return target

    def open_version_branch(self):
        result, out = self.step('version_branch', self.work, NEXT_TAG='v5.3.0')
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(out, {'branch': 'release/v5.3.0'})
        result, _ = self.step('version_commit', self.work, NEXT_VERSION='5.3.0', NEXT_TAG='v5.3.0',
                              BRANCH=out['branch'])
        self.assertEqual(result.returncode, 0, result.stderr)

    def squash_merge(self, ref='release/v5.3.0'):
        merged = self.clone()
        self.git(merged, 'merge', '--squash', 'origin/' + ref)
        self.git(merged, 'commit', '-m', 'version up to v5.3.0 (#1)')
        self.git(merged, 'push', 'origin', 'main')
        sha = self.git(merged, 'rev-parse', 'HEAD')
        self.pulls.write_text(json.dumps([pull(sha, ref=ref)]))
        return sha

    def trigger(self, sha, **env):
        (self.directory / 'gh-calls').unlink(missing_ok=True)
        return self.step('trigger', self.clone(sha), RELEASE_SHA=sha, **env)

    def merged_release(self):
        self.open_version_branch()
        return self.squash_merge()

    def test_version_branch_changes_only_the_version_and_leaves_main(self):
        main_before = self.remote_ref('main')
        self.open_version_branch()
        self.assertEqual(self.remote_ref('main'), main_before)
        branch = self.remote_ref('release/v5.3.0')
        self.assertEqual(self.git(self.remote, 'rev-parse', branch + '^'), main_before)
        self.assertEqual(self.git(self.remote, 'diff', '--name-only', main_before, branch), VERSION_FILE)
        self.assertEqual(self.git(self.remote, 'cat-file', 'blob', branch + ':' + VERSION_FILE), '5.3.0')
        blob = subprocess.run(['git', 'cat-file', 'blob', branch + ':' + VERSION_FILE], cwd=self.remote,
                              env=self.env, check=True, capture_output=True).stdout
        self.assertEqual(blob, b'5.3.0\n')
        self.assertEqual(self.git(self.remote, 'log', '-1', '--format=%s', branch), 'version up to v5.3.0')
        self.assertEqual(self.remote_tags(), 'v5.2.0')

    def test_existing_version_branch_stops_a_second_run(self):
        self.open_version_branch()
        result, out = self.step('version_branch', self.work, NEXT_TAG='v5.3.0')
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('already exists', result.stdout)
        self.assertEqual(out, {})

    def test_pull_request_body_previews_the_release_notes(self):
        for rehearsal in ('true', 'false'):
            with self.subTest(rehearsal=rehearsal):
                result, out = self.step('pr_body', self.work, PREV_TAG='v5.2.0', NEXT_VERSION='5.3.0',
                                        NEXT_TAG='v5.3.0', REHEARSAL=rehearsal)
                self.assertEqual(result.returncode, 0, result.stderr)
                body = Path(out['path']).read_text()
                self.assertIn('Bump `version` to `5.3.0`', body)
                self.assertIn('(https://github.com/o/r/compare/v5.2.0...release/v5.3.0)', body)
                self.assertIn('add a feature', body)
                self.assertEqual('**Rehearsal. Do not merge.**' in body, rehearsal == 'true')

    def test_previous_tag_is_the_latest_stable_tag(self):
        self.git(self.work, 'tag', 'v9.0.0-rc.1')
        self.git(self.work, 'tag', 'not-a-version')
        result, out = self.step('defines', self.work)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(out, {'prev_tag': 'v5.2.0'})

    def test_merged_version_pull_request_passes_the_gate_and_the_tag_is_only_reported(self):
        self.open_version_branch()
        branch_commit = self.remote_ref('release/v5.3.0')
        after = self.squash_merge()
        self.assertNotEqual(after, branch_commit)

        result, out = self.trigger(after)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(out['tag'], 'v5.3.0')
        result, _ = self.step('tag_summary', self.clone(after), RELEASE_TAG='v5.3.0', RELEASE_SHA=after)
        self.assertEqual(result.returncode, 0, result.stderr)
        summary = self.summary.read_text()
        self.assertIn(f'would push `v5.3.0` on `{after}`', summary)
        self.assertIn("`PUSH_RELEASE_TAG` is `'false'`", summary)
        self.assertEqual(self.remote_tags(), 'v5.2.0')

    def test_tag_push_step_tags_the_merged_commit_once_it_is_switched_on(self):
        after = self.merged_release()
        result, _ = self.step('release_tag', self.clone(after), RELEASE_TAG='v5.3.0', RELEASE_SHA=after)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(self.remote_ref('v5.3.0^{commit}'), after)
        result, out = self.trigger(after)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(out['tag'], '')

    def test_tag_on_another_commit_fails(self):
        self.open_version_branch()
        before = self.remote_ref('main')
        after = self.squash_merge()
        other = self.clone(before)
        self.git(other, 'tag', '-a', 'v5.3.0', '-m', 'v5.3.0')
        self.git(other, 'push', 'origin', 'v5.3.0')
        result, _ = self.trigger(after)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('already exists on another commit', result.stderr)

    def test_version_changed_by_another_pull_request_is_not_tagged(self):
        work = self.clone()
        self.git(work, 'switch', '-c', 'feature/x')
        (work / VERSION_FILE).write_text('5.3.0\n')
        self.git(work, 'commit', '-am', 'feat: bump by hand')
        self.git(work, 'push', 'origin', 'feature/x')
        after = self.squash_merge('feature/x')
        result, out = self.trigger(after)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('is not the squash merge', result.stderr)
        self.assertEqual(out, {})

    def test_version_changed_with_code_is_not_tagged(self):
        work = self.clone()
        self.git(work, 'switch', '-c', 'release/v5.3.0')
        (work / VERSION_FILE).write_text('5.3.0\n')
        (work / 'feature.txt').write_text('y\n')
        self.git(work, 'commit', '-am', 'version up to v5.3.0')
        self.git(work, 'push', 'origin', 'release/v5.3.0')
        after = self.squash_merge()
        result, _ = self.trigger(after)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('must change only version', result.stderr)

    def test_pull_request_lookup_is_retried_while_github_links_it(self):
        after = self.merged_release()
        result, out = self.trigger(after, FAKE_GH_FAIL='1', FAKE_GH_EMPTY='2')
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(out['tag'], 'v5.3.0')
        self.assertEqual((self.directory / 'gh-calls').read_text().strip(), '4')

    def test_pull_request_that_never_appears_stops_without_a_tag(self):
        after = self.merged_release()
        result, out = self.trigger(after, FAKE_GH_EMPTY='9')
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('no pull request is associated', result.stderr)
        self.assertEqual(out, {})
        self.assertEqual((self.directory / 'gh-calls').read_text().strip(), '5')

    def test_pull_request_lookup_that_keeps_failing_stops_without_a_tag(self):
        after = self.merged_release()
        result, out = self.trigger(after, FAKE_GH_FAIL='9')
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('could not read the pull requests', result.stdout)
        self.assertEqual(out, {})

    def test_push_without_a_version_change_is_not_tagged(self):
        (self.work / 'feature.txt').write_text('z\n')
        self.git(self.work, 'commit', '-am', 'fix: change the feature')
        self.git(self.work, 'push', 'origin', 'main')
        result, out = self.trigger(self.remote_ref('main'))
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(out['tag'], '')

    def test_parent_without_a_version_file_is_not_released(self):
        work = self.clone()
        self.git(work, 'rm', '-q', VERSION_FILE)
        self.git(work, 'commit', '-m', 'chore: drop the version file')
        self.git(work, 'push', 'origin', 'main')
        (work / VERSION_FILE).write_text('5.3.0\n')
        self.git(work, 'add', VERSION_FILE)
        self.git(work, 'commit', '-m', 'version up to v5.3.0')
        self.git(work, 'push', 'origin', 'main')
        sha = self.git(work, 'rev-parse', 'HEAD')
        self.pulls.write_text(json.dumps([pull(sha)]))
        result, out = self.trigger(sha)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(out['tag'], '')
        self.assertIn('has no version', result.stderr)


if __name__ == '__main__':
    unittest.main()
