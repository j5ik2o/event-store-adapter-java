"""central-portal.py の性質を Hypothesis で確かめる（設計の追記の P7・P10・P12・P13。除外は付けない）。

P10 の照合の相手は decide ではなく、ここで場面（scenario）の言葉で別に書いた参照の関数 expected_action。
場面は、写し・手順の一覧の形・記録・変数の行・状態を、名前や時刻に描く前の形で持つ。参照の関数は描いたものを読まない。
"""
import contextlib
import io
import unittest
from dataclasses import dataclass
from datetime import timedelta

from hypothesis import assume, given, settings
from hypothesis import strategies as st

from central_portal_support import NOW, commands, facts, named, passed_pre_steps, portal

SETTINGS = settings(max_examples=400, deadline=None, database=None)
FIRST = portal.FIRST_COUNTED_RUN_ID
POOL = ('aaaaaaaa-0000-4000-8000-000000000001', 'bbbbbbbb-0000-4000-8000-000000000002',
        'cccccccc-0000-4000-8000-000000000003', 'dddddddd-0000-4000-8000-000000000004')
TAGS = ('v5.2.0', 'v5.3.0', 'v5.4.0')
# 手順の一覧の形と、アップロードまで届いたか（参照の側の知識。描き方は render_job）
KINDS = {
    'upload-skipped': False, 'upload-success': True, 'upload-failure': True, 'upload-cancelled': True,
    'upload-running': True, 'pre-failed': False, 'pre-cancelled': False, 'cut-off': True,
    'empty-no-runner': False, 'empty-with-runner': True,
}
STATES = ('PENDING', 'VALIDATING', 'VALIDATED', 'PUBLISHING', 'PUBLISHED', 'FAILED', 'ABSENT', 'QUERY_FAILED')


@dataclass(frozen=True)
class Attempt:
    number: int
    kind: str
    copy: bool            # 前の試みの写し（「失敗した job の再実行」）
    record: object        # artifact の ID（POOL の1つ）か None
    override: object      # None、'none'、ID
    malformed_record: bool


@dataclass(frozen=True)
class RunPlan:
    id: int
    age: int
    tag: str
    attempts: tuple
    no_release_job: bool
    expiry: str           # 'ok'、'short'、'expired'


@dataclass(frozen=True)
class Scenario:
    version: str
    runs: tuple
    remote_tags: tuple
    checked: frozenset
    malformed_line: bool
    statuses: dict        # ID -> (状態, 名前が合うか)
    repo1: tuple          # 成果物の順に 200・404・503・None
    total: int


def render_job(attempt, previous):
    kind = attempt.kind
    if attempt.copy and previous is not None:
        return portal.Job(portal.RELEASE_JOB, attempt.number, previous.started_at, previous.completed_at,
                          previous.runner_id, previous.runner_name, previous.steps)
    upload = {'upload-skipped': 'skipped', 'upload-success': 'success', 'upload-failure': 'failure',
              'upload-cancelled': 'cancelled', 'upload-running': None}
    pairs = passed_pre_steps()
    runner_name = 'GitHub Actions 9'
    if kind in upload:
        pairs += ((portal.UPLOAD_STEP, upload[kind]),)
    elif kind == 'pre-failed':
        pairs = (('Set up job', 'success'), ('Check Maven Central before publishing', 'failure'))
    elif kind == 'pre-cancelled':
        pairs = (('Set up job', 'cancelled'),)
    elif kind in ('empty-no-runner', 'empty-with-runner'):
        pairs = ()
        runner_name = '' if kind == 'empty-no-runner' else runner_name
    steps = tuple(portal.Step(name, 'completed', conclusion) for name, conclusion in pairs)
    unique = attempt.number * 7 + hash(kind) % 5
    return portal.Job(portal.RELEASE_JOB, attempt.number, f'2026-12-01T01:{attempt.number:02d}:00Z',
                      f'2026-12-01T01:{attempt.number:02d}:{unique % 60:02d}Z', 5000 + attempt.number, runner_name,
                      steps)


