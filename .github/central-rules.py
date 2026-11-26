#! /usr/bin/env python3
"""公開の事前の確認の判定（設計：R2b の設計の追記 v3 の §1.1〜§1.4）。照会はせず、事実を受け取って決める。

central-portal.py が照会した結果を Facts にまとめて decide に渡す。テストは、この関数を事実の組み立てと PBT で確かめる。
"""
import importlib.util
import json
import re
import sys
from dataclasses import dataclass, field
from datetime import datetime, timedelta
from pathlib import Path
from typing import Optional

HERE = Path(__file__).resolve().parent


def _load(name, filename):
    if name in sys.modules:
        return sys.modules[name]
    spec = importlib.util.spec_from_file_location(name, HERE / filename)
    module = importlib.util.module_from_spec(spec)
    sys.modules[name] = module
    spec.loader.exec_module(module)
    return module


gate = _load('release_gate', 'release-gate.py')
bundle = _load('central_bundle', 'central-bundle.py')

# release.yml の名前（R2b-2 の形のテストが、release.yml と一致することを確かめる）。
# 名前は変えない。変えるときは、窓の中の古い run と合うように古い名前も集合に残す（設計 §1.1 の A1 の残り (a)）。
RELEASE_JOB = 'Publish to Maven Central'
UPLOAD_STEP = 'Upload the bundle to Maven Central'
PRE_UPLOAD_STEPS = frozenset({
    'Set up job',
    'Check out the tagged commit',
    'Set up Python',
    'Check the tagged commit against the release gate',
    'Set up Java 11',
    'Set up the Gradle runtime',
    'Set up Gradle',
    'Check the version Gradle builds',
    'Check Maven Central before publishing',
    'Build the bundle',
})
# v5.2.0 の run（42807727008）の次。これより後の release.yml の run は、手順の名前の有無に関わらず数える
FIRST_COUNTED_RUN_ID = 42807727009
BOUNDARY_TAG = 'v5.2.0'
WINDOW = timedelta(days=80)
MAX_RUNS = 1000
RECORD_PREFIX = 'central-'
RECORD = re.compile(r'central-deployment-([1-9][0-9]*)-([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})')
UUID = re.compile(r'[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}')
PROCESSING = frozenset({'PENDING', 'VALIDATING', 'VALIDATED', 'PUBLISHING'})
STATES = PROCESSING | {'PUBLISHED', 'FAILED'}
ABSENT = 'ABSENT'          # 照会が「無い」（404・400）と返った
QUERY_FAILED = 'QUERY_FAILED'
NONE = 'none'


class Stop(Exception):
    """公開せずに止める。文には、人が入れる変数の行の形を含める。"""


# ---------------------------------------------------------------- 事実

@dataclass(frozen=True)
class Step:
    name: str
    status: Optional[str]
    conclusion: Optional[str]


@dataclass(frozen=True)
class Job:
    name: str
    run_attempt: int
    started_at: Optional[str]
    completed_at: Optional[str]
    runner_id: Optional[int]
    runner_name: Optional[str]
    steps: tuple


@dataclass(frozen=True)
class Artifact:
    name: str
    expired: bool
    expires_at: Optional[datetime]


@dataclass(frozen=True)
class Run:
    id: int
    created_at: datetime
    head_branch: Optional[str]
    event: str
    jobs: tuple = ()          # filter=all の release ジョブ（数える run だけ読む）
    artifacts: tuple = ()     # 名前が central- で始まるもの（数える run だけ読む）


@dataclass(frozen=True)
class Status:
    state: str                # STATES、ABSENT、QUERY_FAILED のどれか
    name: Optional[str] = None
    errors: Optional[str] = None


@dataclass(frozen=True)
class Overrides:
    attempts: dict            # (run_id, attempt) -> 'none' か ID
    checked_tags: frozenset


@dataclass(frozen=True)
class Facts:
    version: str
    current_run: int
    current_attempt: int
    now: datetime
    total_runs: int
    runs: tuple
    overrides_text: str
    remote_tags: tuple        # リモートの正式版のタグの名前
    statuses: dict = field(default_factory=dict)   # ID -> Status
    repo1: dict = field(default_factory=dict)      # 成果物 -> 200・404・それ以外（数）か None（照会の失敗）


