"""関門の性質を Hypothesis で確かめる（設計の P1〜P4・P6・P9・P10。除外は付けない）。

照合の相手は関門の関数ではなく、この中で別に書いた条件と semver のライブラリにする。
"""
import unicodedata
import unittest
from dataclasses import dataclass, replace

import semver
from hypothesis import example, given, settings
from hypothesis import strategies as st

from release_test_support import REPOSITORY, VERSION_FILE, load_gate

gate = load_gate()
SHA = 'a' * 40
OTHER_SHAS = ('b' * 40, 'c' * 40, 'd' * 40)
SETTINGS = settings(max_examples=500, deadline=None, database=None)


def accepted(version):
    """semver として正しく、build metadata が無い。"""
    return semver.Version.is_valid(version) and '+' not in version


def is_release(version):
    return accepted(version) and '-' not in version


def gradle_read(data):
    """Gradle（Kotlin）の `File("./version").readText().trim()`。

    readText は UTF-8 で読み、壊れたバイトは置き換える。BOM は残す。trim は Char.isWhitespace
    （Java の Character.isWhitespace か Character.isSpaceChar）の文字を両端から除く。
    """
    def whitespace(char):
        return char in '\t\n\x0b\x0c\r\x1c\x1d\x1e\x1f' or unicodedata.category(char) in ('Zs', 'Zl', 'Zp')

    text = data.decode('utf-8', errors='replace')
    start, end = 0, len(text)
    while start < end and whitespace(text[start]):
        start += 1
    while end > start and whitespace(text[end - 1]):
        end -= 1
    return text[start:end]


identifiers = st.one_of(
    st.integers(0, 30).map(str),
    st.sampled_from(['SNAPSHOT', 'rc', 'alpha', 'beta', '0a', 'a-b', '01', '']),
)
generated_versions = st.builds(
    lambda major, minor, patch, pre, build: f'{major}.{minor}.{patch}' + (pre and '-' + pre) + (build and '+' + build),
    st.integers(0, 6), st.integers(0, 6), st.integers(0, 6),
    st.one_of(st.just(''), st.lists(identifiers, min_size=1, max_size=3).map('.'.join)),
    st.sampled_from(['', '', '', 'build.1']),
)
versions = st.one_of(
    generated_versions,
    st.sampled_from(['05.2.0', '5.2', '5.2.0.0', 'v5.2.0', '５.２.０', '5.2.0-', '5.2.0-a..b', '']),
    st.text(alphabet='0123456789.-+aZ', max_size=12),
)
release_versions = st.builds(lambda *parts: '.'.join(map(str, parts)),
                             st.integers(0, 6), st.integers(0, 6), st.integers(0, 6))
file_suffixes = st.sampled_from(['\n', '\n', '', '\r\n', '\n\n', ' ', ' \n', '\t\n', ' \n', ' '])
file_prefixes = st.sampled_from(['', '', '', '﻿', ' ', '\n'])


def version_file(version_strategy):
    return st.builds(lambda prefix, version, suffix: prefix + version + suffix,
                     file_prefixes, version_strategy, file_suffixes)


@dataclass(frozen=True)
class Case:
    """生成した事実。entries は ls-remote に出すタグ：(名前, 指すコミット, 注釈つきか)。"""
    candidate: object
    entries: tuple

    @property
    def truth(self):
        return {name: sha for name, sha, _ in self.entries}


def ls_remote(entries):
    lines = []
    for index, (name, sha, annotated) in enumerate(entries):
        if annotated:
            lines.append(f'{index:040x}\trefs/tags/{name}')
            lines.append(f'{sha}\trefs/tags/{name}^{{}}')
        else:
            lines.append(f'{sha}\trefs/tags/{name}')
    return '\n'.join(lines) + '\n'


def make_case(parent, current, changed, pulls, entries, on_main, required=None, parents=1):
    candidate = gate.Candidate(
        repository=REPOSITORY, sha=SHA, parent=parent, current=current, changed_files=tuple(changed),
        pulls=tuple(pulls), tags=gate.remote_tags(ls_remote(entries)), on_main=on_main, required=required,
        parents=parents,
    )
    return Case(candidate, tuple(entries))


