"""central-portal.py のテストが共有する事実の組み立てと、HTTP のスタブ。"""
import json
import threading
from datetime import datetime, timedelta, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, urlparse

from release_test_support import load_script

# central-portal.py は照会とコマンド、central-rules.py（portal）は判定。同じ判定の module を共有する
commands = load_script('central-portal.py', 'central_portal')
portal = commands.rules
FIXTURES = Path(__file__).resolve().parent / 'fixtures'
NOW = datetime(2026, 12, 2, tzinfo=timezone.utc)
RUN = portal.FIRST_COUNTED_RUN_ID + 100
ID_A = '11111111-1111-4111-8111-111111111111'
ID_B = '22222222-2222-4222-8222-222222222222'
ID_C = '33333333-3333-4333-8333-333333333333'
PRE_STEPS = sorted(portal.PRE_UPLOAD_STEPS - {'Set up job'})


def steps(*pairs):
    return tuple(portal.Step(name, 'completed', conclusion) for name, conclusion in pairs)


def passed_pre_steps():
    return (('Set up job', 'success'),) + tuple((name, 'success') for name in PRE_STEPS)


def release_job(attempt, upload='success', started=None, runner=None, step_pairs=None, runner_name='GitHub Actions 1'):
    """release ジョブの実行。upload は手順の conclusion（None は手順が一覧に無い）。"""
    if step_pairs is None:
        step_pairs = passed_pre_steps() + (((portal.UPLOAD_STEP, upload),) if upload != 'absent' else ())
    return portal.Job(
        name=portal.RELEASE_JOB, run_attempt=attempt,
        started_at=started or f'2026-12-01T00:0{attempt}:00Z', completed_at=f'2026-12-01T00:0{attempt}:30Z',
        runner_id=runner if runner is not None else 1000 + attempt, runner_name=runner_name,
        steps=steps(*step_pairs),
    )


def record(attempt, deployment, run_created=NOW - timedelta(days=1)):
    return portal.Artifact(portal.record_name(attempt, deployment), False, run_created + timedelta(days=90))


def run(run_id=RUN, tag='v5.3.0', age=1, jobs=(), artifacts=(), event='push', attempts=None):
    if attempts is None:
        attempts = max([job.run_attempt for job in jobs] or [1])
    return portal.Run(run_id, NOW - timedelta(days=age), tag, event, tuple(jobs), tuple(artifacts), attempts)


def facts(runs=(), version='5.3.0', overrides='', tags=('v5.2.0', 'v5.3.0'), statuses=None, repo1=None,
          current_run=RUN + 50, current_attempt=1, total=None):
    runs = tuple(runs)
    if current_run not in {item.id for item in runs}:
        # 今の run も release.yml の run の一覧に入る（release ジョブは動いている途中）
        runs += (run(run_id=current_run, tag=f'v{version}', age=0),)
    if repo1 is None:
        repo1 = {artifact: 404 for artifact in portal.bundle.ARTIFACTS}
    return portal.Facts(
        version=version, current_run=current_run, current_attempt=current_attempt, now=NOW,
        total_runs=len(runs) if total is None else total, runs=runs, overrides_text=overrides,
        remote_tags=tuple(tags), statuses=dict(statuses or {}), repo1=repo1,
    )


def named(deployment_state, version='5.3.0', run_id=RUN, attempt=1, zip_suffix=False):
    name = portal.deployment_name(version, run_id, attempt) + ('.zip' if zip_suffix else '')
    return portal.Status(deployment_state, name)


def fixture_jobs(run_id, rename=None):
    """実物の jobs の JSON（.github/tests/fixtures）を Job にする。rename の名前の job を release ジョブの名前にする。"""
    data = json.loads((FIXTURES / f'jobs-{run_id}.json').read_text())
    jobs = []
    for job in data['jobs']:
        name = portal.RELEASE_JOB if rename is None or job['name'] == rename else job['name']
        jobs.append(portal.Job(
            name=name, run_attempt=job['run_attempt'], started_at=job['started_at'],
            completed_at=job['completed_at'], runner_id=job['runner_id'], runner_name=job['runner_name'],
            steps=tuple(portal.Step(step['name'], step['status'], step['conclusion']) for step in job['steps']),
        ))
    return jobs


class Stub:
    """GitHub・Portal・repo1 の口を1つの HTTP サーバーで返す。routes は (メソッド, パス) → 関数(要求) → (番号, 本文)。"""

    def __init__(self):
        self.calls = []
        self.routes = {}
        stub = self

        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *args):
                pass

            def handle_any(self):
                length = int(self.headers.get('Content-Length') or 0)
                body = self.rfile.read(length) if length else b''
                parsed = urlparse(self.path)
                request = {'method': self.command, 'path': parsed.path, 'query': parse_qs(parsed.query),
                           'headers': dict(self.headers), 'body': body}
                stub.calls.append(request)
                handler = stub.routes.get((self.command, parsed.path))
                code, payload = handler(request) if handler else (404, b'')
                if isinstance(payload, (dict, list)):
                    payload = json.dumps(payload).encode()
                elif isinstance(payload, str):
                    payload = payload.encode()
                self.send_response(code)
                self.send_header('Content-Length', str(len(payload)))
                self.end_headers()
                if self.command != 'HEAD':
                    self.wfile.write(payload)

            do_GET = do_POST = do_HEAD = handle_any

        self.server = ThreadingHTTPServer(('127.0.0.1', 0), Handler)
        self.url = f'http://127.0.0.1:{self.server.server_port}'
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()

    def close(self):
        self.server.shutdown()
        self.server.server_close()

    def paths(self, method=None):
        return [call['path'] for call in self.calls if method is None or call['method'] == method]
