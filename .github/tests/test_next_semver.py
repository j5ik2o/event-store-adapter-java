"""next-semver.py（bump-version.yml の `Get the next version`）の試験。

入力は `release-gate.py latest-tag` の出力（`vX.Y.Z` か空）。先頭の v だけを外し、残り全体を SemVer として読む
（`.*v?(\\d+…)` は v10.2.0 を 0.2.0 と読んだ。event-store-adapter-scala#934 の指摘、#870）。
"""
from pathlib import Path
import subprocess
import sys
import unittest

import semver
from hypothesis import given, settings, strategies as st

SCRIPT = Path(__file__).resolve().parents[1] / 'next-semver.py'
LEVELS = ('patch', 'minor', 'major', 'snapshot')


def next_semver(text, level):
    return subprocess.run([sys.executable, '-W', 'error', str(SCRIPT), level], input=text, text=True,
                          capture_output=True)


def expected(version, level):
    current = semver.Version.parse(version)
    if level == 'snapshot':
        return f'{current.bump_patch()}-SNAPSHOT'
    return str({'major': current.bump_major, 'minor': current.bump_minor, 'patch': current.bump_patch}[level]())


class NextSemverTest(unittest.TestCase):
    def test_multi_digit_versions_keep_all_their_digits(self):
        for tag, level, want in (('v10.2.0', 'patch', '10.2.1'), ('v10.2.0', 'minor', '10.3.0'),
                                 ('v10.2.3', 'major', '11.0.0'), ('v123.45.678', 'patch', '123.45.679'),
                                 ('v5.2.0', 'minor', '5.3.0'), ('v5.2.0', 'snapshot', '5.2.1-SNAPSHOT')):
            with self.subTest(tag=tag, level=level):
                result = next_semver(tag + '\n', level)
                self.assertEqual((result.returncode, result.stdout, result.stderr), (0, want + '\n', ''))

    def test_prerelease_and_build_metadata_bump_as_semver_does(self):
        # latest-tag はプレリリースのタグを出さない。直接入れたときは semver の bump のまま（scala と同じ）
        for tag in ('v1.2.3-rc.1', 'v1.2.3+b'):
            with self.subTest(tag=tag):
                result = next_semver(tag + '\n', 'patch')
                self.assertEqual((result.returncode, result.stdout), (0, '1.2.4\n'))

    def test_a_line_that_is_not_a_version_fails(self):
        for line in ('v', 'v5.2', 'vx.y.z', 'foo-v1.2.3', 'release-5.2.0', 'v01.2.3', 'V1.2.3', 'v1.2.3 extra'):
            with self.subTest(line=line):
                result = next_semver(line + '\n', 'patch')
                self.assertNotEqual(result.returncode, 0)
                self.assertEqual(result.stdout, '')

    @settings(max_examples=60, deadline=None)
    @given(st.tuples(*(st.integers(0, 10 ** 6),) * 3), st.booleans(), st.sampled_from(LEVELS),
           st.sampled_from(('', ' ', '\r')))
    def test_every_release_version_bumps_as_semver(self, parts, with_v, level, trailing):
        version = '.'.join(map(str, parts))
        result = next_semver(('v' if with_v else '') + version + trailing + '\n', level)
        self.assertEqual((result.returncode, result.stdout, result.stderr), (0, expected(version, level) + '\n', ''))

    @settings(max_examples=40, deadline=None)
    @given(st.text(alphabet=' \t\r', max_size=4), st.sampled_from(LEVELS))
    def test_blank_lines_print_nothing_and_succeed(self, blank, level):
        result = next_semver(blank + '\n', level)
        self.assertEqual((result.returncode, result.stdout, result.stderr), (0, '', ''))

    @settings(max_examples=60, deadline=None)
    @given(st.text(alphabet='v0123456789.-+abcxyzV ', min_size=1, max_size=12).filter(lambda s: s.strip()),
           st.sampled_from(LEVELS))
    def test_a_non_blank_line_succeeds_only_when_it_is_a_version(self, line, level):
        text = line.strip()
        body = text[1:] if text.startswith('v') else text
        result = next_semver(line + '\n', level)
        if semver.Version.is_valid(body):
            self.assertEqual((result.returncode, result.stdout), (0, expected(body, level) + '\n'))
        else:
            self.assertNotEqual(result.returncode, 0)
            self.assertEqual(result.stdout, '')


if __name__ == '__main__':
    unittest.main()
