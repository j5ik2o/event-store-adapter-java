"""central-bundle.py の例と性質（設計の追記の P11。除外は付けない）。"""
import hashlib
import re
import shutil
import subprocess
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path

from hypothesis import given, settings
from hypothesis import strategies as st

from release_test_support import GITHUB, load_script

central_bundle = load_script('central-bundle.py', 'central_bundle')
ROOT = GITHUB.parent
VERSION = '5.3.0'
SETTINGS = settings(max_examples=150, deadline=None, database=None)


def write_tree(root, version=VERSION, artifacts=central_bundle.ARTIFACTS, drop=(), add=(), metadata=True):
    """publishToMavenLocal が出す形の木を書く。drop は書かない名前、add は足す (成果物, 名前)。"""
    base = Path(root) / central_bundle.GROUP_PATH
    for artifact in artifacts:
        directory = base / artifact / version
        directory.mkdir(parents=True, exist_ok=True)
        if metadata:
            (base / artifact / 'maven-metadata-local.xml').write_text('<metadata/>')
        for name in central_bundle.published_files(artifact, version):
            if name not in drop:
                (directory / name).write_bytes(f'{artifact}/{name}'.encode())
    for artifact, name in add:
        directory = base / artifact / version
        directory.mkdir(parents=True, exist_ok=True)
        (directory / name).write_bytes(b'extra')


class CentralBundleTest(unittest.TestCase):
    def setUp(self):
        self.directory = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, self.directory)

    def run_script(self, *args):
        return subprocess.run([sys.executable, str(GITHUB / 'central-bundle.py'), *args],
                              capture_output=True, text=True)

    def test_the_artifact_list_matches_the_gradle_build(self):
        settings_text = (ROOT / 'settings.gradle.kts').read_text()
        modules = re.search(r'include\(([^)]*)\)', settings_text).group(1)
        names = ['event-store-adapter-java'] + [
            f'event-store-adapter-java-{name}' for name in re.findall(r'"([a-z]+)"', modules)]
        self.assertEqual(sorted(central_bundle.ARTIFACTS), sorted(names))
        build = (ROOT / 'build.gradle.kts').read_text()
        self.assertIn('artifactId = "event-store-adapter-java"', build)
        self.assertIn('val artifactName = "event-store-adapter-java-$moduleName"', build)
        self.assertIn('group = "io.github.j5ik2o"', build)

    def test_a_complete_tree_makes_180_entries_like_repo1(self):
        write_tree(self.directory / 'm2')
        output = self.directory / 'bundle.zip'
        result = self.run_script('build', '--repository', str(self.directory / 'm2'), '--version', VERSION,
                                 '--output', str(output))
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn('180 files', result.stdout)
        with zipfile.ZipFile(output) as bundle:
            names = bundle.namelist()
            core = f'io/github/j5ik2o/event-store-adapter-java-core/{VERSION}/event-store-adapter-java-core-{VERSION}'
            self.assertEqual(bundle.read(core + '.pom.sha1').decode(),
                             hashlib.sha1(f'event-store-adapter-java-core/event-store-adapter-java-core-{VERSION}.pom'
                                          .encode()).hexdigest())
        self.assertEqual(len(names), 180)
        # repo1 の 5.2.0 と同じ並び：成果物ごとに 30。.asc に checksum は無い
        spanner = sorted(name.rsplit('/', 1)[1] for name in names if '/event-store-adapter-java-spanner/' in name)
        self.assertEqual(len(spanner), 30)
        self.assertFalse([name for name in names if re.search(r'\.asc\.(md5|sha1|sha256|sha512)$', name)])
        self.assertFalse([name for name in names if 'maven-metadata' in name])
        verified = self.run_script('verify', '--zip', str(output), '--version', VERSION)
        self.assertEqual(verified.returncode, 0, verified.stderr)

    def test_the_bundle_is_the_same_for_the_same_input(self):
        write_tree(self.directory / 'm2')
        outputs = []
        for name in ('a.zip', 'b.zip'):
            self.assertEqual(self.run_script('build', '--repository', str(self.directory / 'm2'), '--version',
                                             VERSION, '--output', str(self.directory / name)).returncode, 0)
            outputs.append((self.directory / name).read_bytes())
        self.assertEqual(outputs[0], outputs[1])

    def test_broken_trees_are_refused(self):
        cases = {
            'missing signature': dict(drop=(f'event-store-adapter-java-core-{VERSION}.pom.asc',)),
            'missing module': dict(drop=(f'event-store-adapter-java-core-{VERSION}.module',)),
            'extra file': dict(add=(('event-store-adapter-java-core', 'notes.txt'),)),
            'missing artifact': dict(artifacts=central_bundle.ARTIFACTS[:-1]),
            'extra artifact': dict(artifacts=central_bundle.ARTIFACTS + ('event-store-adapter-java-extra',)),
        }
        for name, options in cases.items():
            with self.subTest(name):
                root = self.directory / name.replace(' ', '-')
                write_tree(root, **options)
                result = self.run_script('build', '--repository', str(root), '--version', VERSION,
                                         '--output', str(root / 'bundle.zip'))
                self.assertEqual(result.returncode, 1)
                self.assertIn('::error::', result.stderr)
                self.assertFalse((root / 'bundle.zip').exists())

    def test_another_version_directory_is_refused(self):
        write_tree(self.directory / 'm2')
        write_tree(self.directory / 'm2', version='5.2.0', artifacts=('event-store-adapter-java-core',))
        result = self.run_script('build', '--repository', str(self.directory / 'm2'), '--version', VERSION,
                                 '--output', str(self.directory / 'bundle.zip'))
        self.assertEqual(result.returncode, 1)
        self.assertIn('expected only the version directory', result.stderr)

    def test_a_tampered_zip_is_refused(self):
        write_tree(self.directory / 'm2')
        output = self.directory / 'bundle.zip'
        self.run_script('build', '--repository', str(self.directory / 'm2'), '--version', VERSION,
                        '--output', str(output))
        tampered = self.directory / 'tampered.zip'
        with zipfile.ZipFile(output) as source, zipfile.ZipFile(tampered, 'w') as target:
            for name in source.namelist():
                data = source.read(name)
                target.writestr(name, b'0' * 40 if name.endswith('.jar.sha1') else data)
        result = self.run_script('verify', '--zip', str(tampered), '--version', VERSION)
        self.assertEqual(result.returncode, 1)
        self.assertIn('checksum', result.stderr)