def render(scenario, current):
    runs, lines = [], []
    for plan in scenario.runs:
        jobs, artifacts, previous = [], [], None
        created = NOW - timedelta(days=plan.age)
        for attempt in plan.attempts:
            job = render_job(attempt, previous)
            previous = job
            if not plan.no_release_job:
                jobs.append(job)
            if attempt.record is not None:
                expires = None if plan.expiry == 'expired' else created + timedelta(
                    days=90 if plan.expiry == 'ok' else 30)
                name = portal.record_name(attempt.number, attempt.record)
                if attempt.malformed_record:
                    name = name.replace('-deployment-', '-deploy-')
                artifacts.append(portal.Artifact(name, plan.expiry == 'expired', expires))
            if attempt.override is not None:
                lines.append(f'{plan.id}/{attempt.number}={attempt.override}')
        runs.append(portal.Run(plan.id, created, plan.tag, 'push', tuple(jobs), tuple(artifacts),
                               len(plan.attempts)))
    lines += [f'tag/{tag}=checked' for tag in sorted(scenario.checked)]
    if scenario.malformed_line:
        lines.append('this is not a line')
    statuses = {}
    for deployment, (state, name_ok) in scenario.statuses.items():
        owner = [(plan, attempt) for plan in scenario.runs for attempt in plan.attempts
                 if deployment in (attempt.record, attempt.override)]
        if state in ('ABSENT', 'QUERY_FAILED'):
            statuses[deployment] = portal.Status(state)
        elif owner and name_ok:
            plan, attempt = owner[0]
            statuses[deployment] = named(state, version=plan.tag[1:], run_id=plan.id, attempt=attempt.number)
        else:
            statuses[deployment] = portal.Status(state, 'some-other-name')
    repo1 = dict(zip(portal.bundle.ARTIFACTS, scenario.repo1))
    return facts(runs, version=scenario.version, overrides='\n'.join(lines), tags=scenario.remote_tags,
                 statuses=statuses, repo1=repo1, current_run=current[0], current_attempt=current[1],
                 total=scenario.total)


def first_owner(scenario, deployment):
    """render が状態の名前を合わせる試み（その ID を最初に持つ試み）。"""
    for plan in scenario.runs:
        for attempt in plan.attempts:
            if deployment in (attempt.record, attempt.override):
                return plan.id, attempt.number
    return None


def expected_action(scenario, current):
    """設計の追記 §1.4 を、場面の言葉で書いた参照。'stop'、'wait'、'published'、'upload' と、wait の ID の集合。"""
    def counted(plan):
        return plan.id >= FIRST and plan.age <= 80

    live = [plan for plan in scenario.runs if counted(plan)]
    own = [(plan, attempt) for plan in live for attempt in plan.attempts
           if not (plan.id == current[0] and attempt.number == current[1])]
    # 1. 形の違い・食い違い・期限
    if scenario.malformed_line:
        return 'stop', None
    if any(attempt.malformed_record and attempt.record for plan in live for attempt in plan.attempts):
        return 'stop', None
    if any(attempt.override not in (None, 'none') and attempt.record and attempt.override != attempt.record
           for plan, attempt in own):
        return 'stop', None
    if any(plan.expiry != 'ok' and any(attempt.record for attempt in plan.attempts) for plan in live):
        return 'stop', None
    # 2. run の数、タグと run
    if scenario.total > portal.MAX_RUNS:
        return 'stop', None
    run_tags = {plan.tag for plan in scenario.runs}
    if any(tag != 'v5.2.0' and tag not in run_tags and tag not in scenario.checked for tag in scenario.remote_tags):
        return 'stop', None
    # 3. 届いたかもしれない実行に記録が無い（写しは数えない。release ジョブの無い run は、どの試みも届いたかもしれない）
    for plan, attempt in own:
        reached = plan.no_release_job or (not attempt.copy and KINDS[attempt.kind])
        if reached and attempt.record is None and attempt.override is None:
            return 'stop', None
    # 4. 照会
    seen = []
    for plan, attempt in own:
        target = attempt.override if attempt.override not in (None, 'none') else attempt.record
        if target is None:
            continue
        state, name_ok = scenario.statuses.get(target, ('QUERY_FAILED', True))
        if state == 'QUERY_FAILED':
            return 'stop', None
        if attempt.override == 'none':
            if state == 'ABSENT':
                continue
            return 'stop', None
        if state == 'ABSENT' or not (name_ok and first_owner(scenario, target) == (plan.id, attempt.number)):
            return 'stop', None
        seen.append((plan.tag, target, state))
    if any(code not in (200, 404) for code in scenario.repo1):
        return 'stop', None
    # 5〜8
    waiting = {target for _, target, state in seen if state in portal.PROCESSING}
    if waiting:
        return 'wait', waiting
    if any(tag == f'v{scenario.version}' and state == 'PUBLISHED' for tag, _, state in seen) or 200 in scenario.repo1:
        return 'published', None
    if max(scenario.remote_tags, key=lambda tag: tuple(int(part) for part in tag[1:].split('.'))) != \
            f'v{scenario.version}':
        return 'stop', None
    return 'upload', None


