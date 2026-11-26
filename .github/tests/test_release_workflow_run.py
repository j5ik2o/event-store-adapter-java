"""関門を失敗させて公開が止まることを、release.yml の release ジョブを手元で流して確かめる（設計の追記 R2b-2 §5、I13）。

bare のリモートに版上げの squash 相当のコミットとタグを作り、そのタグの clone で手順を流す。gradlew・gh は偽物、
GitHub の API・Portal・repo1 は手元の HTTP のスタブ。秘密の値はダミー。
"""
import json
import os
import shutil
import subprocess
import tempfile
import unittest
from datetime import datetime, timezone
from pathlib import Path

from central_portal_support import Stub, portal
from release_test_support import GITHUB, RELEASE, REPOSITORY, VERSION_FILE, pull
from release_workflow_canonical import release_steps
from test_release_rehearsal import FAKE_GH
from workflow_runner import Runner, UnknownExpression, default_environment

RUN_ID = portal.FIRST_COUNTED_RUN_ID + 500
DEPLOYMENT = '44444444-4444-4444-8444-444444444444'
SECRETS = {name: f'dummy-{name.lower()}' for name in
           ('SONATYPE_USERNAME', 'SONATYPE_PASSWORD', 'SIGNING_KEY', 'SIGNING_PASSWORD')}
FAKE_GRADLEW = '''#!/usr/bin/env python3
# 偽の gradlew。呼ばれた引数を残し、properties には版を、publishToMavenLocal には 6 つの成果物の 10 ファイルを書く。
import os, sys
from pathlib import Path
with open(os.environ['FAKE_GRADLE_CALLS'], 'a') as calls:
    calls.write(' '.join(sys.argv[1:]) + '\\n')
if 'properties' in sys.argv:
    print('version: ' + os.environ['FAKE_GRADLE_VERSION'])
elif 'publishToMavenLocal' in sys.argv:
    repository = next(arg.split('=', 1)[1] for arg in sys.argv if arg.startswith('-Dmaven.repo.local='))
    version = os.environ['FAKE_GRADLE_VERSION']
    for artifact in os.environ['FAKE_ARTIFACTS'].split():
        directory = Path(repository, 'io/github/j5ik2o', artifact, version)
        directory.mkdir(parents=True)
        for suffix in ('.jar', '-sources.jar', '-javadoc.jar', '.pom', '.module'):
            for extra in ('', '.asc'):
                (directory / f'{artifact}-{version}{suffix}{extra}').write_bytes(b'x')
else:
    sys.exit('unexpected gradlew call')
'''
GATE_FAILED = 'Check the tagged commit against the release gate'