DEFAULT_WHOLE = object()


def pull(sha=SHA, ref='release/v5.3.0', repo=REPOSITORY, merged=True, base='main', whole=DEFAULT_WHOLE):
    """whole は release-gate-inputs.sh が書く全体の差分。既定は ref の版だけを替えるもの、None は持たない。"""
    item = {'number': 1, 'merged_at': '2026-11-25T00:00:00Z' if merged else None, 'merge_commit_sha': sha,
            'base': {'ref': base}, 'head': {'ref': ref, 'repo': None if repo is None else {'full_name': repo}}}
    if whole is DEFAULT_WHOLE:
        whole = {'files': [VERSION_FILE], 'before': '5.2.1-SNAPSHOT\n', 'after': ref.removeprefix('release/v') + '\n'}
    if whole is not None:
        item['whole_diff'] = whole
    return item


def whole_is_the_version(whole, after):
    """P10 の照合の相手：全体の差分のファイルが version だけで、前が1行の版（改行は1つまで）、後がその改行のまま after。"""
    if not isinstance(whole, dict) or whole.get('files') != [VERSION_FILE]:
        return False
    before_text, after_text = whole.get('before'), whole.get('after')
    if not isinstance(before_text, str) or not isinstance(after_text, str):
        return False
    newline = '\n' if before_text.endswith('\n') else ''
    body = before_text[:len(before_text) - len(newline)]
    return accepted(body) and '\n' not in body and body != after and after_text == after + newline


def whole_diffs(target):
    """target を版とする全体の差分の候補（正しいものと、崩したもの）。"""
    texts = st.one_of(st.none(), version_file(st.one_of(versions, st.just(target))),
                      st.sampled_from([target, target + '\n', '5.2.1-SNAPSHOT\n', '5.2.1-SNAPSHOT']))
    files = st.one_of(st.just([VERSION_FILE]), st.lists(st.sampled_from([VERSION_FILE, 'build.gradle.kts']), max_size=3))
    return st.one_of(
        st.builds(lambda before: {'files': [VERSION_FILE], 'before': before, 'after': target + '\n'},
                  st.sampled_from(['5.2.1-SNAPSHOT\n', '0.0.1\n', target + '-rc.1\n'])),
        st.builds(lambda f, before, after: {'files': f, 'before': before, 'after': after}, files, texts, texts),
        st.sampled_from([None, 'not a dict', {'files': VERSION_FILE}, {}]),
    )


@st.composite
def near_valid_cases(draw):
    """通る事実を作り、そこから 0〜3 か所を崩す（崩した1か所だけで拒否されるかを確かめるため）。"""
    major, minor, patch = draw(st.integers(1, 6)), draw(st.integers(0, 6)), draw(st.integers(0, 6))
    target = f'{major}.{minor}.{patch}'
    parent = draw(st.sampled_from([f'{target}-SNAPSHOT', f'{major - 1}.9.9', f'{target}-rc.1'])) + '\n'
    current = target + '\n'
    changed = [VERSION_FILE]
    pulls = [pull(ref=f'release/v{target}')]
    entries = [(f'v{major - 1}.0.0', 'b' * 40, draw(st.booleans())), ('v0.1.0', 'c' * 40, draw(st.booleans()))]
    on_main, required, parents = True, None, 1
    for change in draw(st.lists(st.sampled_from(range(18)), max_size=3)):
        if change == 0:
            on_main = False
        elif change == 17:
            pulls = [pull(ref=f'release/v{target}', whole=draw(whole_diffs(target)))]
        elif change == 16:
            parents = draw(st.sampled_from([0, 2, 3]))
        elif change == 1:
            changed = draw(st.permutations([VERSION_FILE, 'build.gradle.kts']))
        elif change == 2:
            current = target
        elif change == 3:
            pulls = pulls + [pull(ref=f'release/v{target}')]
        elif change == 4:
            pulls = [pull(ref=f'release/v{target}', repo=draw(st.sampled_from(['someone/r', None])))]
        elif change == 5:
            pulls = [pull(ref=draw(st.sampled_from([f'release/V{target}', 'feature/x', f'release/v{target}.1'])))]
        elif change == 6:
            pulls = [pull(ref=f'release/v{target}', merged=False)]
        elif change == 7:
            pulls = [pull(ref=f'release/v{target}', base='develop')]
        elif change == 8:
            pulls = [pull(sha='d' * 40, ref=f'release/v{target}')]
        elif change == 9:
            pulls = []
        elif change == 10:
            newer = draw(st.sampled_from([target, f'{major}.{minor}.{patch + 1}', f'{major + 1}.0.0']))
            entries = entries + [(f'v{newer}', draw(st.sampled_from(OTHER_SHAS)), draw(st.booleans()))]
        elif change == 11:
            entries = entries + [(f'v{target}', draw(st.sampled_from((SHA,) + OTHER_SHAS)), draw(st.booleans()))]
        elif change == 12:
            entries = entries + [(draw(st.sampled_from([f'V{major + 1}.0.0', f'v0{major + 1}.0.0',
                                                        f'v{major + 1}.0.0-rc.1'])), 'd' * 40, False)]
        elif change == 13:
            parent = draw(st.one_of(st.none(), version_file(st.sampled_from([target, f'{major + 1}.0.0', '5.x']))))
        elif change == 14:
            required = gate.Required(draw(st.sampled_from([f'{major - 1}.0.0', target, f'{major + 1}.0.0'])),
                                     draw(st.booleans()))
        else:
            current = draw(st.one_of(st.none(), version_file(st.just(target))))
    entries = list({name: (name, sha, annotated) for name, sha, annotated in entries}.values())
    return make_case(parent, current, changed, pulls, entries, on_main, required, parents)


