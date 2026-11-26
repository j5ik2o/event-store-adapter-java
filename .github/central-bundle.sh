#!/usr/bin/env bash
# Maven Central に上げる bundle を作る。release.yml（R2b-2）と CI の release-bundle の job が呼ぶ。
# 使い方: central-bundle.sh <版 X.Y.Z> <出力する zip>
# 署名の鍵は環境変数 SIGNING_KEY・SIGNING_PASSWORD（build.gradle.kts の signing が読む）。
# --no-daemon：SIGNING_* を持った Gradle のデーモンを、この手順のあとに残さない。
set -euo pipefail

version="$1"
output="$2"
repository="$(mktemp -d "${RUNNER_TEMP:-${TMPDIR:-/tmp}}/central-repository.XXXXXX")"
trap 'rm -rf "$repository"' EXIT

./gradlew --no-daemon publishToMavenLocal -Dmaven.repo.local="$repository"
python3 "$(dirname "$0")/central-bundle.py" build --repository "$repository" --version "$version" --output "$output"
