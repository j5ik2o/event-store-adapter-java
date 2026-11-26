#!/usr/bin/env bash
# CI の release-bundle の job：使い捨ての鍵で bundle を組み、180 ファイルの形と署名を確かめる。秘密の値は使わない。
# 作業用の `version` を 0.0.0 に書き換えるので、CI の checkout の中だけで動かす（終わったら元に戻す）。
set -euo pipefail

if [[ "${CI:-}" != true ]]; then
  echo "::error::central-bundle-ci.sh rewrites the version file; run it only in CI" >&2
  exit 1
fi
work="$(mktemp -d "${RUNNER_TEMP:-/tmp}/central-bundle-ci.XXXXXX")"
cp version "${work}/version.orig"
trap 'cp "${work}/version.orig" version; gpgconf --kill gpg-agent 2> /dev/null || true; rm -rf "$work"' EXIT

export GNUPGHOME="${work}/gnupg"
mkdir -m 700 "$GNUPGHOME"
passphrase="ci-throwaway"
gpg --batch --pinentry-mode loopback --passphrase "$passphrase" \
  --quick-gen-key 'CI throwaway key <ci@example.invalid>' rsa3072 sign 1d
SIGNING_KEY="$(gpg --batch --pinentry-mode loopback --passphrase "$passphrase" --armor --export-secret-keys)"
SIGNING_PASSWORD="$passphrase"
export SIGNING_KEY SIGNING_PASSWORD

printf '0.0.0\n' > version
bash "$(dirname "$0")/central-bundle.sh" 0.0.0 "${work}/bundle.zip"
python3 "$(dirname "$0")/central-bundle.py" verify --zip "${work}/bundle.zip" --version 0.0.0

mkdir "${work}/unzipped"
python3 -c 'import sys, zipfile; zipfile.ZipFile(sys.argv[1]).extractall(sys.argv[2])' "${work}/bundle.zip" "${work}/unzipped"
signatures=0
while IFS= read -r -d '' signature; do
  gpg --batch --verify "$signature" "${signature%.asc}" 2> /dev/null
  signatures=$((signatures + 1))
done < <(find "${work}/unzipped" -name '*.asc' -print0)
if [[ "$signatures" -ne 30 ]]; then
  echo "::error::expected 30 signatures, found ${signatures}" >&2
  exit 1
fi
echo "all ${signatures} signatures verify"