@st.composite
def random_cases(draw):
    target = draw(st.one_of(release_versions, versions))
    current = draw(st.one_of(st.just(target + '\n'), version_file(st.just(target)), st.none()))
    parent = draw(st.one_of(version_file(st.one_of(versions, st.just(target))),
                            st.sampled_from(['5.2.1-SNAPSHOT\n', '5.2.0\n']), st.none()))
    changed = draw(st.one_of(st.just([VERSION_FILE]), st.lists(
        st.sampled_from([VERSION_FILE, 'build.gradle.kts', 'core/src/main/java/A.java']), max_size=3)))
    pulls = draw(st.lists(st.builds(
        pull,
        sha=st.sampled_from((SHA,) + OTHER_SHAS),
        ref=st.sampled_from([f'release/v{target}', 'release/v5.3.0', 'feature/x', f'release/V{target}']),
        repo=st.sampled_from([REPOSITORY, 'someone/r', None]),
        merged=st.booleans(), base=st.sampled_from(['main', 'main', 'develop']),
        whole=st.one_of(st.just(DEFAULT_WHOLE), whole_diffs(target))), max_size=3))
    names = st.one_of(release_versions.map('v'.__add__), versions.map('v'.__add__),
                      st.sampled_from(['V5.2.0', 'v05.2.0', 'v5.2.0', 'release', f'v{target}']))
    tags = draw(st.dictionaries(names, st.tuples(st.sampled_from((SHA,) + OTHER_SHAS), st.booleans()), max_size=4))
    entries = [(name, sha, annotated) for name, (sha, annotated) in tags.items()]
    required = draw(st.one_of(st.none(), st.builds(gate.Required, release_versions, st.booleans())))
    return make_case(parent, current, changed, pulls, entries, draw(st.booleans()), required,
                     draw(st.sampled_from([1, 1, 2])))


def cases():
    return st.one_of(near_valid_cases(), random_cases())


def outcome(mode, candidate, tag=None):
    try:
        return ('tag', gate.check_candidate(mode, candidate, tag))
    except gate.GateError:
        return ('error', None)


def without_tag(candidate, tag):
    return replace(candidate, tags={name: sha for name, sha in candidate.tags.items() if name != tag})


def greater(version, than):
    return semver.compare(version, than) > 0


def stable(name):
    return name.startswith('v') and is_release(name[1:])


GOOD = make_case('5.2.1-SNAPSHOT\n', '5.3.0\n', [VERSION_FILE], [pull()],
                 [('v5.2.0', 'c' * 40, True), ('v5.1.0', 'b' * 40, False)], True)
