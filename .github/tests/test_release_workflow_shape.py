import os
import re
import subprocess
import tempfile
import unittest
from pathlib import Path

from release_test_support import BUMP, RELEASE, WORKFLOWS, job, run_blocks, run_body, step, steps

PAT = '${{ secrets.PERSONAL_ACCESS_TOKEN }}'
PUBLISH_SECRETS = ('SONATYPE_USERNAME', 'SONATYPE_PASSWORD', 'SIGNING_KEY', 'SIGNING_PASSWORD')
SECRET_NAME = re.compile(r'\b(?:SONATYPE|SIGNING)_[A-Z_]+')
# 公開の秘密の値を受け取ってよい手順（release.yml の手順の id）。R2b で bundle の手順に替えるときは、ここの名前だけを替える。
PUBLISH_STEPS = ('publish',)


GATE_COMMAND = '.github/release-gate.py check --mode publish'
SKIP_WORDS = ('always()', 'failure()', 'cancelled()', 'continue-on-error', '|| true', '|| :')


def release_has_gate(release=RELEASE):
    """release ジョブで、公開の手順の前に、飛ばせない関門の手順（id: release_gate）があるか。

    GitHub Actions では、失敗した手順のあとの手順は、always() などを付けない限り動かない。
    そのため、関門の手順に if も continue-on-error も無く、公開の手順にも失敗を越える条件が無ければ、
    関門に落ちたときに公開は動かない。
    """
    try:
        release_steps = steps(release, 'release')
    except ValueError:
        return False
    ids = [re.findall(r'\n        id: ([a-z_-]+)\n', text + '\n')[:1] for text in release_steps]
    ids = [found[0] if found else None for found in ids]
    if 'release_gate' not in ids or 'publish' not in ids or ids.index('release_gate') > ids.index('publish'):
        return False
    gate_step = release_steps[ids.index('release_gate')]
    commands = [line.strip() for line in run_body(gate_step + '\n', 'release_gate').splitlines()]
    if not any(line.startswith(('python3 ', 'TAG=$(python3 ')) and GATE_COMMAND in line
               for line in commands if not line.startswith('#')):
        return False
    guarded = release_steps[ids.index('release_gate'):ids.index('publish') + 1]
    return '        if:' not in gate_step and not any(word in text for text in guarded for word in SKIP_WORDS)


def env_block(step_text):
    match = re.search(r'\n        env:\n((?:          [^\n]*\n)+)', step_text + '\n')
    return match.group(1).splitlines() if match else []


