#! /usr/bin/env python3
"""Read git log --pretty=format:"%s%x1f%b%x1e" and select a bump level."""

import re
import sys

SUBJECT = re.compile(r"^(?P<type>[a-z]+)(?:\([^\r\n]*\))?(?P<breaking>!)?:")
BREAKING_BODY = re.compile(r"^BREAKING(?: |-)CHANGE:", re.MULTILINE)


def semver_level(log):
    level = "patch"
    for record in log.split("\x1e"):
        if not record.strip():
            continue
        subject, _, body = record.lstrip("\r\n").partition("\x1f")
        match = SUBJECT.match(subject)
        if (match and match["breaking"]) or BREAKING_BODY.search(body):
            return "major"
        if match and match["type"] in {"feat", "revert"}:
            level = "minor"
    return level


if __name__ == "__main__":
    print(semver_level(sys.stdin.read()))