EXAMPLES = (
    GOOD,
    replace(GOOD, candidate=replace(GOOD.candidate, parent=None)),
    replace(GOOD, candidate=replace(GOOD.candidate, on_main=False)),
    make_case('5.2.1-SNAPSHOT\n', '5.3.0\n', [VERSION_FILE], [pull()],
              [('V5.4.0', 'c' * 40, False), ('v05.4.0', 'b' * 40, True), ('v5.2.0', 'b' * 40, False)], True),
    make_case('5.2.1-SNAPSHOT\n', '5.3.0\n', [VERSION_FILE], [pull()], [('v5.3.0', SHA, True)], True),
    make_case('5.2.1-SNAPSHOT\n', '5.3.0\n', [VERSION_FILE], [pull()], [('v5.3.0', 'b' * 40, False)], True),
    # 崩した1か所だけで拒否される形（P1 が、どの条件を外しても見つけるように）
    make_case('5.2.1-SNAPSHOT\n', '5.3.0\n', [VERSION_FILE], [pull()], [('v5.3.1', 'b' * 40, True)], True),
    make_case('5.2.1-SNAPSHOT\n', '5.3.0\n', [VERSION_FILE], [pull()], [], True, parents=2),
    make_case('5.2.1-SNAPSHOT\n', '5.3.0', [VERSION_FILE], [pull()], [], True),
    make_case('5.2.1-SNAPSHOT\n', '5.3.0\n', [VERSION_FILE], [pull(repo='someone/r')], [], True),
    make_case('5.2.1-SNAPSHOT\n', '5.3.0\n', [VERSION_FILE], [pull(repo=None)], [], True),
    make_case('5.2.1-SNAPSHOT\n', '5.3.0\n', [VERSION_FILE], [pull(), pull()], [], True),
    make_case('5.2.1-SNAPSHOT\n', '5.3.0\n', [VERSION_FILE, 'build.gradle.kts'], [pull()], [], True),
    make_case('5.3.1\n', '5.3.0\n', [VERSION_FILE], [pull()], [], True),
    make_case('5.2.1-SNAPSHOT\n', '5.3.0\n', [VERSION_FILE], [pull()], [], True, gate.Required('5.2.0', False)),
)


def with_examples(test):
    for case in EXAMPLES:
        test = example(case)(test)
    return test