def attempts_strategy():
    def build(kinds, copies, records, overrides, malformed):
        result, used = [], set()
        for index, kind in enumerate(kinds):
            number = index + 1
            record = records[index]
            if record in used:
                record = None
            used.add(record)
            result.append(Attempt(number, kind, copies[index] and index > 0, record, overrides[index],
                                  malformed[index]))
        return tuple(result)

    size = st.integers(1, 3)
    return size.flatmap(lambda n: st.builds(
        build,
        st.lists(st.sampled_from(sorted(KINDS)), min_size=n, max_size=n),
        st.lists(st.booleans(), min_size=n, max_size=n),
        st.lists(st.one_of(st.none(), st.sampled_from(POOL)), min_size=n, max_size=n),
        st.lists(st.one_of(st.none(), st.just('none'), st.sampled_from(POOL)), min_size=n, max_size=n),
        st.lists(st.sampled_from((False,) * 9 + (True,)), min_size=n, max_size=n),
    ))


@st.composite
def scenarios(draw):
    version = draw(st.sampled_from(('5.3.0', '5.4.0')))
    count = draw(st.integers(0, 3))
    ids = draw(st.lists(st.sampled_from((FIRST - 5, FIRST, FIRST + 10, FIRST + 20, FIRST + 30)), min_size=count,
                        max_size=count, unique=True))
    plans = []
    for run_id in ids:
        plans.append(RunPlan(
            id=run_id, age=draw(st.sampled_from((1, 79, 81))), tag=draw(st.sampled_from(TAGS[1:])),
            attempts=draw(attempts_strategy()), no_release_job=draw(st.sampled_from((False, False, False, True))),
            expiry=draw(st.sampled_from(('ok', 'ok', 'ok', 'short', 'expired'))),
        ))
    remote = draw(st.sets(st.sampled_from(TAGS[1:])))
    remote = tuple(sorted({'v5.2.0', f'v{version}'} | remote))
    checked = frozenset(draw(st.sets(st.sampled_from(TAGS[1:]))))
    statuses = {deployment: (draw(st.sampled_from(STATES)), draw(st.sampled_from((True, True, True, False))))
                for deployment in POOL}
    repo1_mode = draw(st.sampled_from(('absent', 'absent', 'absent', 'one', 'all', 'error', 'unreachable')))
    repo1 = [404] * len(portal.bundle.ARTIFACTS)
    if repo1_mode == 'one':
        repo1[draw(st.integers(0, len(repo1) - 1))] = 200
    elif repo1_mode == 'all':
        repo1 = [200] * len(repo1)
    elif repo1_mode == 'error':
        repo1[0] = 503
    elif repo1_mode == 'unreachable':
        repo1[-1] = None
    total = draw(st.sampled_from((count, count, count, 1001)))
    malformed_line = draw(st.sampled_from((False,) * 7 + (True,)))
    return Scenario(version, tuple(plans), remote, checked, malformed_line, statuses, tuple(repo1), total)


