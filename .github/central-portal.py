#! /usr/bin/env python3
"""Maven Central（Central Portal）への公開の、事前の確認・アップロード・待ち・repo1 の確認。

設計：R2b の設計の追記（v3）の §1.1〜§1.6。判定は central-rules.py の decide。このファイルは照会と書き込みをする。

precheck --version X.Y.Z [--remote origin] [--attempts N --interval S]
    release.yml の run・jobs・artifact、変数 CENTRAL_DEPLOYMENTS、Portal の状態、repo1 を読んで決める。
    出力（$GITHUB_OUTPUT）：action=upload|published、deployment=<ID または空>。止まるときは終了コード 1。
upload --version X.Y.Z --bundle <zip> --record <ファイル>
    bundle を publishingType=AUTOMATIC で上げる（ファイル名は <deployment の名前>.zip）。受け取った ID を最初に --record の
    ファイルへ、次に $GITHUB_OUTPUT の deployment に書く（release.yml の ID の記録の手順が、そのファイルを artifact にする）。
wait --id <ID> [--attempts N --interval S]
    deployment が PUBLISHED になるまで待つ。FAILED は errors を出して失敗。時間切れも失敗。
confirm --version X.Y.Z [--attempts N --interval S]
    repo1 が6つの成果物の POM を返すまで待つ。
check-version --tag vX.Y.Z  （標準入力に `gradlew -q properties --property version` の出力）

環境変数：GH_TOKEN、GITHUB_REPOSITORY、GITHUB_RUN_ID、GITHUB_RUN_ATTEMPT、SONATYPE_USERNAME、SONATYPE_PASSWORD、
CENTRAL_DEPLOYMENTS（repository variable）、GITHUB_API_URL・CENTRAL_API_URL・REPO1_URL（テストで差し替える）。
"""
import argparse
import base64
import importlib.util
import json
import os
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
from dataclasses import replace
from datetime import datetime, timezone
from pathlib import Path


def _load(name, filename):
    if name in sys.modules:
        return sys.modules[name]
    spec = importlib.util.spec_from_file_location(name, Path(__file__).resolve().parent / filename)
    module = importlib.util.module_from_spec(spec)
    sys.modules[name] = module
    spec.loader.exec_module(module)
    return module


rules = _load('central_rules', 'central-rules.py')
gate, bundle, Stop = rules.gate, rules.bundle, rules.Stop

# ---------------------------------------------------------------- 照会

def http(method, url, headers=None, body=None, timeout=60):
    """(状態の番号, 本文) を返す。接続の失敗と時間切れは (None, b'')。"""
    request = urllib.request.Request(url, data=body, method=method, headers=headers or {})
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            return response.status, response.read()
    except urllib.error.HTTPError as error:
        return error.code, error.read()
    except (urllib.error.URLError, TimeoutError, OSError):
        return None, b''


def central_auth():
    user, password = os.environ.get('SONATYPE_USERNAME', ''), os.environ.get('SONATYPE_PASSWORD', '')
    if not user or not password:
        raise Stop('SONATYPE_USERNAME and SONATYPE_PASSWORD must be set')
    token = base64.b64encode(f'{user}:{password}'.encode('utf-8')).decode('ascii')
    if os.environ.get('GITHUB_ACTIONS') == 'true':
        print(f'::add-mask::{token}')
    return {'Authorization': f'Bearer {token}'}


def central_url(path):
    return os.environ.get('CENTRAL_API_URL', 'https://central.sonatype.com').rstrip('/') + path


def query_status(deployment, auth):
    code, body = http('POST', central_url('/api/v1/publisher/status?id=' + urllib.parse.quote(deployment)), auth)
    return rules.parse_status(code, body)


def repo1_code(artifact, version):
    base = os.environ.get('REPO1_URL', 'https://repo1.maven.org/maven2').rstrip('/')
    code, _ = http('HEAD', f'{base}/{bundle.GROUP_PATH}/{artifact}/{version}/{artifact}-{version}.pom')
    return code


class GitHub:
    def __init__(self):
        self.base = os.environ.get('GITHUB_API_URL', 'https://api.github.com').rstrip('/')
        self.repository = os.environ['GITHUB_REPOSITORY']
        self.headers = {'Authorization': f'Bearer {os.environ["GH_TOKEN"]}', 'Accept': 'application/vnd.github+json',
                        'X-GitHub-Api-Version': '2022-11-28'}

    def get(self, path):
        code, body = http('GET', f'{self.base}/repos/{self.repository}/{path}', self.headers)
        if code != 200:
            raise Stop(f'GitHub returned {code} for {path}; re-run the job')
        try:
            return json.loads(body)
        except ValueError as error:
            raise Stop(f'GitHub returned a body that is not JSON for {path}') from error

    def pages(self, path, key):
        """ページを送って全件を返す。(全件の数, 項目)。"""
        items, page, total = [], 1, None
        while True:
            separator = '&' if '?' in path else '?'
            data = self.get(f'{path}{separator}per_page=100&page={page}')
            total = data.get('total_count', 0)
            batch = data.get(key) or []
            items += batch
            if not batch or len(items) >= total or len(items) >= rules.MAX_RUNS:
                return total, items
            page += 1


