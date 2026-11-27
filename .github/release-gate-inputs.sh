#!/usr/bin/env bash
# 関門 (release-gate.py check) にかける事実を、指定のディレクトリに書く。
# event-store-adapter-js の .github/release-gate-inputs.sh を写し、版のファイルを `version` に替えたもの。
# 関連する pull request を1つずつ読み直し、全体の差分を git で求める部分は event-store-adapter-rs#375（81edc85）の写し（#870）。
# 使い方: release-gate-inputs.sh <コミット> <出力先>
# 環境変数: GH_TOKEN、GITHUB_REPOSITORY、PULLS_RETRY_SECONDS（既定 10。PR の照会を再試行する間隔）
# 親（またはコミット）に `version` が無いときは、parent-version（または version）を書かない。関門はそれを「無い」と読む。
set -euo pipefail

sha="$1"
out="$2"
mkdir -p "$out"

git fetch --no-tags origin main
# FETCH_HEAD は、あとの refs/pull/<番号>/head の fetch で書き換わる。main の先は最初に取っておく
main="$(git rev-parse FETCH_HEAD)"
on_main=false
if git merge-base --is-ancestor "$sha" "$main"; then
  on_main=true
fi
echo "$on_main" > "${out}/on-main"
# 親の数。通常のマージ（親が2つ）は関門が拒否する
git rev-list --parents -n 1 "$sha" | awk '{print NF - 1}' > "${out}/parents"
if git cat-file -e "${sha}^:version" 2> /dev/null; then
  git cat-file blob "${sha}^:version" > "${out}/parent-version"
fi
if git cat-file -e "${sha}:version" 2> /dev/null; then
  git cat-file blob "${sha}:version" > "${out}/version"
fi
git diff --name-only "${sha}^" "$sha" > "${out}/changed-files"
git ls-remote --tags origin > "${out}/remote-tags"

# マージの直後は、コミットと PR の関連付けがまだ返らないことがある。一時的な失敗と空の応答だけを数回やり直す。
attempts=5
read_ok=false
for attempt in $(seq 1 "$attempts"); do
  if gh api "repos/${GITHUB_REPOSITORY}/commits/${sha}/pulls" > "${out}/pulls.json"; then
    read_ok=true
    if [[ "$(tr -d '[:space:]' < "${out}/pulls.json")" != "[]" ]]; then
      break
    fi
    echo "no pull request is associated with ${sha} yet (${attempt}/${attempts})"
  else
    read_ok=false
    echo "could not read the pull requests of ${sha} (${attempt}/${attempts})"
  fi
  if [[ "$attempt" -lt "$attempts" ]]; then
    sleep "${PULLS_RETRY_SECONDS:-10}"
  fi
done
if [[ "$read_ok" != true ]]; then
  echo "::error::could not read the pull requests of ${sha}; re-run the job"
  exit 1
fi
# 一覧の項目には head の SHA が無い。1つずつ読み直し、pull request の全体の差分（マージの基点から head まで）を git で求める。
# 関門は、その差分が `version` の版だけを替えることを確かめる（rebase のマージでも）。
# head を取れなければ差分を書かない。関門はそれを拒否する（再実行で読み直す）
numbers="$(python3 -c 'import json, sys; print(" ".join(str(p["number"]) for p in json.load(open(sys.argv[1]))))' \
  "${out}/pulls.json")"
details=()
for number in $numbers; do
  detail="${out}/pull-${number}"
  mkdir -p "$detail"
  gh api "repos/${GITHUB_REPOSITORY}/pulls/${number}" > "${detail}/pull.json"
  details+=("$detail")
  head="$(python3 -c 'import json, sys; print((json.load(open(sys.argv[1])).get("head") or {}).get("sha") or "")' \
    "${detail}/pull.json")"
  if [[ -n "$head" ]] && git fetch --no-tags origin "refs/pull/${number}/head" \
    && [[ "$(git rev-parse FETCH_HEAD)" == "$head" ]]; then
    base="$(git merge-base "$head" "$main")"
    git diff --name-only "$base" "$head" > "${detail}/files"
    if git cat-file -e "${base}:version" 2> /dev/null; then
      git cat-file blob "${base}:version" > "${detail}/before"
    fi
    if git cat-file -e "${head}:version" 2> /dev/null; then
      git cat-file blob "${head}:version" > "${detail}/after"
    fi
  else
    echo "could not read the head ${head:-(none)} of the pull request #${number}"
  fi
done
python3 - "${out}/pulls.json" ${details[@]+"${details[@]}"} <<'PY'
import json, os, sys

def text(path):
    if not os.path.exists(path):
        return None
    with open(path, 'rb') as file:
        data = file.read()
    try:
        return data.decode('utf-8')
    except UnicodeDecodeError:
        return None

pulls = []
for detail in sys.argv[2:]:
    with open(os.path.join(detail, 'pull.json')) as file:
        pull = json.load(file)
    pull.pop('whole_diff', None)   # 関門が読む差分は、ここで git から求めたものだけ
    files = os.path.join(detail, 'files')
    if os.path.exists(files):
        with open(files) as file:
            pull['whole_diff'] = {'files': [line for line in file.read().splitlines() if line],
                                  'before': text(os.path.join(detail, 'before')),
                                  'after': text(os.path.join(detail, 'after'))}
    pulls.append(pull)
with open(sys.argv[1], 'w') as file:
    json.dump(pulls, file)
PY