class ReleaseGatePropertiesTest(unittest.TestCase):
    def test_p1_a_tag_is_returned_only_for_the_squash_of_a_version_pull_request(self):
        returned = []

        @SETTINGS
        @given(cases())
        @with_examples
        def prop(case):
            candidate = case.candidate
            self.assertEqual(candidate.tags, case.truth)
            result = outcome('tag', candidate)
            if result[0] == 'error' or result[1] is None:
                return
            returned.append(result[1])
            self.assertTrue(candidate.on_main)
            self.assertEqual(candidate.parents, 1)
            self.assertEqual(list(candidate.changed_files), [VERSION_FILE])
            self.assertIsNotNone(candidate.parent)
            self.assertIsNotNone(candidate.current)
            after = candidate.current[:-1] if candidate.current.endswith('\n') else candidate.current
            before = candidate.parent[:-1] if candidate.parent.endswith('\n') else candidate.parent
            self.assertEqual(candidate.current.endswith('\n'), candidate.parent.endswith('\n'))
            self.assertTrue(is_release(after), after)
            self.assertTrue(accepted(before), before)
            self.assertTrue(greater(after, before))
            self.assertEqual(result[1], 'v' + after)
            merges = [p for p in candidate.pulls
                      if p['merged_at'] and p['merge_commit_sha'] == SHA and p['base']['ref'] == 'main'
                      and p['head']['ref'] == 'release/v' + after and p['head']['repo'] is not None
                      and p['head']['repo']['full_name'] == REPOSITORY]
            self.assertEqual(len(merges), 1)
            self.assertTrue(whole_is_the_version(merges[0].get('whole_diff'), after), merges[0].get('whole_diff'))
            for name in case.truth:
                if stable(name) and name != result[1]:
                    self.assertTrue(greater(after, name[1:]), name)
            self.assertNotIn(result[1], case.truth)
            required = candidate.required
            if required and not required.published:
                self.assertFalse(greater(after, required.version))

        prop()
        self.assertGreater(len(returned), 0)

    def test_p2_publish_passes_only_where_tag_mode_returns_that_tag_and_the_remote_tag_points_at_the_commit(self):
        @SETTINGS
        @given(cases(), st.data())
        def prop(case, data):
            candidate = case.candidate
            requested = data.draw(st.one_of(
                st.just('v' + (candidate.current or '').rstrip('\n')), st.sampled_from(['v5.3.0', 'v5.3.1']),
                versions.map('v'.__add__)))
            publish = outcome('publish', candidate, requested)
            tag = outcome('tag', without_tag(candidate, requested))
            expected = tag == ('tag', requested) and case.truth.get(requested) == SHA
            self.assertEqual(publish == ('tag', requested), expected)
            self.assertIn(publish, (('tag', requested), ('error', None)))

        prop()

        @SETTINGS
        @given(cases())
        @with_examples
        def published_tag(case):
            candidate = case.candidate
            tag = outcome('tag', candidate)
            if tag[0] == 'tag' and tag[1]:
                approved = replace(candidate, tags={**without_tag(candidate, tag[1]).tags, tag[1]: SHA})
                self.assertEqual(outcome('publish', approved, tag[1]), tag)

        published_tag()

    def test_p3_the_order_matches_semver_and_build_metadata_is_rejected(self):
        @SETTINGS
        @given(versions, versions)
        @example('5.3.0', '5.3.0-SNAPSHOT')
        @example('1.0.0-alpha', '1.0.0-alpha.1')
        @example('1.0.0-2', '1.0.0-11')
        @example('1.0.0-11', '1.0.0-a')
        def prop(left, right):
            for version in (left, right):
                self.assertEqual(gate.parse(version, release_only=False) is not None, accepted(version), version)
                self.assertEqual(gate.parse(version) is not None, is_release(version), version)
                self.assertIsNone(gate.parse(version + '+build.1', release_only=False))
            if accepted(left) and accepted(right):
                ours = gate.parse(left, release_only=False), gate.parse(right, release_only=False)
                self.assertEqual((ours[0] > ours[1]) - (ours[0] < ours[1]), semver.compare(left, right))

        prop()

    def test_p4_the_version_file_is_read_like_gradle_and_rewritten_in_place(self):
        @SETTINGS
        @given(st.one_of(version_file(versions).map(lambda text: text.encode('utf-8')),
                         st.binary(max_size=12)),
               release_versions)
        @example(b'5.2.1-SNAPSHOT\n', '5.3.0')
        @example(b'\xef\xbb\xbf5.2.1\n', '5.3.0')
        @example(b'5.2.1\r\n', '5.3.0')
        @example(b'5.2.1', '5.3.0')
        def prop(data, new):
            try:
                text = gate.decode(data, VERSION_FILE)
                version, newline = gate.split_version_file(text)
            except gate.GateError:
                with self.assertRaises(gate.GateError):
                    gate.set_version(gate.decode(data, VERSION_FILE), new)
                return
            self.assertEqual(gradle_read(data), version)
            self.assertEqual(text, version + newline)
            rewritten = gate.set_version(text, new)
            self.assertEqual(rewritten, new + newline)
            self.assertEqual(rewritten[len(new):], text[len(version):])
            self.assertEqual(gate.split_version_file(rewritten), (new, newline))
            self.assertEqual(gradle_read(rewritten.encode('utf-8')), new)

        prop()

    def test_p6_an_existing_tag_is_a_no_op_on_the_same_commit_and_an_error_on_another(self):
        @SETTINGS
        @given(cases(), st.sampled_from(('absent', SHA) + OTHER_SHAS), st.booleans())
        @example(GOOD, SHA, True)
        @example(GOOD, 'b' * 40, False)
        def prop(case, remote, annotated):
            candidate = case.candidate
            files = [text if text is None or text.count('\n') > 1 else text.rstrip('\n')
                     for text in (candidate.parent, candidate.current)]
            if all(text is not None and accepted(text) for text in files) and is_release(files[1]) \
                    and case.truth.get('v' + files[1]) == SHA:
                self.assertEqual(outcome('tag', candidate), ('tag', None))
            base = outcome('tag', candidate)
            if base[0] != 'tag' or base[1] is None:
                return
            tag = base[1]
            if remote == 'absent':
                self.assertEqual(outcome('tag', without_tag(candidate, tag)), ('tag', tag))
                return
            entries = [entry for entry in case.entries if entry[0] != tag] + [(tag, remote, annotated)]
            again = replace(candidate, tags=gate.remote_tags(ls_remote(entries)))
            self.assertEqual(outcome('tag', again), ('tag', None) if remote == SHA else ('error', None))

        prop()

    def test_p11_the_next_version_must_be_greater_than_the_version_file(self):
        """P11（#870）：check-next が通る ⇔ 次の版が正式版で、ファイルの版より（semver の優先順位で）大きい。"""
        @SETTINGS
        @given(version_file(versions), st.one_of(release_versions, versions))
        @example('5.2.1-SNAPSHOT\n', '5.2.1')
        @example('5.2.1-SNAPSHOT\n', '5.2.0')
        @example('5.3.0\n', '5.3.0')
        def prop(text, version):
            body = text[:-1] if text.endswith('\n') else text
            ok = (is_release(version) and accepted(body) and '\n' not in body and greater(version, body))
            try:
                self.assertEqual(gate.check_next(text, version), version)
                self.assertTrue(ok, (text, version))
            except gate.GateError:
                self.assertFalse(ok, (text, version))

        prop()

    def test_p10_the_whole_pull_request_must_change_only_the_version(self):
        """P10（#870）：ほかの条件がすべて通るとき、通る ⇔ マージ元の PR の全体の差分が version の版だけを替える。"""
        @SETTINGS
        @given(whole_diffs('5.3.0'), st.sampled_from(['tag', 'publish']))
        @example({'files': [VERSION_FILE], 'before': '5.2.1-SNAPSHOT\n', 'after': '5.3.0\n'}, 'tag')
        @example({'files': [VERSION_FILE], 'before': '5.3.0\n', 'after': '5.3.0\n'}, 'tag')
        @example({'files': [VERSION_FILE], 'before': '5.2.1-SNAPSHOT\n', 'after': '5.3.0'}, 'tag')
        @example({'files': [VERSION_FILE, 'build.gradle.kts'], 'before': '5.2.1-SNAPSHOT\n', 'after': '5.3.0\n'}, 'tag')
        @example(None, 'publish')
        def prop(whole, mode):
            candidate = replace(GOOD.candidate, pulls=(pull(whole=whole),))
            if mode == 'publish':
                candidate = replace(candidate, tags={**candidate.tags, 'v5.3.0': SHA})
            expected = ('tag', 'v5.3.0') if whole_is_the_version(whole, '5.3.0') else ('error', None)
            self.assertEqual(outcome(mode, candidate, 'v5.3.0'), expected)

        prop()

    def test_p9_newer_versions_wait_until_the_required_version_is_published(self):
        @SETTINGS
        @given(cases(), release_versions, st.sampled_from(['tag', 'publish']))
        @example(GOOD, '5.2.0', 'tag')
        def prop(case, required, mode):
            candidate = replace(case.candidate, required=None)
            tag = 'v' + (candidate.current or '').rstrip('\n')
            unset = outcome(mode, candidate, tag)
            waiting = outcome(mode, replace(candidate, required=gate.Required(required, False)), tag)
            published = outcome(mode, replace(candidate, required=gate.Required(required, True)), tag)
            self.assertEqual(published, unset)
            if unset[0] == 'tag' and unset[1] and greater(unset[1][1:], required):
                self.assertEqual(waiting, ('error', None))
            elif unset[0] == 'tag' and unset[1]:
                self.assertEqual(waiting, unset)
            if waiting[0] == 'tag' and waiting[1]:
                self.assertFalse(greater(waiting[1][1:], required))

        prop()


if __name__ == '__main__':
    unittest.main()
