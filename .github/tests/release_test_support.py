"""リリースの経路のテストが共有する読み込みと補助関数。"""
import importlib.util
import re
from pathlib import Path


GITHUB = Path(__file__).resolve().parents[1]
WORKFLOWS = GITHUB / 'workflows'
GATE_SCRIPT = GITHUB / 'release-gate.py'
BUMP = (WORKFLOWS / 'bump-version.yml').read_text()
RELEASE = (WORKFLOWS / 'release.yml').read_text()
REPOSITORY = 'o/r'
VERSION_FILE = 'version'


def load_script(filename, name):
    spec = importlib.util.spec_from_file_location(name, GITHUB / filename)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def load_gate():
    return load_script(GATE_SCRIPT.name, 'release_gate')


def run_body(workflow, step_id):
    return re.search(
        r'id: ' + step_id + r'\n.*?        run: \|\n((?:          [^\n]*\n|\n)+)',
        workflow, re.DOTALL,
    ).group(1)


def run_blocks(workflow):
    blocks = re.findall(r'run: \|\n((?:          [^\n]*\n|\n)+)', workflow)
    return blocks + re.findall(r'run: ([^|\n][^\n]*)', workflow)


def job(workflow, name):
    start = workflow.index('\n  ' + name + ':\n')
    end = re.search(r'\n  [a-z-]+:\n', workflow[start + 1:])
    return workflow[start:start + 1 + end.start()] if end else workflow[start:]


def steps(workflow, job_name):
    """ジョブの手順を、書いた順に文字列で返す。"""
    body = job(workflow, job_name)
    body = body[body.index('\n    steps:\n'):]
    return ['\n      - ' + part for part in body.split('\n      - ')[1:]]


def step(workflow, step_id):
    start = workflow.rindex('\n      - ', 0, workflow.index('id: ' + step_id + '\n'))
    end = workflow.find('\n      - ', start + 1)
    end = len(workflow) if end == -1 else end
    following_job = re.search(r'\n  [a-z-]+:\n', workflow[start + 1:end])
    return workflow[start:start + 1 + following_job.start()] if following_job else workflow[start:end]


def outputs(path):
    return dict(line.split('=', 1) for line in path.read_text().splitlines() if line)


DEFAULT_WHOLE = object()


def whole_diff(after, before='5.2.1-SNAPSHOT\n', files=(VERSION_FILE,)):
    """release-gate-inputs.sh が git で求める、pull request の全体の差分。"""
    return {'files': list(files), 'before': before, 'after': after}


def pull(sha, ref='release/v5.3.0', repo=REPOSITORY, merged=True, base='main', head_sha=None, whole=DEFAULT_WHOLE,
         number=1):
    """関連する pull request の項目。whole の既定は、ref の版だけを替える全体の差分（None なら whole_diff を持たない）。"""
    item = {
        'number': number, 'merged_at': '2026-11-25T00:00:00Z' if merged else None,
        'merge_commit_sha': sha, 'base': {'ref': base},
        'head': {'ref': ref, 'repo': None if repo is None else {'full_name': repo}, 'sha': head_sha},
    }
    if whole is DEFAULT_WHOLE:
        whole = whole_diff(ref.removeprefix('release/v') + '\n')
    if whole is not None:
        item['whole_diff'] = whole
    return item


FAKE_GH = r'''#!/usr/bin/env python3
"""偽の gh。
api .../commits/<sha>/pulls : FAKE_GH_FAIL 回だけ失敗し、続く FAKE_GH_EMPTY 回は [] を返し、その後に $FAKE_PULLS を返す
                             （この呼び出しの回数を $FAKE_GH_CALLS に書く）。
api .../pulls/<番号>          : $FAKE_PULLS の同じ番号の項目。
api ... .../releases          : $FAKE_RELEASES（1行に1つのタグ。無ければ空）。FAKE_RELEASES_FAIL があれば失敗。
release create <タグ>          : $FAKE_RELEASES に足す（FAKE_RELEASES_LAG があれば、まだ一覧に出さない）。
release edit・pr create・workflow run：$FAKE_GH_LOG に残すだけ。"""
import json, os, re, sys
args = sys.argv[1:]
log = os.environ.get('FAKE_GH_LOG')
if log:
    with open(log, 'a') as file:
        file.write(json.dumps(args) + '\n')
path = next((arg for arg in args[1:] if arg.startswith('repos/')), '') if args[:1] == ['api'] else ''
if re.search(r'/commits/[^/]+/pulls$', path):
    calls_file = os.environ['FAKE_GH_CALLS']
    calls = int(open(calls_file).read().strip() or 0) if os.path.exists(calls_file) else 0
    calls += 1
    with open(calls_file, 'w') as file:
        file.write(f'{calls}\n')
    fail, empty = int(os.environ.get('FAKE_GH_FAIL', 0)), int(os.environ.get('FAKE_GH_EMPTY', 0))
    if calls <= fail:
        sys.exit('HTTP 502')
    print('[]' if calls <= fail + empty else open(os.environ['FAKE_PULLS']).read())
elif re.search(r'/pulls/[0-9]+$', path):
    number = int(path.rsplit('/', 1)[1])
    print(json.dumps(next(item for item in json.load(open(os.environ['FAKE_PULLS'])) if item['number'] == number)))
elif path.endswith('/releases'):
    if os.environ.get('FAKE_RELEASES_FAIL'):
        sys.exit('HTTP 502')
    releases = os.environ.get('FAKE_RELEASES', '')
    print(open(releases).read() if releases and os.path.exists(releases) else '', end='')
elif args[:2] == ['release', 'create']:
    if not os.environ.get('FAKE_RELEASES_LAG'):
        with open(os.environ['FAKE_RELEASES'], 'a') as file:
            file.write(args[2] + '\n')
elif args[:2] == ['pr', 'create']:
    print('https://github.com/o/r/pull/7')
elif args[:2] in (['release', 'edit'], ['workflow', 'run']):
    pass
else:
    sys.exit(f'fake gh: not modelled: {args}')
'''