@st.composite
def scenario_and_current(draw):
    scenario = draw(scenarios())
    # 今の run：場面の run の1つ（その最後の試みが今の試み）か、場面に無い run
    choices = [(plan.id, plan.attempts[-1].number) for plan in scenario.runs if plan.tag == f'v{scenario.version}']
    current = draw(st.sampled_from(choices + [(FIRST + 99, 1)]))
    if current[0] == FIRST + 99:
        own_run = RunPlan(FIRST + 99, 0, f'v{scenario.version}',
                          (Attempt(1, 'upload-running', False, None, None, False),), False, 'ok')
        scenario = Scenario(scenario.version, scenario.runs + (own_run,), scenario.remote_tags, scenario.checked,
                            scenario.malformed_line, scenario.statuses, scenario.repo1, scenario.total)
    return scenario, current


class DecidePropertiesTest(unittest.TestCase):
    """P10：decide は、参照の関数と同じ判定を返す。"""

    @SETTINGS
    @given(scenario_and_current())
    def test_decide_matches_the_reference(self, pair):
        scenario, current = pair
        expected, waiting = expected_action(scenario, current)
        value = render(scenario, current)
        try:
            decision = portal.decide(value)
        except portal.Stop:
            self.assertEqual(expected, 'stop', scenario)
            return
        self.assertEqual(decision.action, expected, scenario)
        if expected == 'wait':
            self.assertEqual(set(decision.waiting), waiting)
        if decision.action == 'upload':
            # 公開するときは、届いたかもしれない実行すべてに、処理中でない記録がある
            self.assertNotIn(200, scenario.repo1)


class ParsePropertiesTest(unittest.TestCase):
    """P12：記録の名前と変数の行。P13：ビルドの道具の版。"""

    @SETTINGS
    @given(st.integers(1, 10**6), st.uuids(version=4))
    def test_record_names_round_trip(self, attempt, value):
        self.assertEqual(portal.parse_record(portal.record_name(attempt, str(value))), (attempt, str(value)))

    @SETTINGS
    @given(st.text(min_size=1, max_size=40).map(lambda text: 'central-' + text))
    def test_other_central_names_stop(self, name):
        assume(not portal.RECORD.fullmatch(name))
        with self.assertRaises(portal.Stop):
            portal.parse_record(name)

    @SETTINGS
    @given(st.dictionaries(st.tuples(st.integers(1, 10**11), st.integers(1, 50)),
                           st.one_of(st.just('none'), st.uuids(version=4).map(str)), max_size=4),
           st.sets(st.tuples(st.integers(0, 9), st.integers(0, 9), st.integers(0, 9)), max_size=3),
           st.lists(st.sampled_from(('', '  ', '\t')), max_size=3), st.randoms())
    def test_override_lines_round_trip(self, attempts, tags, blanks, random):
        tag_names = {f'v{a}.{b}.{c}' for a, b, c in tags}
        lines = [f'{run_id}/{attempt}={value}' for (run_id, attempt), value in attempts.items()]
        lines += [f'tag/{tag}=checked' for tag in tag_names] + blanks
        random.shuffle(lines)
        parsed = portal.parse_overrides('\n'.join(f'  {line} ' for line in lines))
        self.assertEqual(parsed.attempts, attempts)
        self.assertEqual(parsed.checked_tags, frozenset(tag_names))

    @SETTINGS
    @given(st.text(max_size=30).filter(lambda text: '\n' not in text and '\r' not in text and text.strip()))
    def test_unknown_lines_stop(self, line):
        stripped = line.strip()
        valid_attempt = portal.re.fullmatch(r'[1-9][0-9]*/[1-9][0-9]*=(none|' + portal.UUID.pattern + ')', stripped)
        valid_tag = portal.re.fullmatch(r'tag/v(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)=checked', stripped)
        try:
            portal.parse_overrides(line)
        except portal.Stop:
            self.assertFalse(valid_attempt or valid_tag)
            return
        self.assertTrue(valid_attempt or valid_tag)

    @SETTINGS
    @given(st.lists(st.sampled_from(('', '> Task :properties', '------', "Root project 'x'", 'versions: 1')),
                    max_size=4),
           st.lists(st.sampled_from(('5.3.0', '5.3.1', '5.3.0-SNAPSHOT')), max_size=2),
           st.sampled_from(('\n', '\r\n')), st.sampled_from(('', ' ', '\t')))
    def test_build_version(self, noise, versions, newline, padding):
        lines = noise + [f'{padding}version: {version}{padding}' for version in versions]
        output = newline.join(lines)
        try:
            value = portal.check_build_version(output, 'v5.3.0')
        except portal.Stop:
            self.assertFalse(len(versions) == 1 and versions[0] == '5.3.0')
            return
        self.assertEqual((value, versions), ('5.3.0', ['5.3.0']))


