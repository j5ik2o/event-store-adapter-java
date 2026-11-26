#! /usr/bin/env python3
"""リリースの関門。タグを打つ前（と、R2b からは Maven Central へ公開する前）に、同じ条件で確かめる。

event-store-adapter-js の .github/release-gate.py を写し、版のファイルを `version`（1行）に替えたもの。

latest-tag                   標準入力のタグ名から、最大の正式版のタグ (vX.Y.Z) を書く（無ければ何も書かない）。
set-version <X.Y.Z>          `version` の版を書き換える。版の後ろの改行の有無は元のまま残す。
check --mode tag|publish ... コミットが、版上げの PR を squash したものかを確かめる。
  tag     : 親に版のファイルが無い、版が変わっていない、正式版 (X.Y.Z) でない、
            タグが既に同じコミットにある、のときは何も書かない。通るならタグ名を書く。
  publish : --tag のタグが関門を通るなら、タグ名を書く。
  どちらも、通らないときは理由を ::error:: で書いて終了コード 1 を返す。
"""
import argparse
import json
import os
import re
import sys
from dataclasses import dataclass
from typing import Optional

NUMERIC = r'0|[1-9][0-9]*'
IDENTIFIER = r'0|[1-9][0-9]*|[0-9]*[A-Za-z-][0-9A-Za-z-]*'
VERSION = re.compile(
    rf'({NUMERIC})\.({NUMERIC})\.({NUMERIC})(?:-((?:{IDENTIFIER})(?:\.(?:{IDENTIFIER}))*))?')
VERSION_FILE = 'version'


class GateError(Exception):
    pass


@dataclass(frozen=True)
class Required:
    """公開先に先に出ている必要がある版と、それが出ているか。"""
    version: str
    published: bool


@dataclass(frozen=True)
class Candidate:
    """関門にかけるコミットと、その周りの事実。parent と current は版のファイルの中身（無ければ None）。"""
    repository: str
    sha: str
    parent: Optional[str]
    current: Optional[str]
    changed_files: tuple
    pulls: tuple
    tags: dict
    on_main: bool
    required: Optional[Required] = None
    parents: int = 1


def parse(version, release_only=True):
    """semver の優先順位で比べられるキーを返す。版でない、または release_only でプレリリースなら None。"""
    match = VERSION.fullmatch(version or '')
    if not match or (release_only and match.group(4)):
        return None
    core = tuple(int(part) for part in match.groups()[:3])
    if match.group(4) is None:
        return core + (1, ())
    identifiers = tuple((0, int(part)) if part.isdigit() else (1, part) for part in match.group(4).split('.'))
    return core + (0, identifiers)


def split_version_file(text):
    """版のファイルの中身を (版, 後ろの改行) に分ける。1行の版と、任意の改行1つのほかは受け付けない。

    Gradle は `File("./version").readText().trim()` で読む。ここで受け付けた中身は、
    trim しても同じ版になる（関門の方が緩いことは無い）。
    """
    body, newline = (text[:-1], '\n') if text.endswith('\n') else (text, '')
    if parse(body, release_only=False) is None:
        raise GateError(f'{VERSION_FILE} must hold a single version X.Y.Z[-prerelease] on one line, '
                        f'but holds {text!r}')
    return body, newline


def set_version(text, version):
    if parse(version) is None:
        raise GateError(f'{version!r} is not a release version (X.Y.Z)')
    return version + split_version_file(text)[1]


def stable_tags(names):
    return {name: parse(name[1:]) for name in names if name.startswith('v') and parse(name[1:])}


def latest_tag(names):
    candidates = [(key, name) for name, key in stable_tags(names).items()]
    return max(candidates)[1] if candidates else None


def remote_tags(ls_remote):
    """`git ls-remote --tags` の出力を {タグ名: コミット} にする（注釈つきタグは ^{} の先を使う）。"""
    tags = {}
    for line in ls_remote.splitlines():
        if not line.strip():
            continue
        sha, ref = line.split('\t', 1)
        name = ref.removeprefix('refs/tags/')
        if name.endswith('^{}'):
            tags[name[:-3]] = sha
        else:
            tags.setdefault(name, sha)
    return tags


def merged_from(pull, candidate, branch):
    head = pull['head']
    return (
        bool(pull['merged_at']) and pull['merge_commit_sha'] == candidate.sha
        and pull['base']['ref'] == 'main' and head['ref'] == branch
        and head['repo'] is not None and head['repo']['full_name'] == candidate.repository
    )