class ReleaseWorkflowRunTest(unittest.TestCase):
    def setUp(self):
        self.directory = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, self.directory)
        self.stub = Stub()
        self.addCleanup(self.stub.close)
        self.bin = self.directory / 'bin'
        self.bin.mkdir()
        (self.bin / 'gh').write_text(FAKE_GH)
        (self.bin / 'gh').chmod(0o755)
        self.pulls = self.directory / 'pulls.json'
        self.gradle_calls = self.directory / 'gradle-calls'
        self.gradle_calls.write_text('')
        (self.directory / 'gitconfig').write_text(
            '[user]\n\tname = Test\n\temail = test@example.com\n[init]\n\tdefaultBranch = main\n')
        self.git_env = {**os.environ, 'GIT_CONFIG_GLOBAL': str(self.directory / 'gitconfig'), 'GIT_CONFIG_NOSYSTEM': '1'}
        self.remote = self.directory / 'remote.git'
        self.work = self.directory / 'work'
        self.git(self.directory, 'init', '--bare', str(self.remote))
        self.git(self.directory, 'init', str(self.work))
        shutil.copytree(GITHUB, self.work / '.github', ignore=shutil.ignore_patterns('__pycache__', 'tests'))
        (self.work / 'gradlew').write_text(FAKE_GRADLEW)
        (self.work / 'gradlew').chmod(0o755)
        self.uploads = []
        self.github_runs = [self.run_item(RUN_ID, 'v5.3.0')]
        self.jobs = {RUN_ID: []}
        self.statuses = {}
        self.repo1 = 404
        self.stub.routes[('GET', f'/repos/{REPOSITORY}/actions/workflows/release.yml/runs')] = \
            lambda request: (200, {'total_count': len(self.github_runs), 'workflow_runs': self.github_runs})

    # ------------------------------------------------------------ 道具
    def git(self, cwd, *args):
        return subprocess.run(['git', *args], cwd=cwd, env=self.git_env, check=True, text=True,
                              capture_output=True).stdout.strip()

    def run_item(self, run_id, tag, attempt=1):
        now = datetime.now(timezone.utc).strftime('%Y-%m-%dT%H:%M:%SZ')
        return {'id': run_id, 'created_at': now, 'head_branch': tag, 'event': 'push', 'run_attempt': attempt}

    def history(self, base='5.2.1-SNAPSHOT', release='5.3.0', tag=None, extra_file=False, head='release/v5.3.0'):
        """v5.2.0 → base（SNAPSHOT）→ release の版だけを変えた squash 相当のコミット。タグ（既定 v<release>）を付けて push する。"""
        (self.work / VERSION_FILE).write_text('5.2.0\n')
        self.git(self.work, 'add', '.')
        self.git(self.work, 'commit', '-m', 'version up to v5.2.0')
        self.git(self.work, 'tag', '-a', 'v5.2.0', '-m', 'v5.2.0')
        (self.work / VERSION_FILE).write_text(base + '\n')
        self.git(self.work, 'commit', '-am', f'version up to {base}')
        (self.work / VERSION_FILE).write_text(release + '\n')
        if extra_file:
            (self.work / 'feature.txt').write_text('x\n')
            self.git(self.work, 'add', 'feature.txt')
        self.git(self.work, 'commit', '-am', f'version up to v{release} (#1)')
        sha = self.git(self.work, 'rev-parse', 'HEAD')
        tag = tag or f'v{release}'
        self.git(self.work, 'tag', '-a', tag, '-m', tag)
        self.git(self.work, 'remote', 'add', 'origin', str(self.remote))
        self.git(self.work, 'push', 'origin', 'main', '--tags')
        self.pulls.write_text(json.dumps([pull(sha, ref=head)]))
        workspace = self.directory / 'workspace'
        self.git(self.directory, 'clone', str(self.remote), str(workspace))
        self.git(workspace, 'checkout', '-q', tag)
        return workspace, tag

    def portal_routes(self, publish_state='PUBLISHED'):
        def upload(request):
            self.uploads.append(request)
            return 201, DEPLOYMENT

        name = portal.deployment_name('5.3.0', RUN_ID, self.attempt)
        self.stub.routes[('POST', '/api/v1/publisher/upload')] = upload
        self.stub.routes[('POST', '/api/v1/publisher/status')] = \
            lambda request: (200, {'deploymentId': DEPLOYMENT, 'deploymentState': publish_state, 'deploymentName': name})
        for artifact in portal.bundle.ARTIFACTS:
            path = f'/{portal.bundle.GROUP_PATH}/{artifact}/5.3.0/{artifact}-5.3.0.pom'
            self.stub.routes[('HEAD', '/repo1' + path)] = lambda request: (self.repo1, b'')
        for run_id, jobs in self.jobs.items():
            self.stub.routes[('GET', f'/repos/{REPOSITORY}/actions/runs/{run_id}/jobs')] = \
                lambda request, jobs=jobs: (200, {'total_count': len(jobs), 'jobs': jobs})
            self.stub.routes[('GET', f'/repos/{REPOSITORY}/actions/runs/{run_id}/artifacts')] = \
                lambda request: (200, {'total_count': 0, 'artifacts': []})

    def run_release(self, workspace, tag, attempt=1, gradle_version='5.3.0', step_env=None, workflow=RELEASE):
        self.attempt = attempt
        self.portal_routes()
        temp = self.directory / 'runner-temp'
        temp.mkdir(exist_ok=True)
        environment = default_environment(workspace, temp, REPOSITORY, RUN_ID, attempt, self.stub.url,
                                          f'{self.bin}{os.pathsep}{os.environ["PATH"]}', self.directory)
        environment.update({
            'GIT_CONFIG_GLOBAL': str(self.directory / 'gitconfig'), 'GIT_CONFIG_NOSYSTEM': '1',
            'FAKE_PULLS': str(self.pulls), 'FAKE_GH_CALLS': str(self.directory / 'gh-calls'), 'PULLS_RETRY_SECONDS': '0',
            'FAKE_GRADLE_CALLS': str(self.gradle_calls), 'FAKE_GRADLE_VERSION': gradle_version,
            'FAKE_ARTIFACTS': ' '.join(portal.bundle.ARTIFACTS),
            'CENTRAL_API_URL': self.stub.url, 'REPO1_URL': self.stub.url + '/repo1',
        })
        runner = Runner(workflow, workspace, temp, {'token': 'ghs_dummy', 'ref_name': tag, 'run_attempt': str(attempt)},
                        environment, SECRETS, {'CENTRAL_DEPLOYMENTS': ''}, step_env)
        return runner.run()

    def portal_calls(self):
        return [call['path'] for call in self.stub.calls if call['path'].startswith('/api/')]

    def assert_stopped_by_the_gate(self, result, message):
        self.assertEqual(result.outcome('release_gate'), 'failure')
        gate = next(step for step in result.steps if step.id == 'release_gate')
        self.assertTrue(any(line.startswith('::error::') and message in line for line in gate.stderr.splitlines()),
                        gate.stderr)
        self.assertEqual(result.ran(), ['Check out the tagged commit', 'Set up Python', 'release_gate'])
        self.assertEqual(self.gradle_calls.read_text(), '')
        self.assertEqual(self.portal_calls(), [])
        self.assertEqual([call for call in self.stub.calls if '/repo1/' in call['path']], [])
        self.assertEqual(result.artifacts, [])
        self.assertTrue(all(not step.secrets for step in result.steps if step.outcome != 'skipped'))
        self.assertFalse(result.succeeded)

    # ------------------------------------------------------------ 関門で止まる
    def test_the_gate_stops_a_commit_that_is_not_the_merge_of_the_version_pull_request(self):
        workspace, tag = self.history(head='feature/x')
        self.assert_stopped_by_the_gate(self.run_release(workspace, tag), 'is not the squash merge of a pull request')

    def test_the_gate_stops_a_commit_that_changes_code_with_the_version(self):
        workspace, tag = self.history(extra_file=True)
        self.assert_stopped_by_the_gate(self.run_release(workspace, tag), 'the commit must change only version')

    def test_the_gate_stops_a_lower_version(self):
        workspace, tag = self.history(base='5.3.1-SNAPSHOT')
        self.assert_stopped_by_the_gate(self.run_release(workspace, tag), 'must be greater than the previous version')

    def test_the_gate_stops_a_tag_that_does_not_match_the_version(self):
        workspace, tag = self.history(tag='v5.3.1')
        self.github_runs = [self.run_item(RUN_ID, 'v5.3.1')]
        self.assert_stopped_by_the_gate(self.run_release(workspace, tag), '5.3.0 must be greater than the latest tag v5.3.1')

    # ------------------------------------------------------------ 関門を通る
    def test_a_release_tag_publishes_once_and_records_the_id(self):
        workspace, tag = self.history()
        result = self.run_release(workspace, tag)
        self.assertTrue(result.succeeded, [(step.id, step.stderr[-500:]) for step in result.steps])
        expected = [item['id'] or item['name'] for item in release_steps(RELEASE)]
        self.assertEqual(result.ran(), expected)
        self.assertEqual(len(result.steps), len(release_steps(RELEASE)))
        self.assertEqual(self.gradle_calls.read_text().splitlines()[0], '--no-daemon -q properties --property version')
        self.assertEqual(sum('publishToMavenLocal' in line for line in self.gradle_calls.read_text().splitlines()), 1)
        self.assertEqual(len(self.uploads), 1)
        name = portal.deployment_name('5.3.0', RUN_ID, 1)
        self.assertEqual(self.uploads[0]['query']['name'], [name])
        self.assertIn(f'filename="{name}.zip"'.encode(), self.uploads[0]['body'])
        self.assertEqual(result.artifacts, [(f'central-deployment-1-{DEPLOYMENT}',
                                             str(self.directory / 'runner-temp' / 'central-deployment.txt'))])
        self.assertEqual(result.outputs, {'action': 'upload', 'state': 'PUBLISHED'})
        secrets = {step.id: step.secrets for step in result.steps if step.secrets}
        self.assertEqual(secrets, {'precheck': {'SONATYPE_USERNAME', 'SONATYPE_PASSWORD'},
                                   'bundle': {'SIGNING_KEY', 'SIGNING_PASSWORD'},
                                   'upload': {'SONATYPE_USERNAME', 'SONATYPE_PASSWORD'},
                                   'deployment': {'SONATYPE_USERNAME', 'SONATYPE_PASSWORD'}})

    def test_a_published_version_is_not_uploaded_again(self):
        workspace, tag = self.history()
        self.repo1 = 200
        result = self.run_release(workspace, tag)
        self.assertTrue(result.succeeded)
        for step_id in ('bundle', 'upload', 'record', 'deployment'):
            self.assertEqual(result.outcome(step_id), 'skipped', step_id)
        self.assertEqual(result.outputs, {'action': 'published', 'state': ''})
        self.assertEqual(self.uploads, [])
        self.assertNotIn('publishToMavenLocal', self.gradle_calls.read_text())

    def test_a_different_build_version_stops_before_the_precheck(self):
        workspace, tag = self.history()
        result = self.run_release(workspace, tag, gradle_version='5.3.1')
        self.assertEqual(result.outcome('build_version'), 'failure')
        self.assertEqual(result.outcome('precheck'), 'skipped')
        self.assertEqual([call for call in self.stub.calls if not call['path'].startswith('/repos/o/r/commits')], [])
        self.assertEqual(result.artifacts, [])

    def test_an_earlier_attempt_without_an_id_stops_the_precheck(self):
        workspace, tag = self.history()
        upload_step = [{'name': name, 'status': 'completed', 'conclusion': 'success'}
                       for name in sorted(portal.PRE_UPLOAD_STEPS) + [portal.UPLOAD_STEP]]
        self.jobs[RUN_ID] = [{'name': portal.RELEASE_JOB, 'run_attempt': 1, 'started_at': '2026-11-26T00:00:00Z',
                              'completed_at': '2026-11-26T00:10:00Z', 'runner_id': 1, 'runner_name': 'GitHub Actions 1',
                              'conclusion': 'failure', 'steps': upload_step}]
        self.github_runs = [self.run_item(RUN_ID, 'v5.3.0', attempt=2)]
        result = self.run_release(workspace, tag, attempt=2)
        self.assertEqual(result.outcome('precheck'), 'failure')
        self.assertIn(f'{RUN_ID}/1=<deployment ID|none>', next(s for s in result.steps if s.id == 'precheck').stderr)
        for step_id in ('bundle', 'upload', 'record', 'deployment'):
            self.assertEqual(result.outcome(step_id), 'skipped', step_id)
        self.assertEqual(self.uploads, [])
        self.assertNotIn('publishToMavenLocal', self.gradle_calls.read_text())

    def test_the_id_is_recorded_when_the_upload_step_fails_after_writing_it(self):
        workspace, tag = self.history()
        broken = self.directory / 'summary-is-a-directory'
        broken.mkdir()
        result = self.run_release(workspace, tag, step_env={'upload': {'GITHUB_STEP_SUMMARY': str(broken)}})
        self.assertEqual(result.outcome('upload'), 'failure')
        self.assertEqual(result.outcome('record'), 'success')
        self.assertEqual(result.outcome('deployment'), 'skipped')
        self.assertEqual(result.artifacts[0][0], f'central-deployment-1-{DEPLOYMENT}')
        self.assertEqual(len(self.uploads), 1)

    # ------------------------------------------------------------ 実行器そのもの
    def test_the_runner_refuses_what_it_does_not_model(self):
        workspace, tag = self.history()
        for old, new in (("if: steps.precheck.outputs.action == 'upload'\n        env:\n          RELEASE_TAG",
                          "if: success()\n        env:\n          RELEASE_TAG"),
                         ('${{ github.ref_name }}', '${{ github.event.ref }}'),
                         ('uses: actions/setup-python@v7', 'uses: some/action@v1'),
                         ('SIGNING_KEY: ${{ secrets.SIGNING_KEY }}', "SIGNING_KEY: ${{ format('{0}', secrets.SIGNING_KEY) }}")):
            with self.subTest(new):
                with self.assertRaises(UnknownExpression):
                    self.run_release(workspace, tag, workflow=RELEASE.replace(old, new, 1))


if __name__ == '__main__':
    unittest.main()
