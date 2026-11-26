"""P14（設計の追記 R2b-2 §4）：正本に任意の変化を加えたとき、判定が「関門あり」になるのは、変化が uses: の版だけのとき。

比べる相手は、uses: の版を正規表現で同じ印に置き換えてから文字列を比べる、判定とは別に書いた関数。除外はない。
"""
import re
import unittest

from hypothesis import given, settings, strategies as st

from release_workflow_canonical import CANONICAL, release_has_gate

VERSION_CHARS = 'abcdefv0123456789.-_'
CHARS = VERSION_CHARS + ' \n\t\r#@|&:;$\'"{}()!AZ'
VERSION_LINE = re.compile(r'(?m)^( *(?:- )?uses: [^@\s]+)@[A-Za-z0-9._-]+(?: # [A-Za-z0-9._-]+)?$')
USES_LINES = [index for index, line in enumerate(CANONICAL.split('\n')) if VERSION_LINE.fullmatch(line)]


def same_but_versions(text):
    """参照：uses: の版を印に置き換えると、正本と同じ文字列になるか。"""
    return VERSION_LINE.sub(r'\1@V', text) == VERSION_LINE.sub(r'\1@V', CANONICAL)


KINDS = ['insert', 'delete', 'replace', 'line insert', 'line delete', 'line copy', 'line swap', 'version']


@st.composite
def edit(draw, kinds=tuple(KINDS)):
    kind = draw(st.sampled_from(kinds))
    return kind, draw(st.integers(0, 10 ** 6)), draw(st.integers(0, 10 ** 6)), draw(st.text(CHARS, min_size=1, max_size=3)), \
        draw(st.text(VERSION_CHARS.replace('-', ''), min_size=1, max_size=12)), draw(st.booleans())


def apply(text, change):
    kind, first, second, chars, version, comment = change
    lines = text.split('\n')
    if kind == 'insert':
        at = first % (len(text) + 1)
        return text[:at] + chars + text[at:]
    if kind in ('delete', 'replace'):
        if not text:
            return text
        at = first % len(text)
        return text[:at] + (chars if kind == 'replace' else '') + text[at + 1:]
    at, other = first % len(lines), second % len(lines)
    if kind == 'line insert':
        lines.insert(at, chars)
    elif kind == 'line delete':
        del lines[at]
    elif kind == 'line copy':
        lines.insert(other, lines[at])
    elif kind == 'line swap':
        lines[at], lines[other] = lines[other], lines[at]
    else:
        index = USES_LINES[first % len(USES_LINES)]
        if index < len(lines) and '@' in lines[index]:
            lines[index] = lines[index].split('@', 1)[0] + '@' + version + (f' # {version}' if comment else '')
    return '\n'.join(lines)


class GateJudgeProperties(unittest.TestCase):
    def test_every_action_line_of_the_canonical_workflow_has_a_version(self):
        self.assertEqual(len(USES_LINES), len([line for line in CANONICAL.split('\n')
                                               if 'uses: ' in line and not line.lstrip().startswith('#')]))

    @settings(max_examples=600, deadline=None)
    @given(st.lists(edit(), min_size=1, max_size=3))
    def test_only_version_changes_keep_the_gate(self, changes):
        text = CANONICAL
        for change in changes:
            text = apply(text, change)
        self.assertEqual(release_has_gate(text), same_but_versions(text))

    @settings(max_examples=200, deadline=None)
    @given(st.lists(edit(kinds=('version',)), min_size=1, max_size=3))
    def test_version_changes_alone_keep_the_gate(self, changes):
        text = CANONICAL
        for change in changes:
            text = apply(text, change)
        self.assertTrue(release_has_gate(text))


if __name__ == '__main__':
    unittest.main()