@dataclass(frozen=True)
class Decision:
    action: str               # 'upload'、'published'、'wait'
    deployment: str = ''
    waiting: tuple = ()       # 'wait' のとき、処理中の ID


# ---------------------------------------------------------------- 純粋な関数

def parse_overrides(text):
    """CENTRAL_DEPLOYMENTS を読む。行は `<run_id>/<試み>=<ID|none>` か `tag/<vX.Y.Z>=checked`。"""
    attempts, tags = {}, set()
    for number, raw in enumerate((text or '').splitlines(), 1):
        line = raw.strip()
        if not line:
            continue
        match = re.fullmatch(r'([1-9][0-9]*)/([1-9][0-9]*)=(\S+)', line)
        if match:
            key = (int(match.group(1)), int(match.group(2)))
            value = match.group(3)
            if value != NONE and not UUID.fullmatch(value):
                raise Stop(f'CENTRAL_DEPLOYMENTS line {number}: {value!r} is neither a deployment ID nor none')
            if key in attempts:
                raise Stop(f'CENTRAL_DEPLOYMENTS has two lines for run {key[0]} attempt {key[1]}')
            attempts[key] = value
            continue
        match = re.fullmatch(r'tag/(v\S+)=checked', line)
        if match and gate.parse(match.group(1)[1:]) is not None:
            if match.group(1) in tags:
                raise Stop(f'CENTRAL_DEPLOYMENTS has two lines for {match.group(1)}')
            tags.add(match.group(1))
            continue
        raise Stop(f'CENTRAL_DEPLOYMENTS line {number} is not <run_id>/<attempt>=<deployment ID|none> '
                   f'or tag/vX.Y.Z=checked: {line!r}')
    return Overrides(attempts, frozenset(tags))


def parse_record(name):
    """artifact の名前を (試み, ID) にする。central- で始まらなければ None。形が違えば止める。"""
    if not name.startswith(RECORD_PREFIX):
        return None
    match = RECORD.fullmatch(name)
    if not match:
        raise Stop(f'artifact {name!r} starts with {RECORD_PREFIX} but is not central-deployment-<attempt>-<ID>')
    return int(match.group(1)), match.group(2)


def record_name(attempt, deployment):
    return f'central-deployment-{attempt}-{deployment}'


def deployment_name(version, run_id, attempt):
    return f'event-store-adapter-java-{version}-run{run_id}-{attempt}'


def executions(jobs):
    """release ジョブの実行。「失敗した job の再実行」で次の試みに写された job（started_at・completed_at・runner_id が
    前の試みの release ジョブと同じ）は捨てる（設計 §1.1 の B1）。"""
    kept = []
    for job in sorted((job for job in jobs if job.name == RELEASE_JOB), key=lambda job: job.run_attempt):
        signature = (job.started_at, job.completed_at, job.runner_id)
        if any((other.started_at, other.completed_at, other.runner_id) == signature for other in kept):
            continue
        kept.append(job)
    return kept


def may_have_uploaded(job):
    """アップロードしていないと証明できなければ True（設計 §1.1 の A1）。"""
    upload = [step for step in job.steps if step.name == UPLOAD_STEP]
    if upload:
        return upload[-1].conclusion != 'skipped'
    if any(step.name in PRE_UPLOAD_STEPS and step.conclusion in ('failure', 'cancelled') for step in job.steps):
        return False
    if not job.steps and not job.runner_name:
        return False
    return True


def version_of(run):
    branch = run.head_branch or ''
    return branch[1:] if branch.startswith('v') and gate.parse(branch[1:]) else None


def counted(run, now):
    return run.id >= FIRST_COUNTED_RUN_ID and now - run.created_at <= WINDOW