def timestamp(text):
    return datetime.fromisoformat(text.replace('Z', '+00:00')) if text else None


def remote_tags(remote):
    """リモートのタグを読む（`git ls-remote --tags`）。読むたびに取り直す（待つあいだに新しいタグができうる）。"""
    try:
        result = subprocess.run(['git', 'ls-remote', '--tags', remote], capture_output=True, text=True, timeout=120)
    except subprocess.TimeoutExpired as error:
        raise Stop(f'listing the tags of {remote} timed out; re-run the job') from error
    if result.returncode != 0:
        raise Stop(f'could not list the tags of {remote}: {result.stderr.strip()}; re-run the job')
    return result.stdout


def run_attempts(item):
    value = item.get('run_attempt')
    if not isinstance(value, int) or value < 1:
        raise Stop(f'GitHub returned no run_attempt for run {item.get("id")}; re-run the job')
    return value


def gather(version, remote, github, now):
    total, raw_runs = github.pages('actions/workflows/release.yml/runs', 'workflow_runs')
    runs = []
    for item in raw_runs:
        run = rules.Run(id=int(item['id']), created_at=timestamp(item['created_at']),
                        head_branch=item.get('head_branch'), event=item.get('event', ''),
                        attempts=run_attempts(item))
        if rules.counted(run, now):
            _, jobs = github.pages(f'actions/runs/{run.id}/jobs?filter=all', 'jobs')
            _, artifacts = github.pages(f'actions/runs/{run.id}/artifacts', 'artifacts')
            run = rules.Run(
                run.id, run.created_at, run.head_branch, run.event,
                jobs=tuple(rules.Job(
                    name=job.get('name', ''), run_attempt=int(job.get('run_attempt') or 1),
                    started_at=job.get('started_at'), completed_at=job.get('completed_at'),
                    runner_id=job.get('runner_id'), runner_name=job.get('runner_name'),
                    conclusion=job.get('conclusion'),
                    steps=tuple(rules.Step(step.get('name', ''), step.get('status'), step.get('conclusion'))
                                for step in job.get('steps') or ()),
                ) for job in jobs),
                artifacts=tuple(rules.Artifact(artifact['name'], bool(artifact.get('expired')),
                                         timestamp(artifact.get('expires_at')))
                                for artifact in artifacts if artifact.get('name', '').startswith(rules.RECORD_PREFIX)),
                attempts=run.attempts,
            )
        runs.append(run)
    try:
        tags = tuple(gate.stable_tags(gate.remote_tags(remote_tags(remote))).keys())
    except ValueError as error:
        raise Stop(f'could not read the tags of {remote}: {error}; re-run the job') from error
    return rules.Facts(
        version=version, current_run=int(os.environ.get('GITHUB_RUN_ID', '0')),
        current_attempt=int(os.environ.get('GITHUB_RUN_ATTEMPT', '1')), now=now, total_runs=total, runs=tuple(runs),
        overrides_text=os.environ.get('CENTRAL_DEPLOYMENTS', ''), remote_tags=tags,
    )


def write_output(**values):
    path = os.environ.get('GITHUB_OUTPUT')
    if path:
        with open(path, 'a', encoding='utf-8') as output:
            for key, value in values.items():
                output.write(f'{key}={value}\n')


def summary(text):
    path = os.environ.get('GITHUB_STEP_SUMMARY')
    if path:
        with open(path, 'a', encoding='utf-8') as output:
            output.write(text + '\n')


# ---------------------------------------------------------------- コマンド

def precheck(args, sleep=time.sleep, clock=lambda: datetime.now(timezone.utc)):
    """GitHub の事実は最初と、待ちが明けたときだけ読む。待つあいだは Portal と repo1 だけを読み直す。"""
    github = GitHub()
    auth = {}

    def evaluate(facts):
        ids = rules.ids_to_query(facts)
        if ids and not auth:
            auth.update(central_auth())
        statuses = {deployment: query_status(deployment, auth) for deployment in sorted(ids)}
        repo1 = {artifact: repo1_code(artifact, args.version) for artifact in bundle.ARTIFACTS}
        return rules.decide(replace(facts, statuses=statuses, repo1=repo1))

    facts = gather(args.version, args.remote, github, clock())
    decision = evaluate(facts)
    for attempt in range(1, args.attempts + 1):
        if decision.action != 'wait':
            write_output(action=decision.action, deployment=decision.deployment)
            print(f'precheck: {decision.action} {decision.deployment}'.rstrip())
            return 0
        print(f'deployments in progress: {", ".join(decision.waiting)} ({attempt}/{args.attempts}); waiting')
        if attempt == args.attempts:
            break
        sleep(args.interval)
        decision = evaluate(facts)
        if decision.action != 'wait':
            # 待ちが明けたら、GitHub の事実とリモートのタグを読み直して 1 から決め直す（設計 §1.4 の 5）
            facts = gather(args.version, args.remote, github, clock())
            decision = evaluate(facts)
    raise Stop(f'deployments are still in progress after {args.attempts} checks; re-run the job later')


