#! /usr/bin/env python3
# -*- coding: utf-8 -*-
import sys
import semver

args = sys.argv

for s in sys.stdin:
    # 先頭の v だけを外し、残り全体を SemVer として読む（`.*` で前を読み飛ばすと v10.2.0 が 0.2.0 になる。#870）。
    # 版でない行は失敗する。空白だけの行は読み飛ばす
    s = s.strip()
    if not s:
        continue
    cur_ver = semver.Version.parse(s[1:] if s.startswith('v') else s)
    if args[1] == "snapshot":
        next_ver = f"{cur_ver.bump_patch()}-SNAPSHOT"
    elif args[1] == "major":
        next_ver = str(cur_ver.bump_major())
    elif args[1] == "minor":
        next_ver = str(cur_ver.bump_minor())
    else:
        next_ver = str(cur_ver.bump_patch())
    print(next_ver)