def attempt_records(facts):
    """数える run の、試みごとの記録。{(run_id, 試み): {'artifact': ID か None, 'override': 値か None,
    'uploaded': bool, 'version': 版}}。形の違う名前・行、食い違いは止める。"""
    overrides = parse_overrides(facts.overrides_text)
    records = {}
    for run in facts.runs:
        if not counted(run, facts.now):
            continue
        artifacts = {}
        for artifact in run.artifacts:
            parsed = parse_record(artifact.name)
            if parsed is None:
                continue
            attempt, deployment = parsed
            if attempt in artifacts and artifacts[attempt] != deployment:
                raise Stop(f'run {run.id} attempt {attempt} has two deployment records')
            if artifact.expired:
                raise Stop(f'the record {artifact.name} of run {run.id} has expired; check the artifact retention '
                           f'(keep it at 90 days)')
            if artifact.expires_at is None or artifact.expires_at < run.created_at + WINDOW:
                raise Stop(f'the record {artifact.name} of run {run.id} expires before {WINDOW.days} days; '
                           'keep the artifact retention at 90 days')
            artifacts[attempt] = deployment
        jobs = executions(run.jobs)
        if not jobs and run.id != facts.current_run:
            raise Stop(f'run {run.id} has no {RELEASE_JOB!r} job; check the deployments of the run in the Portal and '
                       f'add {run.id}/1=<deployment ID|none> to CENTRAL_DEPLOYMENTS')
        uploaded = {}
        for job in jobs:
            if run.id == facts.current_run and job.run_attempt == facts.current_attempt:
                continue
            uploaded[job.run_attempt] = uploaded.get(job.run_attempt, False) or may_have_uploaded(job)
        for attempt in set(uploaded) | set(artifacts) | {a for (r, a) in overrides.attempts if r == run.id}:
            if run.id == facts.current_run and attempt == facts.current_attempt:
                continue
            override = overrides.attempts.get((run.id, attempt))
            found = artifacts.get(attempt)
            if override not in (None, NONE) and found is not None and override != found:
                raise Stop(f'CENTRAL_DEPLOYMENTS gives {override} for run {run.id} attempt {attempt}, '
                           f'but its record is {found}')
            records[(run.id, attempt)] = {
                'artifact': found, 'override': override, 'uploaded': uploaded.get(attempt, False),
                'version': version_of(run),
            }
    return overrides, records


def ids_to_query(facts):
    """Portal に照会する ID（判定の前に読む）。形の違いで止まるときは、空を返す（decide が止める）。"""
    try:
        _, records = attempt_records(facts)
    except Stop:
        return set()
    ids = set()
    for record in records.values():
        deployment = queried(record)
        if deployment is not None:
            ids.add(deployment)
    return ids


def queried(record):
    """試みの照会する ID。変数の ID が先。=none でも artifact の ID があれば照会する（設計 §1.3 の N1）。"""
    if record['override'] not in (None, NONE):
        return record['override']
    return record['artifact']


