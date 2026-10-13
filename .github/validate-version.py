#!/usr/bin/env python3
"""Validate a requested release version against existing tags.

Usage: git tag -l 'v*' | sort -V | validate-version.py VERSION
Prints VERSION and exits 0 when it is acceptable; otherwise prints the reason
to stderr and exits 1 without writing anything to stdout.
"""
import re
import sys

VERSION_PATTERN = re.compile(r'[0-9]+\.[0-9]+\.[0-9]+')
TAG_PATTERN = re.compile(r'v([0-9]+)\.([0-9]+)\.([0-9]+)')


def parse(version):
    return tuple(int(part) for part in version.split('.'))


def validate_version(version, tags):
    """`tags` are existing `v*` tags already ordered by `sort -V`."""
    if not VERSION_PATTERN.fullmatch(version):
        raise ValueError(f'version must be X.Y.Z (digits only): {version!r}')
    tags = [tag for tag in tags if tag]
    if f'v{version}' in tags:
        raise ValueError(f'tag v{version} already exists')
    if tags:
        latest = TAG_PATTERN.fullmatch(tags[-1])
        if latest is None:
            raise ValueError(f'latest tag is not comparable: {tags[-1]!r}')
        if parse(version) <= tuple(int(part) for part in latest.groups()):
            raise ValueError(f'version {version} must be greater than the latest tag {tags[-1]}')
    return version


def main(argv):
    if len(argv) != 2:
        print('usage: validate-version.py VERSION < sorted-tags', file=sys.stderr)
        return 1
    try:
        version = validate_version(argv[1], sys.stdin.read().splitlines())
    except ValueError as error:
        print(f'error: {error}', file=sys.stderr)
        return 1
    print(version)
    return 0


if __name__ == '__main__':
    sys.exit(main(sys.argv))
