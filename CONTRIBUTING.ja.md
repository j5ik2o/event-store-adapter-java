> この文書は [CONTRIBUTING.md](CONTRIBUTING.md) の日本語版です。英語版が正本で、食い違うときは英語版に従います。

# event-store-adapter-java への貢献

[English](CONTRIBUTING.md)

## 概要

この案内は、このリポジトリに変更を加える開発者向けです。[共通契約 v5](https://github.com/j5ik2o/event-store-adapter/blob/main/docs/spec/README.md) の Java 実装です。公開パッケージは `core`・`memory`・`dynamodb`・`sqlite` です。ライブラリの利用者向けの文書は [README](README.ja.md) と `docs/` にあります。

## 共有する契約と設計判断

すべての言語の実装で共有する仕様は、ハブのリポジトリにあります: [docs/spec](https://github.com/j5ik2o/event-store-adapter/tree/main/docs/spec)。共有する設計判断は [docs/adr](https://github.com/j5ik2o/event-store-adapter/tree/main/docs/adr) にあります。

一部のファイルはハブから固定して写したもので、CI が SHA-256 で照合します: `conformance/`（データの版 1.3.0）・`api-surface/surface.json`・`user-docs/template.json`・`tools/user-docs/check.py`。[conformance/README.md](conformance/README.md) と [api-surface/README.md](api-surface/README.md) を参照してください。これらのファイルは、このリポジトリで編集しません。

## ローカルの準備

- コードは Java 11 向けにコンパイルします（`build.gradle.kts` の Gradle toolchain と `options.release`）。テストは `-PtestJavaVersion`（既定 11）で選んだ JDK で実行します。
- Gradle wrapper（`./gradlew`、Gradle 9.8.2）を使います。CI は Gradle 自体を JDK 25 で動かします。
- DynamoDB のテストは、digest で固定した DynamoDB Local のイメージを Testcontainers で起動するので、Docker が必要です。SQLite のテストは同梱の JDBC ドライバーと一時的なローカルファイルを使うので、Docker は不要です。
- リリース用の道具の検査には Python 3 を使います。CI は Python 3.15 を使います。 `tools/user-docs/check.py` は Python 3.10 以上が必要です。

## 検査

手元で使う検査コマンドです。[CI のワークフロー](.github/workflows/ci.yml) は Java の版ごとに `test` タスク全体を実行し、公開する型の形の試験も含めます。下の `--tests '*apisurface*'` は、その試験だけを手元で実行する場合の指定です。

```sh
./gradlew spotlessCheck
./gradlew test -PtestJavaVersion=11
./gradlew test --tests '*apisurface*'
python3 tools/conformance/manifest.py verify
python3 tools/user-docs/check.py
python3 -m unittest discover -s .github/tests -v
```

- CI は `-PtestJavaVersion` を 11・17・19 にしてテストを実行します。
- CI は、`conformance/manifest.json`・`api-surface/surface.json`・`user-docs/template.json`・`tools/user-docs/check.py` の SHA-256 も `sha256sum -c` で照合し、workflow ファイルを actionlint v1.7.12 で検査します。期待値は `ci.yml` にあります。
- 適合の報告は、テストが `build/reports/conformance/` に書き出します。

## 設計の経緯

[docs/design/next-major.md](docs/design/next-major.md)（[日本語](docs/design/next-major.ja.md)）は、v5 を作る際の設計の記録です。現在の使い方は、README と現在のコードで確認してください。共有する判断は、ハブの [docs/adr](https://github.com/j5ik2o/event-store-adapter/tree/main/docs/adr) にあります。

## 変更とリリース

- 変更は pull request で `main` に入れます。`CI Success` ジョブが、lint・test・release-tools の結果をまとめて判定します。
- 版は `version` ファイルに持ち、ビルドが読みます。
- Bump-Version ワークフローは手動で起動します。入力は `level`・`force_bump`・`version` です。版を上げる段階は、コミットの件名の Conventional Commits の型で決まります。`!` か `BREAKING CHANGE` は major、`feat` と `revert` は minor、それ以外は patch です（`.github/semver-level.py`）。
- Bump-Version は `vX.Y.Z` のタグを push し、GitHub Release を作り、`version` を次の SNAPSHOT 版にします。
- `vX.Y.Z` のタグを push すると Release ワークフローが起動し、Sonatype（Maven Central）へ公開します。`main` で CI が成功すると、Snapshot ワークフローがスナップショットを公開します。
- 依存の更新は Renovate が行います（`renovate.json`）。