def decide(facts):
    """事前の確認の判定（設計 §1.4 の順）。止めるときは Stop を投げる。"""
    if gate.parse(facts.version) is None:
        raise Stop(f'{facts.version} is not a release version X.Y.Z')
    # 1. 形の違い、食い違い（attempt_records が投げる）
    overrides, records = attempt_records(facts)
    # 2. run の数、タグと run の突き合わせ（期限の確かめは attempt_records の中）
    if facts.total_runs > MAX_RUNS:
        raise Stop(f'release.yml has {facts.total_runs} runs; more than {MAX_RUNS} cannot be listed')
    boundary = gate.parse(BOUNDARY_TAG[1:])
    pushed = {run.head_branch for run in facts.runs if run.event == 'push'}
    for tag in sorted(facts.remote_tags):
        if gate.parse(tag[1:]) > boundary and tag not in pushed and tag not in overrides.checked_tags:
            raise Stop(f'tag {tag} has no Release run; check in the Portal that no deployment of {tag[1:]} is in '
                       f'progress, then add tag/{tag}=checked to CENTRAL_DEPLOYMENTS')
    # 3. 始まったのに ID の記録が無い実行
    for (run_id, attempt), record in sorted(records.items()):
        if record['uploaded'] and record['artifact'] is None and record['override'] is None:
            raise Stop(f'run {run_id} attempt {attempt} may have uploaded a bundle but left no deployment ID; '
                       f'find the ID in its job summary or in the Portal (deployment '
                       f'{deployment_name(record["version"], run_id, attempt)}) and add '
                       f'{run_id}/{attempt}=<deployment ID|none> to CENTRAL_DEPLOYMENTS')
    # 4. 照会（Portal、repo1）
    known = []   # (版, ID, Status)
    for (run_id, attempt), record in sorted(records.items()):
        override = record['override']
        deployment = queried(record)
        if deployment is None:
            continue
        status = facts.statuses.get(deployment, Status(QUERY_FAILED))
        if status.state == QUERY_FAILED:
            raise Stop(f'could not read the state of deployment {deployment} (run {run_id} attempt {attempt}); '
                       're-run the job')
        if override == NONE:
            if status.state == ABSENT:
                continue
            raise Stop(f'CENTRAL_DEPLOYMENTS says run {run_id} attempt {attempt} has no deployment, but '
                       f'{deployment} is {status.state}; remove or correct the line')
        if status.state == ABSENT:
            raise Stop(f'deployment {deployment} (run {run_id} attempt {attempt}) is not found in the Portal; if it '
                       f'was dropped, add {run_id}/{attempt}=none to CENTRAL_DEPLOYMENTS')
        expected = deployment_name(record['version'], run_id, attempt)
        if status.name not in (expected, expected + '.zip'):
            raise Stop(f'deployment {deployment} is named {status.name!r}, not {expected!r}; '
                       f'check the line for run {run_id} attempt {attempt} in CENTRAL_DEPLOYMENTS')
        known.append((record['version'], deployment, status))
    for artifact in bundle.ARTIFACTS:
        code = facts.repo1.get(artifact)
        if code not in (200, 404):
            raise Stop(f'could not read {artifact} {facts.version} from repo1 (got {code}); re-run the job')
    # 5. 処理中
    waiting = tuple(sorted(deployment for _, deployment, status in known if status.state in PROCESSING))
    if waiting:
        return Decision('wait', waiting=waiting)
    # 6. 今の版が既に出ている
    published = sorted(deployment for version, deployment, status in known
                       if version == facts.version and status.state == 'PUBLISHED')
    if published:
        return Decision('published', deployment=published[0])
    if any(facts.repo1.get(artifact) == 200 for artifact in bundle.ARTIFACTS):
        return Decision('published')
    # 7. 最新のタグ
    tag = f'v{facts.version}'
    if gate.latest_tag(list(facts.remote_tags)) != tag:
        raise Stop(f'{tag} is not the latest release tag ({gate.latest_tag(list(facts.remote_tags))}); '
                   'an overtaken version is not published')
    # 8.
    return Decision('upload')


def check_build_version(output, tag):
    """`gradlew -q properties --property version` の出力から `version: ` の行を1つだけ読み、タグの版と比べる。"""
    lines = [line.strip() for line in output.replace('\r\n', '\n').replace('\r', '\n').split('\n')]
    values = [line[len('version: '):] for line in lines if line.startswith('version: ')]
    if len(values) != 1:
        raise Stop(f'expected one "version: " line from Gradle, found {len(values)}')
    if f'v{values[0]}' != tag:
        raise Stop(f'Gradle builds {values[0]}, but the tag is {tag}')
    return values[0]


def parse_status(code, body):
    """Portal の status の応答を Status にする。"""
    if code in (400, 404):
        return Status(ABSENT)
    if code != 200:
        return Status(QUERY_FAILED)
    try:
        data = json.loads(body)
    except ValueError:
        return Status(QUERY_FAILED)
    if not isinstance(data, dict):
        return Status(QUERY_FAILED)
    state = data.get('deploymentState')
    if state not in STATES:
        return Status(QUERY_FAILED)
    errors = data.get('errors')
    return Status(state, data.get('deploymentName'), json.dumps(errors, ensure_ascii=False) if errors else None)
