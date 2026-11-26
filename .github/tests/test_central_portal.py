"""central-portal.py の例のテスト。判定（decide）は事実を組み立てて、照会はスタブの HTTP サーバーで確かめる。"""
import base64
import os
import subprocess
import sys
import tempfile
import unittest
from datetime import timedelta
from pathlib import Path

from central_portal_support import (
    ID_A, ID_B, NOW, RUN, Stub, facts, fixture_jobs, named, passed_pre_steps, portal, record, release_job, run,
)
from release_test_support import GITHUB, outputs

Stop = portal.Stop


class DecideTest(unittest.TestCase):
    def decide(self, value):
        return portal.decide(value)

    def assert_stops(self, value, *fragments):
        with self.assertRaises(Stop) as raised:
            portal.decide(value)
        for fragment in fragments:
            self.assertIn(fragment, str(raised.exception))
        return str(raised.exception)

    def test_nothing_recorded_uploads(self):
        self.assertEqual(self.decide(facts()), portal.Decision('upload'))

    def test_a_version_on_repo1_is_not_uploaded_again(self):
        repo1 = {artifact: 404 for artifact in portal.bundle.ARTIFACTS}
        repo1['event-store-adapter-java-core'] = 200
        self.assertEqual(self.decide(facts(repo1=repo1)).action, 'published')

    def test_a_recorded_deployment_of_an_earlier_attempt_decides(self):
        earlier = run(jobs=[release_job(1), release_job(2, upload='absent', step_pairs=())],
                      artifacts=[record(1, ID_A)])
        cases = {
            'PENDING': portal.Decision('wait', waiting=(ID_A,)),
            'PUBLISHING': portal.Decision('wait', waiting=(ID_A,)),
            'PUBLISHED': portal.Decision('published', deployment=ID_A),
            'FAILED': portal.Decision('upload'),
        }
        for state, decision in cases.items():
            with self.subTest(state):
                value = facts([earlier], statuses={ID_A: named(state)}, current_run=RUN, current_attempt=2)
                self.assertEqual(self.decide(value), decision)

    def test_another_versions_deployment_in_progress_makes_this_one_wait(self):
        older = run(tag='v5.3.0', jobs=[release_job(1)], artifacts=[record(1, ID_A)])
        value = facts([older], version='5.4.0', tags=('v5.2.0', 'v5.3.0', 'v5.4.0'),
                      statuses={ID_A: named('VALIDATING')})
        self.assertEqual(self.decide(value), portal.Decision('wait', waiting=(ID_A,)))

    def test_an_upload_without_an_id_stops_every_later_release(self):
        older = run(tag='v5.3.0', jobs=[release_job(1)])
        message = self.assert_stops(facts([older], version='5.4.0', tags=('v5.2.0', 'v5.3.0', 'v5.4.0')),
                                    f'{RUN}/1=<deployment ID|none>',
                                    portal.deployment_name('5.3.0', RUN, 1))
        self.assertIn('CENTRAL_DEPLOYMENTS', message)

    def test_a_job_whose_step_list_is_cut_counts_as_uploaded(self):
        cut = run(jobs=[release_job(1, upload='absent')])
        self.assert_stops(facts([cut]), f'{RUN}/1=')

    def test_jobs_that_did_not_reach_the_upload_are_not_counted(self):
        cases = {
            'upload skipped': release_job(1, upload='skipped'),
            'build failed': release_job(1, step_pairs=(('Set up job', 'success'), ('Build the bundle', 'failure'))),
            'cancelled at setup': release_job(1, step_pairs=(('Set up job', 'cancelled'),)),
            'never reached a runner': release_job(1, step_pairs=(), runner_name=''),
        }
        for name, job in cases.items():
            with self.subTest(name):
                self.assertEqual(self.decide(facts([run(jobs=[job])])).action, 'upload')

    def test_jobs_that_reached_the_upload_count(self):
        for conclusion in ('success', 'failure', 'cancelled', None):
            with self.subTest(conclusion):
                self.assert_stops(facts([run(jobs=[release_job(1, upload=conclusion)])]), f'{RUN}/1=')
        self.assert_stops(facts([run(jobs=[release_job(1, step_pairs=(), runner_name='GitHub Actions 7')])]))

    def test_real_jobs_cancelled_before_the_upload(self):
        # run 45745111667（Set up job だけが cancelled）と 45748080842（steps が空、runner_name が空）
        for run_id in (45745111667, 45748080842):
            with self.subTest(run_id):
                jobs = fixture_jobs(run_id)
                self.assertFalse(portal.may_have_uploaded(jobs[0]))
                self.assertEqual(self.decide(facts([run(jobs=jobs)])).action, 'upload')

    def test_a_job_copied_into_a_rerun_is_not_counted_again(self):
        # run 43174724734：失敗した job の再実行で、Check release tooling が試み 2 に写された
        copied = fixture_jobs(43174724734, rename='Check release tooling')
        self.assertEqual([job.run_attempt for job in portal.executions(copied)], [1])
        rerun = fixture_jobs(43174724734, rename='Run tests with JDK 11')
        self.assertEqual([job.run_attempt for job in portal.executions(rerun)], [1, 2])
        value = facts([run(jobs=copied, artifacts=[record(1, ID_A)])], statuses={ID_A: named('FAILED')})
        self.assertEqual(self.decide(value).action, 'upload')
        uncopied = facts([run(jobs=rerun, artifacts=[record(1, ID_A)])], statuses={ID_A: named('FAILED')})
        self.assert_stops(uncopied, f'{RUN}/2=')

    def test_a_confirm_only_rerun_does_not_stop(self):
        attempt_one = release_job(1)
        copy = release_job(2, started=attempt_one.started_at, runner=attempt_one.runner_id)
        copy = portal.Job(copy.name, 2, attempt_one.started_at, attempt_one.completed_at, attempt_one.runner_id,
                          attempt_one.runner_name, attempt_one.steps)
        value = facts([run(jobs=[attempt_one, copy], artifacts=[record(1, ID_A)])],
                      version='5.4.0', tags=('v5.2.0', 'v5.3.0', 'v5.4.0'), statuses={ID_A: named('PUBLISHED')})
        self.assertEqual(self.decide(value).action, 'upload')

    def test_none_lines(self):
        unknown = run(jobs=[release_job(1)])
        self.assertEqual(self.decide(facts([unknown], overrides=f'{RUN}/1=none')).action, 'upload')
        recorded = run(jobs=[release_job(1)], artifacts=[record(1, ID_A)])
        dropped = facts([recorded], overrides=f'{RUN}/1=none', statuses={ID_A: portal.Status(portal.ABSENT)})
        self.assertEqual(self.decide(dropped).action, 'upload')
        for state in ('PENDING', 'PUBLISHED', 'FAILED'):
            with self.subTest(state):
                self.assert_stops(facts([recorded], overrides=f'{RUN}/1=none', statuses={ID_A: named(state)}),
                                  'remove or correct the line')
        self.assert_stops(facts([recorded], overrides=f'{RUN}/1=none',
                                statuses={ID_A: portal.Status(portal.QUERY_FAILED)}), 're-run')

    def test_a_dropped_deployment_stops_until_a_none_line(self):
        recorded = run(jobs=[release_job(1)], artifacts=[record(1, ID_A)])
        self.assert_stops(facts([recorded], statuses={ID_A: portal.Status(portal.ABSENT)}), f'{RUN}/1=none')

    def test_id_lines(self):
        unknown = run(jobs=[release_job(1)])
        given = facts([unknown], overrides=f'{RUN}/1={ID_B}', statuses={ID_B: named('PUBLISHED', zip_suffix=True)})
        self.assertEqual(self.decide(given), portal.Decision('published', deployment=ID_B))
        self.assert_stops(facts([unknown], overrides=f'{RUN}/1={ID_B}',
                                statuses={ID_B: named('PUBLISHED', run_id=RUN + 1)}), 'is named')
        recorded = run(jobs=[release_job(1)], artifacts=[record(1, ID_A)])
        self.assert_stops(facts([recorded], overrides=f'{RUN}/1={ID_B}'), 'but its record is')
        same = facts([recorded], overrides=f'{RUN}/1={ID_A}', statuses={ID_A: named('FAILED')})
        self.assertEqual(self.decide(same).action, 'upload')

    def test_malformed_lines_and_names_stop(self):
        for text in ('1/1=maybe', f'{RUN}/0=none', 'tag/5.3.0=checked', 'tag/v5.3.0=yes', f'{RUN}/1=none\n{RUN}/1=none',
                     'garbage'):
            with self.subTest(text):
                self.assert_stops(facts(overrides=text), 'CENTRAL_DEPLOYMENTS')
        broken = run(jobs=[release_job(1)], artifacts=[portal.Artifact('central-deployment-1-xyz', False,
                                                                       NOW + timedelta(days=89))])
        self.assert_stops(facts([broken]), 'central-deployment-<attempt>-<ID>')
        other = run(jobs=[release_job(1, upload='skipped')],
                    artifacts=[portal.Artifact('conformance-report', False, None)])
        self.assertEqual(self.decide(facts([other])).action, 'upload')

    def test_tags_without_a_release_run(self):
        self.assert_stops(facts(tags=('v5.2.0', 'v5.2.1', 'v5.3.0')), 'tag/v5.2.1=checked')
        value = facts(tags=('v5.2.0', 'v5.2.1', 'v5.3.0'), overrides='tag/v5.2.1=checked')
        self.assertEqual(self.decide(value).action, 'upload')
        own = facts([run(tag='v5.3.0', jobs=[release_job(1, upload='skipped')])])
        self.assertEqual(self.decide(own).action, 'upload')

    def test_record_retention(self):
        expired = run(jobs=[release_job(1)], artifacts=[portal.Artifact(portal.record_name(1, ID_A), True, None)])
        self.assert_stops(facts([expired]), 'expired')
        short = run(jobs=[release_job(1)], artifacts=[portal.Artifact(
            portal.record_name(1, ID_A), False, NOW + timedelta(days=10))])
        self.assert_stops(facts([short]), '90 days')

    def test_the_window_and_the_boundary(self):
        old = run(age=81, jobs=[release_job(1)])
        self.assertEqual(self.decide(facts([old])).action, 'upload')
        before = run(run_id=portal.FIRST_COUNTED_RUN_ID - 1, tag='v5.2.0', jobs=[release_job(1)])
        self.assertEqual(self.decide(facts([before])).action, 'upload')
        recent = run(age=79, jobs=[release_job(1)])
        self.assert_stops(facts([recent]))

    def test_a_run_without_a_release_job_stops(self):
        self.assert_stops(facts([run(jobs=[])]), f'{RUN}/1=<deployment ID|none>')
        self.assertEqual(self.decide(facts([run(run_id=RUN, jobs=[])], current_run=RUN)).action, 'upload')

    def test_queries_that_fail_stop(self):
        recorded = run(jobs=[release_job(1)], artifacts=[record(1, ID_A)])
        self.assert_stops(facts([recorded]), 're-run')
        repo1 = {artifact: 404 for artifact in portal.bundle.ARTIFACTS}
        repo1['event-store-adapter-java-sqlite'] = 503
        self.assert_stops(facts(repo1=repo1), 'repo1')
        repo1['event-store-adapter-java-sqlite'] = None
        self.assert_stops(facts(repo1=repo1), 'repo1')
        self.assert_stops(facts(total=1001), '1000')

    def test_an_overtaken_version_is_not_uploaded(self):
        self.assert_stops(facts(tags=('v5.2.0', 'v5.3.0', 'v5.4.0'), overrides='tag/v5.4.0=checked'),
                          'not the latest release tag')

    def test_a_prerelease_version_stops(self):
        self.assert_stops(facts(version='5.3.0-rc.1'), 'X.Y.Z')


