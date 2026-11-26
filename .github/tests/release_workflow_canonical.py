"""release.yml の正本との比べ方（形のテストの判定）と、正本の手順の読み方。

PUSH_RELEASE_TAG: 'true' を許すのは、release.yml が正本 fixtures/release-workflow.yml と行ごとに一字一句同じときだけ。
違ってよいのは、`uses: <action>@<版>` の行の版（Renovate が上げる）だけ。手順の並び、id ごとの if:、本文、
defaults・shell・env（BASH_ENV を含む）、コメント、3つのジョブのすべてが固定される（設計の追記 R2b-2 §4、部分レビュー F1）。
release.yml を変える PR は、正本も同じ PR で同じように変える。
"""
import re
from pathlib import Path

from release_test_support import steps

CANONICAL_PATH = Path(__file__).resolve().parent / 'fixtures' / 'release-workflow.yml'
CANONICAL = CANONICAL_PATH.read_text()
USES = re.compile(r'(\s*(?:- )?uses: [^@\s]+)@[A-Za-z0-9._-]+(?: # [A-Za-z0-9._-]+)?')


def release_has_gate(text):
    """text が正本と、uses: の版を除いて行ごとに一字一句同じか。"""
    lines, canonical = text.split('\n'), CANONICAL.split('\n')
    if len(lines) != len(canonical):
        return False
    for line, expected in zip(lines, canonical):
        if line == expected:
            continue
        found, wanted = USES.fullmatch(line), USES.fullmatch(expected)
        if not (found and wanted and found.group(1) == wanted.group(1)):
            return False
    return True


def parse_step(text):
    """手順の文字列から、名前、id、if:、uses:、env の名前と値、run の本文を読む。"""
    def field(key):
        match = re.search(r'\n(?:      - |        )' + key + r': ([^\n]*)', text)
        return match.group(1) if match else None
    block = text.split('\n        env:\n', 1)[1].split('\n        run:', 1)[0] if '\n        env:\n' in text else ''
    env = dict(re.findall(r'^          ([A-Z_]+): ([^\n]*)$', block, re.MULTILINE))
    run = re.search(r'\n        run: \|\n((?:          [^\n]*\n?|\n)+)', text + '\n')
    return {
        'name': field('name'), 'id': field('id'), 'if': field('if'), 'uses': field('uses'), 'env': env,
        'run': '\n'.join(line[10:] for line in run.group(1).rstrip('\n').split('\n')) if run else None,
        'text': text,
    }


def release_steps(workflow, job_name='release'):
    return [parse_step(text) for text in steps(workflow, job_name)]