class ReleaseWorkflowShapeTest(unittest.TestCase):
    def test_nothing_is_pushed_to_main(self):
        pushes = []
        for path in sorted(WORKFLOWS.glob('*.yml')):
            pushes += re.findall(r'git push [^\n]*', path.read_text())
        self.assertEqual(pushes, [
            'git push origin "HEAD:refs/heads/${BRANCH}"',
            'git push origin "refs/tags/${RELEASE_TAG}"; then',
        ])
        self.assertIn('BRANCH="release/${NEXT_TAG}"', BUMP)
        self.assertNotIn('origin main', BUMP + RELEASE)

    def test_no_snapshot_commit_and_no_github_release_in_the_bump(self):
        self.assertEqual(BUMP.count('git commit'), 1)
        for text in ('snapshot', 'actions/create-release', 'gh release'):
            self.assertFalse(text in BUMP.lower(), text)

    def test_triggers(self):
        self.assertNotIn('pull_request', BUMP + RELEASE)
        self.assertTrue(BUMP.startswith(
            'name: Bump-Version\non:\n  push:\n    branches:\n      - main\n'
            '    paths:\n      - version\n  workflow_dispatch:\n'))
        self.assertIn(
            "if: github.event_name == 'workflow_dispatch' && github.ref == 'refs/heads/main'",
            job(BUMP, 'bump-version'))
        self.assertIn(
            "if: github.event_name == 'push' && github.ref == 'refs/heads/main'"
            " && github.repository == 'j5ik2o/event-store-adapter-java'",
            job(BUMP, 'tag-release'))
        self.assertIn('      rehearsal:\n        description:', BUMP)
        self.assertIn('        type: boolean\n        default: false\n', BUMP[BUMP.index('      rehearsal:'):])

    def test_release_gate_detection_needs_a_gate_step_that_cannot_be_skipped(self):
        def release(gate_step, publish_if=''):
            return ('name: Release\njobs:\n  release:\n    runs-on: ubuntu-latest\n    steps:\n'
                    '      - uses: actions/checkout@v7\n' + gate_step +
                    '      - name: Publish\n        id: publish\n' + publish_if +
                    '        run: ./gradlew publish\n')
        gate = ('      - name: Gate\n        id: release_gate\n        run: |\n'
                '          python3 "${GITHUB_WORKSPACE}"/.github/release-gate.py check --mode publish --tag "$TAG"\n')
        self.assertTrue(release_has_gate(release(gate)))
        self.assertFalse(release_has_gate(RELEASE))
        commented = gate.replace('          python3', '          # python3')
        self.assertFalse(release_has_gate(release(commented)))
        self.assertFalse(release_has_gate(release(gate.replace('id: release_gate', 'id: other'))))
        self.assertFalse(release_has_gate(release(gate.replace('        run:', '        if: false\n        run:'))))
        self.assertFalse(release_has_gate(release(gate.replace('        run:', '        continue-on-error: true\n        run:'))))
        self.assertFalse(release_has_gate(release(gate.replace('"$TAG"\n', '"$TAG" || true\n'))))
        self.assertFalse(release_has_gate(release(gate, '        if: always()\n')))
        after = release('').replace('        run: ./gradlew publish\n', '        run: ./gradlew publish\n' + gate)
        self.assertFalse(release_has_gate(after))

    def test_tag_push_stays_off_while_the_release_workflow_has_no_gate(self):
        settings = re.findall(r'PUSH_RELEASE_TAG: [^\n]*', BUMP + RELEASE)
        self.assertEqual(len(settings), 1, settings)
        self.assertIn("\nenv:\n  PUSH_RELEASE_TAG: ", BUMP)
        if not release_has_gate():
            self.assertEqual(settings, ["PUSH_RELEASE_TAG: 'false'"])
        self.assertIn("if: steps.trigger.outputs.tag != '' && env.PUSH_RELEASE_TAG == 'true'",
                      step(BUMP, 'release_tag'))
        self.assertIn("if: steps.trigger.outputs.tag != '' && env.PUSH_RELEASE_TAG != 'true'",
                      step(BUMP, 'tag_summary'))

    def run_release_path(self, push_release_tag, rehearsal):
        with tempfile.TemporaryDirectory() as directory:
            return subprocess.run(
                ['bash', '-e', '-o', 'pipefail', '-c', run_body(BUMP, 'release_path')], cwd=directory,
                env={**os.environ, 'PUSH_RELEASE_TAG': push_release_tag, 'REHEARSAL': rehearsal},
                capture_output=True, text=True,
            )

    def test_a_bump_that_is_not_a_rehearsal_fails_before_the_branch_and_the_pull_request(self):
        bump_steps = steps(BUMP, 'bump-version')
        self.assertIn('id: release_path\n', bump_steps[0])
        self.assertNotIn('        if:', bump_steps[0])
        result = self.run_release_path('false', 'false')
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('::error::', result.stdout)
        self.assertIn('rehearsal', result.stdout)
        self.assertEqual(self.run_release_path('false', 'true').returncode, 0)
        self.assertEqual(self.run_release_path('true', 'false').returncode, 0)

    def open_pull_request(self, rehearsal):
        with tempfile.TemporaryDirectory() as directory:
            fake_bin = Path(directory) / 'bin'
            fake_bin.mkdir()
            args = Path(directory) / 'gh-args'
            (fake_bin / 'gh').write_text(
                '#!/bin/sh\nprintf \'%s\\n\' "$@" > "$FAKE_GH_ARGS"\necho https://github.com/o/r/pull/7\n')
            (fake_bin / 'gh').chmod(0o755)
            output = Path(directory) / 'outputs'
            output.write_text('')
            result = subprocess.run(
                ['bash', '-e', '-o', 'pipefail', '-c', run_body(BUMP, 'version_pr')], cwd=directory,
                env={**os.environ, 'PATH': f'{fake_bin}{os.pathsep}{os.environ["PATH"]}',
                     'FAKE_GH_ARGS': str(args), 'GITHUB_OUTPUT': str(output), 'GITHUB_REPOSITORY': 'o/r',
                     'NEXT_TAG': 'v5.3.0', 'BRANCH': 'release/v5.3.0', 'BODY': 'body.md',
                     'REHEARSAL': rehearsal},
                capture_output=True, text=True,
            )
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertEqual(output.read_text(), 'number=7\n')
            return args.read_text().splitlines()

    def test_only_a_rehearsal_opens_a_draft_pull_request(self):
        args = self.open_pull_request('true')
        self.assertIn('--draft', args)
        self.assertEqual(args[args.index('--title') + 1], 'version up to v5.3.0 (rehearsal, do not merge)')
        args = self.open_pull_request('false')
        self.assertNotIn('--draft', args)
        self.assertEqual(args[args.index('--title') + 1], 'version up to v5.3.0')

    def test_publish_secrets_are_only_in_the_env_of_the_publish_step(self):
        allowed = {line for step_id in PUBLISH_STEPS for line in env_block(step(RELEASE, step_id))}
        self.assertEqual(
            sorted(line for line in allowed if SECRET_NAME.search(line)),
            sorted(f'          {name}: ${{{{ secrets.{name} }}}}' for name in PUBLISH_SECRETS))
        for path in sorted(WORKFLOWS.glob('*.yml')):
            for number, line in enumerate(path.read_text().splitlines(), 1):
                if SECRET_NAME.search(line):
                    with self.subTest(file=path.name, line=number):
                        self.assertEqual(path.name, 'release.yml')
                        self.assertIn(line, allowed)
        for name in PUBLISH_SECRETS:
            self.assertEqual(RELEASE.count(name), 2, name)

    def test_pat_is_given_only_to_the_steps_that_push_or_open_the_pull_request(self):
        self.assertEqual(BUMP.count(PAT), 3)
        for step_id in ('version_commit', 'version_pr', 'release_tag'):
            with self.subTest(step=step_id):
                self.assertIn(PAT, step(BUMP, step_id))
        self.assertNotIn('persist-credentials: true', BUMP + RELEASE)
        self.assertNotIn('contents: write', BUMP)

    def test_no_expression_is_expanded_inside_scripts(self):
        for block in run_blocks(BUMP) + run_blocks(RELEASE):
            self.assertNotIn('${{', block)

    def test_tags_are_serialized(self):
        self.assertIn('    concurrency:\n      group: release-tag\n      cancel-in-progress: false\n',
                      job(BUMP, 'tag-release'))

    def test_tag_is_reported_or_pushed_only_after_the_gate(self):
        tag_steps = steps(BUMP, 'tag-release')
        ids = [re.search(r'id: ([a-z_]+)\n', text).group(1) for text in tag_steps if 'id: ' in text]
        self.assertEqual(ids, ['trigger', 'tag_summary', 'release_tag'])
        self.assertIn('--mode tag', run_body(BUMP, 'trigger'))
        self.assertIn('.github/release-gate-inputs.sh "$RELEASE_SHA" "$GATE"', run_body(BUMP, 'trigger'))
        self.assertNotIn(PAT, step(BUMP, 'trigger'))
        self.assertNotIn(PAT, step(BUMP, 'tag_summary'))

    def test_the_bump_writes_the_version_through_the_gate_script(self):
        self.assertIn('.github/release-gate.py set-version "$NEXT_VERSION"', run_body(BUMP, 'version_commit'))
        self.assertIn('git add version\n', run_body(BUMP, 'version_commit'))


if __name__ == '__main__':
    unittest.main()