class WaitPropertiesTest(unittest.TestCase):
    """P7：k 回目に PUBLISHED を返すとき、上限 n なら k ≤ n で成功。FAILED はすぐ失敗。照会の失敗は数えて待つ。"""

    def run_wait(self, answers, attempts):
        calls = []

        def fake_http(method, url, headers=None, body=None, timeout=60):
            calls.append(url)
            if '/publisher/upload' in url:
                raise AssertionError('wait must not upload')
            return answers[len(calls) - 1] if len(calls) <= len(answers) else (200, b'{"deploymentState": "PENDING"}')

        original, auth = commands.http, commands.central_auth
        commands.http, commands.central_auth = fake_http, lambda: {'Authorization': 'Bearer x'}
        try:
            with contextlib.redirect_stdout(io.StringIO()):
                commands.wait(type('Args', (), {'id': 'x', 'attempts': attempts, 'interval': 0})(), sleep=lambda _: None)
            return 'published', len(calls)
        except portal.Stop as error:
            return ('failed' if 'FAILED' in str(error) else 'timeout'), len(calls)
        finally:
            commands.http, commands.central_auth = original, auth

    @SETTINGS
    @given(st.lists(st.sampled_from(('PENDING', 'VALIDATING', 'VALIDATED', 'PUBLISHING', 'error', 'garbage',
                                     'unreachable')), max_size=8),
           st.sampled_from(('PUBLISHED', 'FAILED', None)), st.integers(1, 8))
    def test_wait(self, before, last, attempts):
        def answer(item):
            if item == 'error':
                return 503, b''
            if item == 'garbage':
                return 200, b'<html>'
            if item == 'unreachable':
                return None, b''
            return 200, ('{"deploymentState": "%s", "errors": {"x": ["y"]}}' % item).encode()

        answers = [answer(item) for item in before] + ([answer(last)] if last else [])
        outcome, calls = self.run_wait(answers, attempts)
        k = len(before) + 1
        if last is None or k > attempts:
            self.assertEqual((outcome, calls), ('timeout', attempts))
        elif last == 'PUBLISHED':
            self.assertEqual((outcome, calls), ('published', k))
        else:
            self.assertEqual((outcome, calls), ('failed', k))

    @SETTINGS
    @given(st.lists(st.lists(st.sampled_from((200, 404, 503, None)), min_size=6, max_size=6), max_size=6),
           st.integers(1, 6))
    def test_confirm(self, rounds, attempts):
        calls = []

        def fake_http(method, url, headers=None, body=None, timeout=60):
            index = len(calls)
            calls.append(url)
            if '/publisher/' in url:
                raise AssertionError('confirm must not call the Portal')
            round_index, artifact_index = divmod(index, 6)
            code = rounds[round_index][artifact_index] if round_index < len(rounds) else 404
            return code, b''

        original = commands.http
        commands.http = fake_http
        try:
            with contextlib.redirect_stdout(io.StringIO()):
                commands.confirm(type('Args', (), {'version': '5.3.0', 'attempts': attempts, 'interval': 0})(),
                               sleep=lambda _: None)
            outcome = 'ok'
        except portal.Stop:
            outcome = 'timeout'
        finally:
            commands.http = original
        first = next((index + 1 for index, codes in enumerate(rounds) if all(code == 200 for code in codes)), None)
        if first is not None and first <= attempts:
            self.assertEqual((outcome, len(calls)), ('ok', first * 6))
        else:
            self.assertEqual((outcome, len(calls)), ('timeout', attempts * 6))


if __name__ == '__main__':
    unittest.main()
