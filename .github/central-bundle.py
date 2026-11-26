#! /usr/bin/env python3
"""Maven Central に上げる bundle（zip）を、Gradle の publishToMavenLocal の出力から作る。

build  --repository <dir> --version <X.Y.Z> --output <zip>
    <dir> の io/github/j5ik2o/<成果物>/<版>/ に、成果物ごとにちょうど 10 ファイル（5つと、その .asc）があることを
    確かめ、.asc 以外の5ファイルに .md5・.sha1・.sha256・.sha512 を作って zip にする。成果物ごとに 30、合わせて 180 項目。
    repo1 の 5.2.0 の並びと同じ。足りない・余るファイルや成果物があれば失敗する。
verify --zip <zip> --version <X.Y.Z>
    zip の中身が、build が作る形と同じかを確かめる（CI で使う）。
"""
import argparse
import hashlib
import sys
import zipfile
from pathlib import Path, PurePosixPath

GROUP_PATH = 'io/github/j5ik2o'
# settings.gradle.kts の include と、根の publication（.github/tests/test_central_bundle.py が照らし合わせる）
ARTIFACTS = (
    'event-store-adapter-java',
    'event-store-adapter-java-core',
    'event-store-adapter-java-dynamodb',
    'event-store-adapter-java-spanner',
    'event-store-adapter-java-bigtable',
    'event-store-adapter-java-sqlite',
)
SUFFIXES = ('.jar', '-sources.jar', '-javadoc.jar', '.pom', '.module')
CHECKSUMS = (('md5', hashlib.md5), ('sha1', hashlib.sha1), ('sha256', hashlib.sha256), ('sha512', hashlib.sha512))
# zip の項目の時刻を固定する（同じ入力から同じ zip を作る）
ZIP_TIME = (1980, 1, 1, 0, 0, 0)


class BundleError(Exception):
    pass


def published_files(artifact, version):
    """成果物の <版>/ に Gradle が出すファイルの名前（.asc を含む10個）。"""
    names = [f'{artifact}-{version}{suffix}' for suffix in SUFFIXES]
    return names + [name + '.asc' for name in names]


def bundle_entries(artifact, version, read):
    """成果物の bundle の項目 {zip の中のパス: 中身}。read(name) は <版>/ のファイルの中身を返す。"""
    entries = {}
    directory = f'{GROUP_PATH}/{artifact}/{version}'
    for name in published_files(artifact, version):
        data = read(name)
        entries[f'{directory}/{name}'] = data
        if not name.endswith('.asc'):
            for extension, algorithm in CHECKSUMS:
                entries[f'{directory}/{name}.{extension}'] = algorithm(data).hexdigest().encode('ascii')
    return entries


def expected_count():
    return len(ARTIFACTS) * len(SUFFIXES) * (2 + len(CHECKSUMS))


def collect(repository, version):
    """作業用のディレクトリを確かめて、bundle の項目を返す。"""
    root = Path(repository) / GROUP_PATH
    if not root.is_dir():
        raise BundleError(f'{root} does not exist; did publishToMavenLocal run with -Dmaven.repo.local?')
    found = sorted(path.name for path in root.iterdir())
    if found != sorted(ARTIFACTS):
        raise BundleError(f'artifacts under {root} are {found}, expected {sorted(ARTIFACTS)}')
    entries = {}
    for artifact in ARTIFACTS:
        artifact_dir = root / artifact
        others = sorted(path.name for path in artifact_dir.iterdir() if path.name != 'maven-metadata-local.xml')
        if others != [version]:
            raise BundleError(f'{artifact_dir} holds {others}, expected only the version directory {version}')
        version_dir = artifact_dir / version
        names = sorted(path.name for path in version_dir.iterdir())
        expected = sorted(published_files(artifact, version))
        if names != expected:
            missing = sorted(set(expected) - set(names))
            extra = sorted(set(names) - set(expected))
            raise BundleError(f'{version_dir}: missing {missing}, unexpected {extra}')
        for name in names:
            if not (version_dir / name).is_file():
                raise BundleError(f'{version_dir / name} is not a regular file')

        def read(name, version_dir=version_dir):
            return (version_dir / name).read_bytes()

        entries.update(bundle_entries(artifact, version, read))
    return entries


def write_zip(entries, output):
    with zipfile.ZipFile(output, 'w', compression=zipfile.ZIP_DEFLATED) as bundle:
        for path in sorted(entries):
            info = zipfile.ZipInfo(path, date_time=ZIP_TIME)
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = 0o644 << 16
            bundle.writestr(info, entries[path])


def verify_zip(path, version):
    """zip の中身が build の作る形と同じかを確かめ、項目の数を返す。"""
    with zipfile.ZipFile(path) as bundle:
        names = bundle.namelist()
        if len(names) != len(set(names)):
            raise BundleError('the bundle has duplicate entries')
        contents = {name: bundle.read(name) for name in names}
    expected = {}
    for artifact in ARTIFACTS:
        directory = f'{GROUP_PATH}/{artifact}/{version}'
        prefix = directory + '/'

        def read(name, prefix=prefix):
            key = prefix + name
            if key not in contents:
                raise BundleError(f'the bundle has no {key}')
            return contents[key]

        expected.update(bundle_entries(artifact, version, read))
    if set(contents) != set(expected):
        raise BundleError(f'unexpected entries {sorted(set(contents) - set(expected))}')
    for name, data in expected.items():
        if contents[name] != data:
            raise BundleError(f'{name} does not hold the expected checksum')
    for name in names:
        if PurePosixPath(name).is_absolute() or '..' in PurePosixPath(name).parts:
            raise BundleError(f'{name} is not a relative path')
    return len(names)


def main(argv):
    parser = argparse.ArgumentParser(prog='central-bundle.py')
    commands = parser.add_subparsers(dest='command', required=True)
    build = commands.add_parser('build')
    build.add_argument('--repository', required=True)
    build.add_argument('--version', required=True)
    build.add_argument('--output', required=True)
    check = commands.add_parser('verify')
    check.add_argument('--zip', required=True)
    check.add_argument('--version', required=True)
    args = parser.parse_args(argv[1:])
    try:
        if args.command == 'build':
            entries = collect(args.repository, args.version)
            write_zip(entries, args.output)
            count = len(entries)
        else:
            count = verify_zip(args.zip, args.version)
        if count != expected_count():
            raise BundleError(f'the bundle has {count} entries, expected {expected_count()}')
    except (BundleError, OSError, zipfile.BadZipFile) as error:
        print(f'::error::{error}', file=sys.stderr)
        return 1
    print(f'bundle for {args.version}: {count} files ({len(ARTIFACTS)} artifacts)')
    return 0


if __name__ == '__main__':
    sys.exit(main(sys.argv))
