#!/usr/bin/env bash
# release_path.qnt の検査を流す。
# 使い方:
#   bash .github/quint/run-quint.sh ci          CI 用：typecheck、quint test の全件、R2b の instance の quint run（少ないサンプル）
#   bash .github/quint/run-quint.sh full [N]    全 instance を N サンプル（既定 3000）で流す。モデルを変えた PR で手元で流し、結果を PR に書く
# 環境変数 QUINT：quint のコマンド（既定 quint）。CI は npx で版を固定して使う。
set -euo pipefail
cd "$(dirname "$0")"
read -r -a Q <<< "${QUINT:-quint}"
mode="${1:-ci}"
SEED=7
STEPS=30
POSITIVE_ALL=(java javaReleasesOnly kotlin kotlinNoManual scala rs go javaR2aOnly scalaBeforeR5 javaR2b javaR2bReleasesOnly)
# 前提を外すと破れることを示す instance と、破れる不変条件
NEGATIVE_ALL=(kotlinNoRequired:legacyBeforeNewer goManualTags:publishedOnlyFromReleasePR
  jsBefore1161:neverTimesOutAfterPublished wholeRerun:noDuplicatePublish noDeploymentCheck:noDuplicatePublish
  javaR2aPushesTags:noDuplicatePublish scalaOldBumpLive:legacyBeforeNewer noOtherDeploymentCheck:publishedMonotonic
  stepRecordDropped:noDuplicatePublish unknownIgnored:noDuplicatePublish noneSkipsQuery:noDuplicatePublish
  noneSkipsQuery:publishedMonotonic noLatestTagCheck:publishedMonotonic)
POSITIVE_CI=(javaR2b javaR2bReleasesOnly)
NEGATIVE_CI=(stepRecordDropped:noDuplicatePublish unknownIgnored:noDuplicatePublish noneSkipsQuery:noDuplicatePublish
  noLatestTagCheck:publishedMonotonic)
TESTS=(javaPathsTest kotlinPathsTest javaR2aOnlyTest javaR2bPathsTest confirmBoundaryTest)

if [[ "$mode" == ci ]]; then
  samples=500
  positive=("${POSITIVE_CI[@]}")
  negative=("${NEGATIVE_CI[@]}")
elif [[ "$mode" == full ]]; then
  samples="${2:-3000}"
  positive=("${POSITIVE_ALL[@]}")
  negative=("${NEGATIVE_ALL[@]}")
else
  echo "usage: run-quint.sh ci|full [samples]" >&2
  exit 2
fi

failed=0
log="$(mktemp)"
trap 'rm -f "$log"' EXIT
"${Q[@]}" typecheck release_path.qnt
"${Q[@]}" typecheck release_path_test.qnt
echo "typecheck: ok"
for main in "${TESTS[@]}"; do
  echo "## quint test ${main}"
  "${Q[@]}" test release_path_test.qnt --main="$main" || failed=1
done
for main in "${positive[@]}"; do
  echo "## ${main}  (expect: no violation of safety)"
  if ! "${Q[@]}" run release_path.qnt --main="$main" --invariant=safety --max-samples="$samples" \
    --max-steps="$STEPS" --seed="$SEED" > "$log" 2>&1; then
    tail -n 40 "$log"
    failed=1
  fi
  grep -E '^\[(ok|violation)\]' "$log"
done
for item in "${negative[@]}"; do
  main="${item%%:*}"
  invariant="${item#*:}"
  echo "## ${main}  invariant=${invariant}  (expect: violation)"
  if "${Q[@]}" run release_path.qnt --main="$main" --invariant="$invariant" --max-samples="$samples" \
    --max-steps="$STEPS" --seed="$SEED" > "$log" 2>&1; then
    echo "expected a violation of ${invariant} in ${main}, found none"
    failed=1
  fi
  grep -E '^\[(ok|violation)\]' "$log"
done
if [[ "$mode" == full ]]; then
  echo "## java  invariant=neverTimesOutAfterPublished  (expect: no violation)"
  "${Q[@]}" run release_path.qnt --main=java --invariant=neverTimesOutAfterPublished --max-samples="$samples" \
    --max-steps="$STEPS" --seed="$SEED" | grep -E '^\[(ok|violation)\]' || failed=1
fi
exit "$failed"