class SmallFunctionsTest(unittest.TestCase):
    def test_check_build_version(self):
        output = "\n> Task :properties\n\n------------\nRoot project 'x'\n------------\n\nversion: 5.3.0\n"
        self.assertEqual(portal.check_build_version(output, 'v5.3.0'), '5.3.0')
        self.assertEqual(portal.check_build_version(output.replace('\n', '\r\n'), 'v5.3.0'), '5.3.0')
        for text, tag in ((output, 'v5.3.1'), ('nothing', 'v5.3.0'), (output + 'version: 5.3.0\n', 'v5.3.0')):
            with self.subTest(text=text, tag=tag), self.assertRaises(Stop):
                portal.check_build_version(text, tag)

    def test_parse_status(self):
        body = b'{"deploymentId": "x", "deploymentName": "n", "deploymentState": "FAILED", "errors": {"a": ["b"]}}'
        self.assertEqual(portal.parse_status(200, body), portal.Status('FAILED', 'n', '{"a": ["b"]}'))
        self.assertEqual(portal.parse_status(404, b'').state, portal.ABSENT)
        self.assertEqual(portal.parse_status(400, b'').state, portal.ABSENT)
        for code, data in ((500, b''), (None, b''), (200, b'not json'), (200, b'[]'),
                           (200, b'{"deploymentState": "DONE"}'), (200, b'{}')):
            with self.subTest(code=code, data=data):
                self.assertEqual(portal.parse_status(code, data).state, portal.QUERY_FAILED)

    def test_record_names(self):
        self.assertEqual(portal.parse_record(portal.record_name(3, ID_A)), (3, ID_A))
        self.assertIsNone(portal.parse_record('conformance-report-jdk11'))
        upper = 'central-deployment-1-ABCDEF01-2345-4678-89AB-CDEF01234567'
        for name in ('central-deployment-0-' + ID_A, upper, 'central-intent-1'):
            with self.subTest(name), self.assertRaises(Stop):
                portal.parse_record(name)