def multipart(path, filename):
    boundary = uuid.uuid4().hex
    data = Path(path).read_bytes()
    head = (f'--{boundary}\r\nContent-Disposition: form-data; name="bundle"; filename="{filename}"\r\n'
            'Content-Type: application/octet-stream\r\n\r\n')
    body = head.encode('utf-8') + data + f'\r\n--{boundary}--\r\n'.encode('utf-8')
    return f'multipart/form-data; boundary={boundary}', body


def upload(args):
    run_id = int(os.environ['GITHUB_RUN_ID'])
    attempt = int(os.environ.get('GITHUB_RUN_ATTEMPT', '1'))
    name = rules.deployment_name(args.version, run_id, attempt)
    # deploymentName がファイル名から来る場合も、事前の確認の名前の照合に合うようにする（設計 §7）
    content_type, body = multipart(args.bundle, f'{name}.zip')
    headers = {**central_auth(), 'Content-Type': content_type}
    query = urllib.parse.urlencode({'publishingType': 'AUTOMATIC', 'name': name})
    code, response = http('POST', central_url(f'/api/v1/publisher/upload?{query}'), headers, body, timeout=600)
    deployment = response.decode('utf-8', errors='replace').strip()
    if code != 201 or not rules.UUID.fullmatch(deployment):
        raise Stop(f'the upload returned {code}; the Portal may or may not have created deployment {name}. '
                   f'Find it in the Portal and add {run_id}/{attempt}=<deployment ID|none> to CENTRAL_DEPLOYMENTS')
    # 落ちても人が ID を拾えるように、記録のファイル、出力、summary とログの順に書く（設計 §1.1）
    Path(args.record).write_text(deployment + '\n', encoding='utf-8')
    write_output(deployment=deployment)
    summary(f'Uploaded {name} to Maven Central: deployment `{deployment}`')
    print(f'::notice::deployment {deployment} ({name})')
    return 0


def wait(args, sleep=time.sleep):
    auth = central_auth()
    for attempt in range(1, args.attempts + 1):
        status = query_status(args.id, auth)
        if status.state == 'PUBLISHED':
            write_output(state='PUBLISHED')
            print(f'deployment {args.id} is PUBLISHED')
            return 0
        if status.state == 'FAILED':
            raise Stop(f'deployment {args.id} FAILED: {status.errors or "no errors given"}')
        print(f'deployment {args.id}: {status.state} ({attempt}/{args.attempts})')
        if attempt < args.attempts:
            sleep(args.interval)
    raise Stop(f'deployment {args.id} is not PUBLISHED after {args.attempts} checks; re-run the failed jobs '
               '(the precheck waits for the same deployment and does not upload again)')


def confirm(args, sleep=time.sleep):
    for attempt in range(1, args.attempts + 1):
        codes = {artifact: repo1_code(artifact, args.version) for artifact in bundle.ARTIFACTS}
        if all(code == 200 for code in codes.values()):
            print(f'repo1 has {args.version} of all {len(codes)} artifacts')
            return 0
        missing = ', '.join(f'{artifact} ({code})' for artifact, code in codes.items() if code != 200)
        print(f'repo1 does not have yet: {missing} ({attempt}/{args.attempts})')
        if attempt < args.attempts:
            sleep(args.interval)
    raise Stop(f'repo1 does not have {args.version} after {args.attempts} checks; re-run only this job')


def check_version(args):
    print(rules.check_build_version(sys.stdin.read(), args.tag))
    return 0


def main(argv):
    parser = argparse.ArgumentParser(prog='central-portal.py')
    commands = parser.add_subparsers(dest='command', required=True)
    pre = commands.add_parser('precheck')
    pre.set_defaults(run=precheck)
    pre.add_argument('--version', required=True)
    pre.add_argument('--remote', default='origin', help='git remote whose tags are listed (git ls-remote --tags)')
    up = commands.add_parser('upload')
    up.set_defaults(run=upload)
    up.add_argument('--version', required=True)
    up.add_argument('--bundle', required=True)
    up.add_argument('--record', required=True, help='file that receives the deployment ID (uploaded as an artifact)')
    waiter = commands.add_parser('wait')
    waiter.set_defaults(run=wait)
    waiter.add_argument('--id', required=True)
    conf = commands.add_parser('confirm')
    conf.set_defaults(run=confirm)
    conf.add_argument('--version', required=True)
    for command, attempts, interval in ((pre, 120, 30), (waiter, 120, 30), (conf, 60, 60)):
        command.add_argument('--attempts', type=int, default=attempts)
        command.add_argument('--interval', type=float, default=interval)
    version = commands.add_parser('check-version')
    version.set_defaults(run=check_version)
    version.add_argument('--tag', required=True)
    args = parser.parse_args(argv[1:])
    try:
        return args.run(args)
    except Stop as error:
        print(f'::error::{error}', file=sys.stderr)
        return 1


if __name__ == '__main__':
    sys.exit(main(sys.argv))