def approved_release(candidate, before, after, same_newline):
    version = parse(after)
    if version is None:
        raise GateError(f'{after} is not a release version (X.Y.Z)')
    if not candidate.on_main:
        raise GateError(f'{candidate.sha} is not reachable from main')
    if candidate.parents != 1:
        # merge_commit_sha は通常のマージでも PR に結び付くので、リポジトリのマージ方式の設定に頼らずここで落とす
        raise GateError(f'{candidate.sha} has {candidate.parents} parents; only a squash merge is released')
    if list(candidate.changed_files) != [VERSION_FILE]:
        raise GateError(f'the commit must change only {VERSION_FILE}, but changes {list(candidate.changed_files)}')
    if not same_newline:
        raise GateError(f'the commit must change only the version in {VERSION_FILE}, not the line ending')
    if version <= parse(before, release_only=False):
        raise GateError(f'{after} must be greater than the previous version {before}')
    required = candidate.required
    if required and not required.published and version > parse(required.version):
        raise GateError(f'{after} must wait until {required.version} is published')
    branch = f'release/v{after}'
    if not candidate.pulls:
        raise GateError(
            f'no pull request is associated with {candidate.sha} yet; '
            'if it is the merge of a version pull request, re-run the job')
    if len([pull for pull in candidate.pulls if merged_from(pull, candidate, branch)]) != 1:
        raise GateError(
            f'{candidate.sha} is not the squash merge of a pull request from {candidate.repository}:{branch} into main; '
            'restore the version with a pull request')
    tag = f'v{after}'
    newest = latest_tag([name for name in candidate.tags if name != tag])
    if newest and version <= parse(newest[1:]):
        raise GateError(f'{after} must be greater than the latest tag {newest}')
    return tag


def check_candidate(mode, candidate, requested_tag=None):
    if candidate.parent is None:
        if mode == 'tag':
            print(f'no release: the parent of {candidate.sha} has no {VERSION_FILE}', file=sys.stderr)
            return None
        raise GateError(f'the parent of {candidate.sha} has no {VERSION_FILE}')
    if candidate.current is None:
        raise GateError(f'{candidate.sha} has no {VERSION_FILE}')
    after, newline = split_version_file(candidate.current)
    if mode == 'tag' and parse(after) is None:
        print(f'no release: {after} is not a release version (X.Y.Z)', file=sys.stderr)
        return None
    before, parent_newline = split_version_file(candidate.parent)
    if mode == 'tag' and before == after:
        print(f'no release: version unchanged ({after})', file=sys.stderr)
        return None
    tag = approved_release(candidate, before, after, newline == parent_newline)
    remote = candidate.tags.get(tag)
    if mode == 'publish':
        if requested_tag != tag:
            raise GateError(f'tag {requested_tag} does not match the version {after}')
        if remote != candidate.sha:
            raise GateError(f'tag {tag} does not point at {candidate.sha} on the remote')
        return tag
    if remote is None:
        return tag
    if remote != candidate.sha:
        raise GateError(f'tag {tag} already exists on another commit {remote}')
    print(f'no release: tag {tag} already points at {candidate.sha}', file=sys.stderr)
    return None


def decode(data, name):
    try:
        return data.decode('utf-8')
    except UnicodeDecodeError as error:
        raise GateError(f'{name} is not UTF-8: {error}') from error


def read_version_file(path):
    """版のファイルを読む。無ければ None（release-gate-inputs.sh は、無いファイルを書かない）。"""
    if not os.path.exists(path):
        return None
    with open(path, 'rb') as file:
        return decode(file.read(), path)


def check(args):
    def read(path):
        with open(path, encoding='utf-8') as file:
            return file.read()

    required = None
    if args.required_version is not None:
        if parse(args.required_version) is None:
            raise GateError(f'--required-version {args.required_version!r} is not X.Y.Z')
        required = Required(args.required_version, args.required_published == 'true')
    candidate = Candidate(
        repository=args.repository, sha=args.sha,
        parent=read_version_file(args.parent_version), current=read_version_file(args.version_file),
        changed_files=tuple(line for line in read(args.changed_files).splitlines() if line),
        pulls=tuple(json.loads(read(args.pulls))), tags=remote_tags(read(args.remote_tags)),
        on_main=args.on_main == 'true', required=required, parents=int(read(args.parents)),
    )
    return check_candidate(args.mode, candidate, args.tag)


def write_version(path, version):
    with open(path, 'rb') as file:
        text = set_version(decode(file.read(), path), version)
    with open(path, 'wb') as file:
        file.write(text.encode('utf-8'))


def main(argv):
    parser = argparse.ArgumentParser(prog='release-gate.py')
    commands = parser.add_subparsers(dest='command', required=True)
    commands.add_parser('latest-tag')
    setter = commands.add_parser('set-version')
    setter.add_argument('version')
    setter.add_argument('--file', default=VERSION_FILE)
    gate = commands.add_parser('check')
    gate.add_argument('--mode', choices=('tag', 'publish'), required=True)
    gate.add_argument('--tag')
    for name in ('repository', 'sha', 'parents', 'parent-version', 'version-file', 'changed-files', 'pulls',
                 'remote-tags'):
        gate.add_argument('--' + name, required=True)
    gate.add_argument('--on-main', choices=('true', 'false'), required=True)
    gate.add_argument('--required-version')
    gate.add_argument('--required-published', choices=('true', 'false'))
    args = parser.parse_args(argv[1:])

    if args.command == 'latest-tag':
        tag = latest_tag([line.strip() for line in sys.stdin])
        if tag:
            print(tag)
        return 0
    try:
        if args.command == 'set-version':
            write_version(args.file, args.version)
            return 0
        if (args.required_version is None) != (args.required_published is None):
            raise GateError('--required-version and --required-published go together')
        tag = check(args)
    except GateError as error:
        print(f'::error::{error}', file=sys.stderr)
        return 1
    if tag:
        print(tag)
    return 0


if __name__ == '__main__':
    sys.exit(main(sys.argv))
