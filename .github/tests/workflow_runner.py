"""release.yml の release ジョブの手順を、書いた順に手元で流す小さな実行器（設計の追記 R2b-2 §5、R2b の追記 §4）。

写す GitHub Actions の動き：
- 手順の if:：状態の関数が無ければ success() && を付けて評価し、always() を含めば前の失敗に関わらず評価する。
  読める形は steps.<id>.outputs.<名前> == '<値>'、!= ''、always() && … だけ。知らない形は UnknownExpression。
- env: と with: の式：github.token、github.ref_name、github.run_attempt、vars.<名前>、secrets.<名前>、
  steps.<id>.outputs.<名前>、runner.temp だけ。知らない式は UnknownExpression。
- shell は bash --noprofile --norc -eo pipefail {0}。GITHUB_OUTPUT は手順ごとの新しいファイル。
- uses: の手順：checkout は前もって作った clone で代え、setup-* は飛ばし、upload-artifact は名前と path を記録する。
  知らない uses: は UnknownExpression。
"""
import os
import re
import subprocess
from dataclasses import dataclass, field
from pathlib import Path

from release_test_support import job, outputs
from release_workflow_canonical import release_steps

SKIPPED_ACTIONS = ('actions/checkout@', 'actions/setup-python@', 'actions/setup-java@', 'gradle/actions/setup-gradle@')
EXPRESSION = re.compile(r'\$\{\{ ([^}]*) \}\}')
OUTPUT = re.compile(r"steps\.([a-z_]+)\.outputs\.([a-z_]+)")


class UnknownExpression(AssertionError):
    """実行器が写していない式・形。テストを失敗させる。"""


@dataclass
class StepResult:
    id: str
    name: str
    outcome: str              # success、failure、skipped
    stdout: str = ''
    stderr: str = ''
    secrets: frozenset = frozenset()


@dataclass
class JobResult:
    steps: list
    outputs: dict
    artifacts: list = field(default_factory=list)   # (名前, path)

    def outcome(self, step_id):
        return next(step.outcome for step in self.steps if step.id == step_id)

    def ran(self):
        return [step.id or step.name for step in self.steps if step.outcome != 'skipped']

    @property
    def succeeded(self):
        return all(step.outcome != 'failure' for step in self.steps)


def with_block(text):
    if '\n        with:\n' not in text:
        return {}
    lines = []
    for line in text.split('\n        with:\n', 1)[1].split('\n'):
        if not line.startswith('          '):
            break
        lines.append(line)
    return dict(re.findall(r'^          ([a-z-]+): ([^\n]*)$', '\n'.join(lines), re.MULTILINE))