# ファイル名に使える文字（macOS の APFS は、未割り当ての文字を名前に使えない。BMP の文字・数字・記号に絞る）
NAME = st.text(alphabet=st.characters(categories=('L', 'N', 'P', 'S'), max_codepoint=0xFFFF, exclude_characters='/'),
               min_size=1, max_size=12).filter(lambda name: name not in ('.', '..'))


class CentralBundlePropertiesTest(unittest.TestCase):
    """P11：任意の木について、成功するのは6つの成果物の <版>/ がちょうど 10 ファイルのときだけ。"""

    @SETTINGS
    @given(
        drop=st.sets(st.sampled_from([(artifact, name) for artifact in central_bundle.ARTIFACTS
                                      for name in central_bundle.published_files(artifact, VERSION)]), max_size=2),
        extra=st.lists(st.tuples(st.sampled_from(central_bundle.ARTIFACTS), NAME), max_size=2),
        missing_artifacts=st.sets(st.sampled_from(central_bundle.ARTIFACTS), max_size=1),
        extra_artifacts=st.lists(NAME.map(lambda name: 'x' + name), max_size=1),
        other_version=st.booleans(),
        empty_files=st.booleans(),
        metadata=st.booleans(),
    )
    def test_success_exactly_when_the_tree_is_complete(self, drop, extra, missing_artifacts, extra_artifacts,
                                                       other_version, empty_files, metadata):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory) / 'm2'
            base = root / central_bundle.GROUP_PATH
            artifacts = [artifact for artifact in central_bundle.ARTIFACTS if artifact not in missing_artifacts]
            contents = {}
            for artifact in artifacts + extra_artifacts:
                version_dir = base / artifact / VERSION
                version_dir.mkdir(parents=True, exist_ok=True)
                if metadata:
                    (base / artifact / 'maven-metadata-local.xml').write_text('<metadata/>')
                names = central_bundle.published_files(artifact, VERSION) if artifact in artifacts else ['a.jar']
                for name in names:
                    if (artifact, name) in drop:
                        continue
                    data = b'' if empty_files else f'{artifact}:{name}'.encode()
                    (version_dir / name).write_bytes(data)
                    contents[f'{central_bundle.GROUP_PATH}/{artifact}/{VERSION}/{name}'] = data
            added = set()
            for artifact, name in extra:
                if artifact in artifacts and name not in central_bundle.published_files(artifact, VERSION):
                    (base / artifact / VERSION / name).write_bytes(b'extra')
                    added.add((artifact, name))
            if other_version and artifacts:
                (base / artifacts[0] / '5.2.0').mkdir()
            complete = (not drop and not added and not missing_artifacts and not extra_artifacts
                        and not (other_version and artifacts))
            output = Path(directory) / 'bundle.zip'
            try:
                entries = central_bundle.collect(root, VERSION)
                central_bundle.write_zip(entries, output)
            except central_bundle.BundleError:
                self.assertFalse(complete)
                return
            self.assertTrue(complete)
            with zipfile.ZipFile(output) as bundle:
                names = set(bundle.namelist())
                self.assertEqual(len(names), central_bundle.expected_count())
                for path, data in contents.items():
                    self.assertEqual(bundle.read(path), data)
                    if path.endswith('.asc'):
                        self.assertFalse({path + '.md5', path + '.sha1'} & names)
                        continue
                    for extension, algorithm in central_bundle.CHECKSUMS:
                        self.assertEqual(bundle.read(f'{path}.{extension}'), algorithm(data).hexdigest().encode())
                expected = set(contents) | {f'{path}.{extension}' for path in contents if not path.endswith('.asc')
                                            for extension, _ in central_bundle.CHECKSUMS}
                self.assertEqual(names, expected)
                self.assertTrue(all(name.startswith(f'{central_bundle.GROUP_PATH}/') for name in names))
            self.assertEqual(central_bundle.verify_zip(output, VERSION), central_bundle.expected_count())


if __name__ == '__main__':
    unittest.main()