class StubbedCommandsTest(unittest.TestCase):
    """スクリプトを別のプロセスで流し、GitHub・Portal・repo1 をスタブの HTTP サーバーで返す。"""

    def setUp(self):
        self.stub = Stub()
        self.addCleanup(self.stub.close)
        self.directory = Path(tempfile.mkdtemp())
        self.output = self.directory / 'output'
        self.summary = self.directory / 'summary'
        self.output.write_text('')
        self.summary.write_text('')
        self.tags = self.directory / 'remote-tags'
        self.tags.write_text('a' * 40 + '\trefs/tags/v5.2.0\n' + 'b' * 40 + '\trefs/tags/v5.3.0\n')
        for artifact in portal.bundle.ARTIFACTS:
            self.stub.routes[('HEAD', f'/maven2/io/github/j5ik2o/{artifact}/5.3.0/{artifact}-5.3.0.pom')] = \
                lambda request: (404, b'')

    def script(self, *args, attempt=2):
        env = {
            **os.environ, 'GH_TOKEN': 'gh-token', 'GITHUB_REPOSITORY': 'o/r', 'GITHUB_RUN_ID': str(RUN),
            'GITHUB_RUN_ATTEMPT': str(attempt), 'SONATYPE_USERNAME': 'user', 'SONATYPE_PASSWORD': 'secret-password',
            'CENTRAL_DEPLOYMENTS': '', 'GITHUB_API_URL': self.stub.url, 'CENTRAL_API_URL': self.stub.url,
            'REPO1_URL': self.stub.url + '/maven2', 'GITHUB_OUTPUT': str(self.output),
            'GITHUB_STEP_SUMMARY': str(self.summary),
        }
        env.pop('GITHUB_ACTIONS', None)
        result = subprocess.run([sys.executable, str(GITHUB / 'central-portal.py'), *args], env=env,
                                capture_output=True, text=True, timeout=60)
        for text in (result.stdout, result.stderr):
            self.assertNotIn('secret-password', text)
            self.assertNotIn(base64.b64encode(b'user:secret-password').decode(), text)
        return result

    def github_run(self, jobs, artifacts):
        created = (NOW - timedelta(days=1)).strftime('%Y-%m-%dT%H:%M:%SZ')
        self.stub.routes[('GET', '/repos/o/r/actions/workflows/release.yml/runs')] = lambda request: (200, {
            'total_count': 1, 'workflow_runs': [
                {'id': RUN, 'created_at': created, 'head_branch': 'v5.3.0', 'event': 'push'}]})
        self.stub.routes[('GET', f'/repos/o/r/actions/runs/{RUN}/jobs')] = lambda request: (200, {
            'total_count': len(jobs), 'jobs': jobs})
        self.stub.routes[('GET', f'/repos/o/r/actions/runs/{RUN}/artifacts')] = lambda request: (200, {
            'total_count': len(artifacts), 'artifacts': artifacts})

    @staticmethod
    def job_json(attempt, upload_conclusion):
        pairs = passed_pre_steps() + ((portal.UPLOAD_STEP, upload_conclusion),)
        return {'name': portal.RELEASE_JOB, 'run_attempt': attempt, 'started_at': f'2026-12-01T00:0{attempt}:00Z',
                'completed_at': f'2026-12-01T00:0{attempt}:30Z', 'runner_id': 7 + attempt,
                'runner_name': 'GitHub Actions', 'steps': [
                    {'name': name, 'status': 'completed', 'conclusion': conclusion} for name, conclusion in pairs]}

    def test_precheck_waits_for_an_earlier_attempt_and_then_does_not_upload(self):
        expires = (NOW + timedelta(days=89)).strftime('%Y-%m-%dT%H:%M:%SZ')
        self.github_run([self.job_json(1, 'success')],
                        [{'name': portal.record_name(1, ID_A), 'expired': False, 'expires_at': expires}])
        states = iter(['PUBLISHING', 'PUBLISHED', 'PUBLISHED'])

        def status(request):
            self.assertEqual(request['query']['id'], [ID_A])
            self.assertEqual(request['headers']['Authorization'],
                             'Bearer ' + base64.b64encode(b'user:secret-password').decode())
            return 200, {'deploymentId': ID_A, 'deploymentName': portal.deployment_name('5.3.0', RUN, 1),
                         'deploymentState': next(states)}

        self.stub.routes[('POST', '/api/v1/publisher/status')] = status
        result = self.script('precheck', '--version', '5.3.0', '--remote-tags', str(self.tags),
                             '--attempts', '3', '--interval', '0')
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(outputs(self.output), {'action': 'published', 'deployment': ID_A})
        self.assertEqual(self.stub.paths('GET').count('/repos/o/r/actions/workflows/release.yml/runs'), 2)
        self.assertNotIn('/api/v1/publisher/upload', self.stub.paths())

    def test_precheck_stops_on_an_upload_without_an_id(self):
        self.github_run([self.job_json(1, 'failure')], [])
        result = self.script('precheck', '--version', '5.3.0', '--remote-tags', str(self.tags),
                             '--attempts', '2', '--interval', '0')
        self.assertEqual(result.returncode, 1)
        self.assertIn(f'{RUN}/1=<deployment ID|none>', result.stderr)
        self.assertEqual(outputs(self.output), {})
        self.assertFalse([path for path in self.stub.paths() if path.startswith('/api/')])

    def test_precheck_stops_when_github_fails(self):
        result = self.script('precheck', '--version', '5.3.0', '--remote-tags', str(self.tags))
        self.assertEqual(result.returncode, 1)
        self.assertIn('GitHub returned 404', result.stderr)

    def test_upload_writes_the_id_first(self):
        bundle = self.directory / 'bundle.zip'
        bundle.write_bytes(b'PK-zip-bytes')
        seen = {}

        def upload(request):
            seen.update(request)
            return 201, ID_B + '\n'

        self.stub.routes[('POST', '/api/v1/publisher/upload')] = upload
        result = self.script('upload', '--version', '5.3.0', '--bundle', str(bundle))
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(self.output.read_text(), f'deployment={ID_B}\n')
        self.assertIn(ID_B, self.summary.read_text())
        self.assertIn(f'::notice::deployment {ID_B}', result.stdout)
        self.assertEqual(seen['query'], {'publishingType': ['AUTOMATIC'],
                                         'name': [portal.deployment_name('5.3.0', RUN, 2)]})
        self.assertEqual(seen['headers']['Authorization'],
                         'Bearer ' + base64.b64encode(b'user:secret-password').decode())
        self.assertIn(b'name="bundle"; filename="bundle.zip"', seen['body'])
        self.assertIn(b'PK-zip-bytes', seen['body'])

    def test_upload_without_a_deployment_id_fails_without_an_output(self):
        for code, body in ((500, 'oops'), (201, 'not an id'), (200, ID_B)):
            with self.subTest(code=code, body=body):
                self.output.write_text('')
                self.stub.routes[('POST', '/api/v1/publisher/upload')] = lambda request: (code, body)
                bundle = self.directory / 'bundle.zip'
                bundle.write_bytes(b'zip')
                result = self.script('upload', '--version', '5.3.0', '--bundle', str(bundle))
                self.assertEqual(result.returncode, 1)
                self.assertEqual(self.output.read_text(), '')
                self.assertIn(f'{RUN}/2=<deployment ID|none>', result.stderr)

    def test_wait_reads_the_state_until_published_or_failed(self):
        def answer(states):
            sequence = iter(states)
            return lambda request: next(sequence)

        published = answer([(503, b''), (200, {'deploymentState': 'VALIDATING'}),
                            (200, {'deploymentState': 'PUBLISHED'})])
        self.stub.routes[('POST', '/api/v1/publisher/status')] = published
        result = self.script('wait', '--id', ID_A, '--attempts', '3', '--interval', '0')
        self.assertEqual(result.returncode, 0, result.stderr)
        self.stub.routes[('POST', '/api/v1/publisher/status')] = answer(
            [(200, {'deploymentState': 'FAILED', 'errors': {'pkg': ['missing signature']}})])
        result = self.script('wait', '--id', ID_A, '--attempts', '3', '--interval', '0')
        self.assertEqual(result.returncode, 1)
        self.assertIn('missing signature', result.stderr)
        self.stub.routes[('POST', '/api/v1/publisher/status')] = lambda request: (200, {'deploymentState': 'PENDING'})
        result = self.script('wait', '--id', ID_A, '--attempts', '2', '--interval', '0')
        self.assertEqual(result.returncode, 1)
        self.assertIn('re-run the failed jobs', result.stderr)
        self.assertNotIn('/api/v1/publisher/upload', self.stub.paths())

    def test_confirm_waits_for_all_poms(self):
        seen = {'count': 0}

        def pom(request):
            seen['count'] += 1
            return (200 if seen['count'] > 6 else 404), b''

        for artifact in portal.bundle.ARTIFACTS:
            self.stub.routes[('HEAD', f'/maven2/io/github/j5ik2o/{artifact}/5.3.0/{artifact}-5.3.0.pom')] = pom
        result = self.script('confirm', '--version', '5.3.0', '--attempts', '3', '--interval', '0')
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(seen['count'], 12)
        result = self.script('confirm', '--version', '5.4.0', '--attempts', '2', '--interval', '0')
        self.assertEqual(result.returncode, 1)
        self.assertIn('re-run only this job', result.stderr)

    def test_check_version_reads_standard_input(self):
        result = subprocess.run([sys.executable, str(GITHUB / 'central-portal.py'), 'check-version', '--tag', 'v5.3.0'],
                                input='version: 5.3.1\n', capture_output=True, text=True)
        self.assertEqual(result.returncode, 1)
        self.assertIn('Gradle builds 5.3.1', result.stderr)


if __name__ == '__main__':
    unittest.main()