class Runner:
    def __init__(self, workflow, workspace, temp, context, environment, secrets, variables, step_env=None):
        self.workflow = workflow
        self.workspace = Path(workspace)
        self.temp = Path(temp)
        self.context = context            # github.* の値（token、ref_name、run_attempt）
        self.environment = environment    # ランナーが渡す環境変数（PATH、GITHUB_* など）
        self.secrets = secrets
        self.variables = variables
        self.step_env = step_env or {}    # 試験のための、手順ごとの環境変数の差し替え
        self.step_outputs = {}

    def expand(self, value, used):
        def one(match):
            expression = match.group(1)
            found = OUTPUT.fullmatch(expression)
            if found:
                return self.step_outputs.get(found.group(1), {}).get(found.group(2), '')
            if expression == 'runner.temp':
                return str(self.temp)
            if expression.startswith('github.') and expression[7:] in self.context:
                return self.context[expression[7:]]
            if expression.startswith('vars.'):
                return self.variables.get(expression[5:], '')
            if expression.startswith('secrets.'):
                used.add(expression[8:])
                return self.secrets[expression[8:]]
            raise UnknownExpression(f'expression not modelled: {expression}')
        if '${{' in EXPRESSION.sub('', value):
            # 区切りの中に } を含む式（format('{0}', …) など）は、正規表現に一致せずに残る。展開せずに拒否する
            raise UnknownExpression(f'expression not modelled: {value}')
        return EXPRESSION.sub(one, value)

    def condition(self, expression, failed):
        if expression is None:
            return not failed
        always = expression.startswith('always() && ')
        rest = expression[len('always() && '):] if always else expression
        found = re.fullmatch(r"steps\.([a-z_]+)\.outputs\.([a-z_]+) (==|!=) '([^']*)'", rest)
        if not found:
            raise UnknownExpression(f'if: not modelled: {expression}')
        value = self.step_outputs.get(found.group(1), {}).get(found.group(2), '')
        result = (value == found.group(4)) == (found.group(3) == '==')
        return result if always else (not failed and result)

    def uses(self, item, used):
        action = item['uses']
        if action.startswith(SKIPPED_ACTIONS):
            return None
        if action.startswith('actions/upload-artifact@'):
            settings = {key: self.expand(value, used) for key, value in with_block(item['text']).items()}
            path = Path(settings['path'])
            if settings.get('if-no-files-found') == 'error' and not path.is_file():
                return f'no file at {path}'
            self.artifacts.append((settings['name'], str(path)))
            return None
        raise UnknownExpression(f'uses: not modelled: {action}')

    def run(self):
        release_job = job(self.workflow, 'release')
        job_env = dict(re.findall(r'^      ([A-Z_]+): +([^\n]*)$', release_job.split('\n    env:\n', 1)[1].split('\n    steps:', 1)[0],
                                  re.MULTILINE)) if '\n    env:\n' in release_job else {}
        self.artifacts = []
        results, failed = [], False
        for number, item in enumerate(release_steps(self.workflow)):
            step_id = item['id'] or ''
            used = set()
            if not self.condition(item['if'], failed):
                results.append(StepResult(step_id, item['name'], 'skipped'))
                continue
            if item['uses']:
                error = self.uses(item, used)
                results.append(StepResult(step_id, item['name'], 'failure' if error else 'success', stderr=error or '',
                                          secrets=frozenset(used)))
                failed = failed or bool(error)
                continue
            env = {**self.environment, **job_env,
                   **{key: self.expand(value, used) for key, value in item['env'].items()}}
            output = self.temp / f'output-{number}'
            output.write_text('')
            script = self.temp / f'step-{number}.sh'
            script.write_text(item['run'] + '\n')
            env.update(GITHUB_OUTPUT=str(output), **self.step_env.get(step_id, {}))
            process = subprocess.run(['bash', '--noprofile', '--norc', '-eo', 'pipefail', str(script)],
                                     cwd=self.workspace, env=env, capture_output=True, text=True)
            self.step_outputs[step_id] = outputs(output)
            outcome = 'success' if process.returncode == 0 else 'failure'
            results.append(StepResult(step_id, item['name'], outcome, process.stdout, process.stderr, frozenset(used)))
            failed = failed or outcome == 'failure'
        job_outputs = {}
        block = release_job.split('\n    outputs:\n', 1)[1].split('\n    env:', 1)[0] if '\n    outputs:\n' in release_job else ''
        for key, value in re.findall(r'^      ([a-z_]+): ([^\n]*)$', block, re.MULTILINE):
            job_outputs[key] = self.expand(value, set())
        return JobResult(results, job_outputs, self.artifacts)


def default_environment(workspace, temp, repository, run_id, attempt, api_url, path, home):
    """ランナーが手順に渡す環境変数（試験で要るものだけ）。"""
    return {
        'PATH': path, 'HOME': str(home), 'CI': 'true', 'GITHUB_ACTIONS': 'true',
        'GITHUB_WORKSPACE': str(workspace), 'RUNNER_TEMP': str(temp), 'GITHUB_REPOSITORY': repository,
        'GITHUB_RUN_ID': str(run_id), 'GITHUB_RUN_ATTEMPT': str(attempt), 'GITHUB_API_URL': api_url,
        'GITHUB_STEP_SUMMARY': str(Path(temp) / 'summary.md'), 'LANG': os.environ.get('LANG', 'C.UTF-8'),
    }
