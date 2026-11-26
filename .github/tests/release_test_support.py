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


def load_gate():
    spec = importlib.util.spec_from_file_location('release_gate', GATE_SCRIPT)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


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


def pull(sha, ref='release/v5.3.0', repo=REPOSITORY, merged=True, base='main'):
    return {
        'number': 1, 'merged_at': '2026-11-25T00:00:00Z' if merged else None,
        'merge_commit_sha': sha, 'base': {'ref': base}, 'head': {'ref': ref, 'repo': {'full_name': repo}},
    }
