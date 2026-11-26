> この文書は [CONTRIBUTING.md](CONTRIBUTING.md) の日本語版です。英語版が正本で、食い違うときは英語版に従います。

# event-store-adapter-java への貢献

[English](CONTRIBUTING.md)

## 概要

この案内は、このリポジトリに変更を加える開発者向けです。[共通契約 v5](https://github.com/j5ik2o/event-store-adapter/blob/main/docs/spec/README.md) の Java 実装です。公開パッケージは `core`・`memory`・`dynamodb`・`sqlite`・`spanner`・`bigtable` です。ライブラリの利用者向けの文書は [README](README.ja.md) と `docs/` にあります。

## 共有する契約と設計判断

すべての言語の実装で共有する仕様は、ハブのリポジトリにあります: [docs/spec](https://github.com/j5ik2o/event-store-adapter/tree/main/docs/spec)。共有する設計判断は [docs/adr](https://github.com/j5ik2o/event-store-adapter/tree/main/docs/adr) にあります。

一部のファイルはハブから固定して写したもので、CI が SHA-256 で照合します: `conformance/`（データの版 1.5.0）・`api-surface/surface.json`・`user-docs/template.json`・`tools/user-docs/check.py`。[conformance/README.md](conformance/README.md) と [api-surface/README.md](api-surface/README.md) を参照してください。これらのファイルは、このリポジトリで編集しません。

## ローカルの準備

- コードは Java 11 向けにコンパイルします（`build.gradle.kts` の Gradle toolchain と `options.release`）。テストは `-PtestJavaVersion`（既定 11）で選んだ JDK で実行します。
- ビルドは `core`（Memory を含む）・`dynamodb`・`spanner`・`bigtable`・`sqlite` のモジュールに分かれます。ルートプロジェクトはまとめ用の成果物 `event-store-adapter-java` で、クラスを含まず 5 モジュールに依存します。各保存先の SDK は、その保存先のモジュールだけの `api` 依存です。
- Gradle wrapper（`./gradlew`、Gradle 9.9.0）を使います。CI は Gradle 自体を JDK 25 で動かします。
- DynamoDB のテストは、digest で固定した DynamoDB Local のイメージを Testcontainers で起動するので、Docker が必要です。Spanner のテストは、digest で固定した Cloud Spanner エミュレーターのイメージ `gcr.io/cloud-spanner-emulator/emulator:1.5.58` を Testcontainers で起動するので、Docker が必要です。実行されない必須の Spanner 適合ケースがあるとビルドが失敗します。SQLite のテストは同梱の JDBC ドライバーと一時的なローカルファイルを使うので、Docker は不要です。Bigtable のテストは、digest で固定した Cloud Bigtable エミュレーターのイメージ `gcr.io/google.com/cloudsdktool/google-cloud-cli` を Testcontainers で起動するので、Docker が必要です。実行されない必須の Bigtable 適合ケースがあるとビルドが失敗します。初回の実行はイメージを取得するので時間がかかります。
- リリース用の道具の検査には Python 3 を使います。CI は Python 3.15 を使います。 `tools/user-docs/check.py` は Python 3.10 以上が必要です。`.github/tests` のリリース用の道具のテストには、`.github/tests/requirements.txt` のパッケージ（Hypothesis と semver）が必要です。

## 検査

手元で使う検査コマンドです。[CI のワークフロー](.github/workflows/ci.yml) は Java の版ごとに `test` タスク全体を実行し、公開する型の形の試験も含めます。下の `--tests '*apisurface*'` は、その試験だけを手元で実行する場合の指定です。

```sh
./gradlew spotlessCheck
./gradlew test -PtestJavaVersion=11
./gradlew test --tests '*apisurface*'
python3 tools/conformance/manifest.py verify
python3 tools/user-docs/check.py
python3 -m pip install -r .github/tests/requirements.txt
python3 -m unittest discover -s .github/tests -v
```

- CI は `-PtestJavaVersion` を 11・17・19 にしてテストを実行します。
- `-PgoogleSdkSet=5.1` または `-PgoogleSdkSet=5.2` を付けると、5.1.0 または 5.2.0 の公開版が依存する Spanner と Bigtable の SDK でテストを実行します（5.1 は Spanner 6.124.0 と Bigtable 2.85.0、5.2 は Spanner 6.127.0 と Bigtable 2.88.0）。変わるのはテストの実行時クラスパスだけで、ライブラリは宣言した SDK でコンパイルします。読み込んだ SDK が選んだ組と違うと `GoogleSdkSetTest` が失敗します。CI は `createOffloaded` のテストを両方の組で実行します。例: `./gradlew test -PgoogleSdkSet=5.1 --tests '*OffloadedEventStoreTest'`。
- CI は、`conformance/manifest.json`・`api-surface/surface.json`・`user-docs/template.json`・`tools/user-docs/check.py` の SHA-256 も `sha256sum -c` で照合し、workflow ファイルを actionlint v1.7.12 で検査します。期待値は `ci.yml` にあります。
- 適合の報告は、テストが `build/reports/conformance/` に書き出します。
- `release-bundle` のジョブは、使い捨ての署名の鍵で Maven Central の bundle を `.github/central-bundle.sh`（Release ワークフローが使うスクリプト）で組み、180 ファイルと 30 の署名を確かめます。`release-model` のジョブは、公開の経路の Quint のモデルを検査します（`bash .github/quint/run-quint.sh ci`。[.github/quint/README.md](.github/quint/README.md) を参照）。どちらも秘密の値を使わず、何も公開しません。
- `createOffloaded` の長時間の負荷試験は、`EVENT_STORE_SOAK_SECONDS` を設定しない限り飛ばします。保存先ごとに、その秒数のあいだ複数の集約に 4 操作を流し、終わったら全集約を読み戻して、スレッドとヒープを確かめます。CI では実行しません。例: `EVENT_STORE_SOAK_SECONDS=900 ./gradlew test --tests '*OffloadedSoakTest'`。結果は `build/test-results/test/TEST-*OffloadedSoakTest.xml` の標準出力にある `SOAK` で始まる行です。

## 設計の経緯

[docs/design/next-major.md](docs/design/next-major.md)（[日本語](docs/design/next-major.ja.md)）は、v5 を作る際の設計の記録です。現在の使い方は、README と現在のコードで確認してください。共有する判断は、ハブの [docs/adr](https://github.com/j5ik2o/event-store-adapter/tree/main/docs/adr) にあります。

## 変更とリリース

- 変更は pull request で `main` に入れます。`CI Success` ジョブが、lint・test・google-sdk-sets・aislop・release-tools・release-bundle・release-model の結果をまとめて判定します。
- 版は `version` ファイルに持ち、ビルドが読みます。
- Bump-Version ワークフローは手動で起動します。入力は `level`・`force_bump`・`version`・`rehearsal` です。版を上げる段階は、コミットの件名の Conventional Commits の型で決まります。`!` か `BREAKING CHANGE` は major、`feat` と `revert` は minor、それ以外は patch です（`.github/semver-level.py`）。
- Bump-Version は `main` へ push しません。新しい版を `release/vX.Y.Z` の枝の `version` にコミットし、`main` への pull request を開きます。GitHub Release は作らず、`version` を SNAPSHOT 版へ戻すこともしません。
- `version` を変えたコミットが `main` に入ると、Bump-Version のタグのジョブが、関門（`.github/release-gate.py`）でそのコミットを確かめます。コミットは、このリポジトリの `release/vX.Y.Z` からの pull request を squash でマージしたもので、`version` の版だけを変えていなければなりません。版は、前の版とすべての `vX.Y.Z` のタグより大きくなければなりません。親のコミットに `version` が無いときは、リリースしません。
- `vX.Y.Z` のタグを push すると Release ワークフローが起動します。Release ワークフローは、タグのコミットをもう一度関門で確かめてから、その版を Maven Central へ公開します（[リリースの出し方](#リリースの出し方)）。Snapshot（試験版）は公開しません。正式版を使ってください。
- 依存の更新は Renovate が行います（`renovate.json`）。

### リリースの出し方

1. Bump-Version を流します（`rehearsal: false`）。版を決めるときは `version` に書きます。空なら、版を上げる段階で決まります。
2. pull request `version up to vX.Y.Z` を確かめます。変えているのが `version` だけで、Check unresolved comments が成功していなければなりません。リリースを出してよいときに、squash でマージします。やめるときは、閉じて枝を消します。
3. タグのジョブ（`Tag the merged version`）が、マージのコミットを関門で確かめ、`vX.Y.Z` を push します。
4. Release ワークフローが、ジョブ `Publish to Maven Central` を次の順に流します。
   1. タグのコミットを関門で確かめます。
   2. Gradle がタグの版をビルドすることを確かめます。
   3. Maven Central と前の Release の run を確かめます（`.github/central-portal.py precheck`）。処理中の deployment があれば待ちます（最大 60 分）。版が公開済みならアップロードせずに先へ進み、行方の分からない試みがあれば止まります。
   4. bundle（180 ファイル）を作り、Central Portal へ上げます（`publishingType=AUTOMATIC`）。
   5. deployment の ID を artifact `central-deployment-<試みの番号>-<ID>` に残します（90 日）。
   6. deployment が `PUBLISHED` になるまで待ちます（最大 60 分）。
5. `PUBLISHED` になったら、`Create the GitHub release` が GitHub のリリースを作り、`Check Maven Central` が、repo1 が6つの POM を返すまで待ちます（最大 60 分）。

予行（`rehearsal: true`）は、`version up to vX.Y.Z (rehearsal, do not merge)` という題の draft の pull request を開きます。マージせずに閉じて、枝を消してください。

署名の秘密の値は bundle の手順だけに、Sonatype の秘密の値は事前の確認・アップロード・待ちの手順だけに渡します。`release.yml` は personal access token を使いません。

確かめる仕組みを保つための決まり：

- `vX.Y.Z` のタグを手で作らないでください。GitHub のリリースの画面で作るタグも同じです。タグは、そのタグが指すコミットの Release ワークフローを動かします。Release ワークフローに関門が入る前のコミットには、タグを作らないでください。
- Release の run と、その `central-` の artifact を消さないでください。artifact の保存期間は 90 日のままにします。事前の確認は、これらを読んで前の試みの deployment を見つけます。
- `release.yml` のジョブと手順の名前を変えないでください。事前の確認は、`.github/central-rules.py` の名前（`RELEASE_JOB`、`UPLOAD_STEP`、`PRE_UPLOAD_STEPS`）で、前の run のアップロードを見つけます。どうしても変えるときは、古い名前も集合に残します。
- `release.yml` を変える pull request では、`.github/tests/fixtures/release-workflow.yml`（レビュー済みの写し）も同じように変えます。`.github/tests/test_release_workflow_shape.py` が `PUSH_RELEASE_TAG: 'true'` を許すのは、`release.yml` がこの写しと行ごとに一字一句同じときだけです。違ってよいのは `uses:` の行の版だけです。
- 失敗した run・取り消された run を再実行できるのは、始まってから 30 日までです。

リリースを止めるときは、`bump-version.yml` の `PUSH_RELEASE_TAG` を `'false'` にする pull request をマージします。`'false'` の間、タグのジョブは、打つはずのタグを job summary に書いて終わります。その間にマージした版上げの pull request の版は、タグが付かないまま `main` に残ります。pull request で `version` を前の SNAPSHOT 版に戻し、出したい版を `version` に指定して Bump-Version をやり直してください。

### リリースが途中で止まったとき

たいていは、その run の失敗したジョブを再実行すれば回復します（Re-run failed jobs）。再実行のたびに、アップロードの前に Maven Central と前の試みを確かめ直すので、同じ版を二度上げることはありません。

止まり方によっては、repository variable `CENTRAL_DEPLOYMENTS`（Settings → Secrets and variables → Actions → Variables）に行を足します。1行に1つ書きます。

- `<run_id>/<試みの番号>=<deployment の ID>`：run `<run_id>` の試み `<試みの番号>` が上げた deployment。
- `<run_id>/<試みの番号>=none`：その試みは何も上げていない。または、その deployment を Portal で drop した。
- `tag/vX.Y.Z=checked`：その版の deployment が処理中でないことを、Portal で確かめた。

ID が無くて事前の確認が止まったときは、そのエラーの文が、足す行を `<run_id>/1` から `<run_id>/<run_attempt>` まで、行の無い試みすべてについてまとめて出します。試みの deployment は、Portal で `event-store-adapter-java-<版>-run<run_id>-<試みの番号>` という名前になっています。

| 起きたこと | ワークフローの動き | 回復 |
|---|---|---|
| タグが版上げの pull request の squash に付いていない、版と一緒にコードを変えた、版を下げた、タグと版が合わない | 関門の手順が失敗する。ビルドもアップロードもしない | タグを消す。`version` を pull request で直す |
| Gradle がタグと違う版をビルドする | `Check the version Gradle builds` が失敗する | `version` を pull request で直し、次の版で出す |
| GitHub・Portal・repo1 への照会が失敗した（5xx、時間切れ） | 事前の確認が止まる | 失敗したジョブを再実行する |
| ほかの run の deployment が処理中 | 事前の確認が最大 60 分待ち、時間切れで失敗する | 失敗したジョブを再実行する |
| アップロードしたかもしれないのに、deployment の ID が無い試みがある（手順の一覧が切れた試み、ジョブの無い run、release ジョブの無い試みを含む） | 事前の確認が止まり、足す行を出す | 試みの job summary やログ、または Portal で名前から ID を探す。出された試みすべてに `<run_id>/<試みの番号>=<ID>` か `=none` を足して再実行する |
| アップロードが 201 と deployment の ID を返さない | アップロードの手順が失敗する。次の試みは上の行のとおり止まる | 上の行と同じ |
| bundle のファイルが足りない・多い | bundle の手順が失敗する。アップロードしない | pull request で直し、次の版で出す。再実行はタグのコミットのスクリプトで動くので、直しは効かない |
| 署名の鍵が無い・違う | bundle の手順の署名で失敗する。アップロードしない | 秘密の値を直して再実行する |
| deployment が `FAILED` | 待ちの手順が Portal の errors を出して失敗する。GitHub のリリースは作らない | 失敗したジョブを再実行する（事前の確認が `FAILED` を見て、上げ直す）。中身の誤りなら次の版で直す |
| `FAILED` の deployment を Portal で drop した | 事前の確認が止まる（ID が見つからない） | drop したあとに `<run_id>/<試みの番号>=none` を足して再実行する |
| 60 分たっても deployment が処理中 | 待ちの手順が失敗する | 失敗したジョブを再実行する。事前の確認は同じ deployment を待ち、上げ直さない |
| 60 分たっても repo1 が版を返さない | `Check Maven Central` が失敗する | `Check Maven Central` だけを再実行する |
| GitHub のリリースを作れなかった | `Create the GitHub release` が失敗する | `Create the GitHub release` だけを再実行する |
| より新しいタグがある（版が追い越された） | 関門が止まる（`must be greater than the latest tag`）。その版は公開しない | そのまま（飛んだ版）。その版が既に `PUBLISHED` だったとき（待ちが時間切れになり、再実行の前に新しいタグができた）は、[新しいタグの前に公開された版](#新しいタグの前に公開された版)の手順で回復する |
| 待っていた公開のジョブが、`release-publish` の group にあとから着いたジョブ（ほかのタグの push、ほかの版の再実行）に置き換えられた | その run は始まる前に取り消される。その run は何も公開しない | その版が最新の `vX.Y.Z` のタグなら、取り消された run のジョブをすべて再実行する（取り消されたジョブは、アップロードに数えない）。そうでなければ、再実行しても新しいタグがあるので関門で止まる。前の試みで版が既に公開されていたとき（たとえば GitHub のリリースの作成だけが失敗していた）は、[新しいタグの前に公開された版](#新しいタグの前に公開された版)の手順で回復する。公開されていなければそのまま（飛んだ版） |
| タグのジョブ（`Tag the merged version`）が、`release-tag` の group にあとから着いたジョブに置き換えられた | その run は取り消される。その版にタグが付かない | その版が最新の `vX.Y.Z` のタグより大きければ、取り消された run を再実行する。あとにほかの pull request がマージされていても同じ（関門は `main` の先頭であることを求めない）。そうでなければそのまま（関門が拒否する） |
| `CENTRAL_DEPLOYMENTS` の行の形が違う、artifact と違う ID を書いた、Portal が知っている deployment に `none` を書いた | 事前の確認が止まる | 行を直すか消して、再実行する |
| Release の run や `central-` の artifact を消した。タグの push で Release の run が起きなかった（一度に4つ以上のタグの push、障害） | 事前の確認が止まる（Release の run の無いタグがある） | その版の deployment が処理中でないことを Portal で確かめ、`tag/vX.Y.Z=checked` を足して再実行する。タグは動かさない |
| `release.yml` のジョブや手順の名前を変えた | 前の run で、事前の確認が止まる | 変えない。どうしても変えるときは、古い名前も `.github/central-rules.py` に残す |
| artifact の保存期間を 90 日より短くした | 事前の確認が止まる | 90 日に戻す |
| 30 日を過ぎて、run を再実行できない | | その版は飛んだ版として扱い、次の版を出す。その前に、行方の分からない試みすべてに行を足す |
| 80 日より前の run | 事前の確認は数えない | |

#### 新しいタグの前に公開された版

版は Maven Central に出ているのに、その run が終わる前に新しい `vX.Y.Z` のタグが push された場合、古い版の再実行は、どれも関門で止まります。起きるのは、deployment の待ちが時間切れになり、そのあと Portal が公開したとき、または前の試みが公開したあとで後の手順（たとえば GitHub のリリースの作成）が失敗し、再実行が `release-publish` の group で置き換えられたときです。このとき、GitHub のリリースと repo1 の確認が欠けます。手で回復します。

1. repo1 が版を返すことを確かめます。`.github/central-bundle.py` の6つの成果物（`ARTIFACTS`）それぞれについて、`https://repo1.maven.org/maven2/io/github/j5ik2o/<成果物>/<版>/<成果物>-<版>.pom` を見ます。
2. 前のリリースのタグ `<previous>` からリリースノートを書きます。

   ```sh
   python3 .github/create-release-note-header.py https://github.com j5ik2o/event-store-adapter-java <previous> vX.Y.Z > release-body.md
   git log <previous>..vX.Y.Z --no-merges --format='%H%x1f%s%x1f%b%x1e' \
     | python3 .github/create-release-note-body.py https://github.com j5ik2o/event-store-adapter-java >> release-body.md
   ```

3. リリースを作ります：`gh release create vX.Y.Z --verify-tag --title "Release vX.Y.Z" -F release-body.md`。

この場合のために関門を緩めることはしません。関門は、事前の確認より前に、秘密の値を持たずに、版がほかのどのタグより大きいことを確かめ続けます。
