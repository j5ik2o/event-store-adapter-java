> この文書は [spanner-backend.md](spanner-backend.md) の日本語版です。英語版が正本です。

# Spanner 保存先の設計記録

Java の Spanner 保存先が、ハブの Spanner プロファイル（共通契約 v5）の規則をどう実装し、何の試験で確かめているかを対応づける記録です。規範（ハブの `docs/spec`）と配布データ（`conformance/` 1.3.0）は変更していません。利用者向けの形式の説明は [SPANNER_DATABASE_SCHEMA](../SPANNER_DATABASE_SCHEMA.ja.md) にあります。

## 1. 公開型と資源の所有

- `SpannerRetentionPolicy` は `none()`、`delete(n)`、`ttl(n, graceDays)` です。TTL の猶予は正の日数です（SPN-D7）。core の `RetentionPolicy.ttl` は秒なので流用せず、core の契約は変えません。
- `SpannerStorageConfig` は、表の接頭辞、保持方式（既定は保持なし）、`transactionRetryLimit`（既定 5、初回は数えない）を持ちます。`build()` は、不正な接頭辞、1 未満の保持件数、1 日未満の猶予、負の上限、`changeFeed(true)` を設定エラーにします（SPN-D8）。
- `SpannerStorage.open(DatabaseClient, SpannerStorageConfig)` は配置を照合して保存先を返します。`DatabaseClient`（認証やエミュレーターの接続先を含む）は利用者が作って所有し、保存先は作らず・閉じず・設定しません。`DatabaseClient` が公開シグネチャに現れるため、`google-cloud-spanner:6.124.0` は `api` の依存です。
- `SpannerEventStore.create` は同期のインターフェイスを提供します。`createAsync` は、メモリや SQLite と同じく呼び出しスレッドで実行し、完了済みの Future を返します。

## 2. 規則・実装・試験

実装は `src/main/java/.../spanner/`、試験は `src/test/java/.../spanner/` にあります。試験は固定したイメージの Cloud Spanner エミュレーター（`SpannerEmulator`）で動かし、Docker がなくても skip しません。

| 規則 | 振る舞い | 実装 | 試験 | 適合ケース |
|---|---|---|---|---|
| SPN-1 / SPN-D1b | 1 つの接頭辞から 5 つの表を導く。各名前は 128 文字以下の識別子。書き込みは SERIALIZABLE、読み取りは strong | `SpannerTableNames`、`SpannerTransactions`、`SpannerRead` | `SpannerStorageConfigTest`、`SpannerTransactionRetryTest`、`SpannerReadTest` | `core-*` |
| SPN-2 / SPN-D5 | 開くときは方言・列と型・主キー・設定の行・行削除ポリシーを照合するだけで、DDL は実行しない | `SpannerStorage`、`SpannerLayout` | `SpannerOpenTest`（削除した表を作り直さないことを含む） | `spanner-seeded-*` |
| SPN-3 | `seq_nr` は 1 から 2^53-1。`occurred_at_ns` は符号付き `INT64` のナノ秒で `TIMESTAMP` にしない。TTL の印 `expired_at` は別の列 | `SpannerAppend`、`SpannerRead` | `SpannerAppendTest`（境界値、2^53-1）、`SpannerJsFormatTest` | `occurred-at-*`、`seq-*` |
| SPN-4 / K-1 / K-2 | キーは `(hash_prefix, aid)` の完全一致の組。`hash_prefix` は UTF-8 の `aid` の FNV-1a 64 を 8 バイトのビッグエンディアンにした値で、符号付き・剰余・16 進にしない | `SpannerKey` | `SpannerKeyTest`、`SpannerAppendTest`、`SpannerJsFormatTest` | `hash-fnv1a64-*`、`aid-*` |
| SPN-5 | `INT64` は `long` で、payload は `BYTES` で束縛する | `SpannerAppend`、`SpannerRead` | `SpannerAppendTest` | `occurred-at-*` |
| SPN-6 | head の照合とすべての書き込みを 1 つの SERIALIZABLE 読み書きトランザクションで行う。journal と新しい head の主キー重複だけが楽観ロック。履歴の主キー重複は保存先エラー | `SpannerAppend` | `SpannerAppendTest`、`SpannerTransactionRetryTest` | `core-*` |
| SPN-7 | `ABORTED` なら、同じ封筒で上限までトランザクションを最初からやり直し、超えたら保存先エラー。試行の中で通知も採番もしない | `SpannerTransactions` | `SpannerTransactionRetryTest` | `spanner-aborted-retry-within-limit`、`spanner-aborted-retry-limit-exceeded` |
| SPN-8 | コミットの応答を失ったら成否不明の保存先エラー。補償の書き込みはしない。同じ封筒を送り直すと楽観ロック | `SpannerTransactions` | `SpannerTransactionRetryTest` | `spanner-commit-response-lost` |
| SPN-9 | head と `snapshot_current` を 1 つの strong 読み取り専用トランザクションで読む | `SpannerRead` | `SpannerReadTest`（読み取り中に確定した書き込みが組に混ざらない） | `core-*` |
| SPN-10 | head と journal を 1 つの strong 読み取り専用トランザクションで、`max(1, seqNr)` から head まで読む。欠番は保存先エラー | `SpannerRead` | `SpannerReadTest` | `core-*` |
| SPN-11 / SPN-12 | 追記の確定後、別の SERIALIZABLE トランザクションで、印のない履歴の新しい `n` 件を残す。delete 方式は履歴の行だけを削除し、TTL 方式は印のない行に一度だけ `expired_at = CURRENT_TIMESTAMP()` を設定する | `SpannerRetention` | `SpannerRetentionTest` | `core-retention-*` |
| SPN-13 / S-4 | 保持の失敗は確定済みの追記を変えない。ログと通知先に 1 回通知し、ログや通知先が投げた `Error` も隔離する | `SpannerRetention`、`SpannerEventStore` | `SpannerPostCommitNotificationTest`、`SpannerAsyncEventStoreTest`、`SpannerRetentionTest`。失敗の経路が実行されたことを assert する | `core-retention-failure-after-commit`、`core-retention-query-failure` |
| SPN-14 / SPN-15 | 変更フィード | 対象外（SPN-D8 により提供しない） | なし | なし |
| SPN-16 | TTL の猶予の日数は、クライアントの値・`ttl_grace_days`・行削除ポリシーの `INTERVAL n DAY` が一致する。delete 方式ではポリシーがない | `SpannerStorageConfig`、`SpannerLayout` | `SpannerOpenTest`、`SpannerStorageConfigTest` | `spanner-seeded-ttl-*`、`spanner-ttl-grace-days-not-positive` |
| SPN-17 | 行の `change_feed_enabled=true`、またはフィードを求める設定は設定エラー | `SpannerStorageConfig`、`SpannerLayout` | `SpannerStorageConfigTest`、`SpannerOpenTest` | `spanner-change-feed-unsupported`、`spanner-seeded-change-feed-enabled` |

Spanner の必須の適合ケース 79 件は、実際のエミュレーターで失敗 0・未検証 0 で合格します。`SpannerCaseRunnerTest` が 1 件ずつ実行し、`ConformanceRunnerTest` が結果を `build/reports/conformance/report.json` の `spanner-emulator` に書き出します。Bigtable だけのケースと DynamoDB の配置のケースは Spanner に該当しません。必須のケースが実行されないとビルドが失敗します。

## 3. 共通データの外にある規則

| 共通データの外にある規則 | 証拠 |
|---|---|
| 保存形式: 5 つの表、キーの列、`INT64` のナノ秒が JavaScript の v5 の配置と同じで、その形の行を読み、続きを追記できる | `SpannerJsFormatTest`（DDL は JavaScript 実装からの写しで、ハッシュは独立に計算する） |
| 同時実行: `seq_nr=1` の同時の最初の追記で、保存されるイベントは 1 件だけ | `SpannerTransactionRetryTest.concurrentFirstAppendsOfSequenceOneStoreExactlyOneEvent` |
| 要求の分割: 保持は 500 行以下ずつ処理し、トランザクションごとに対象を選び直す | `SpannerRetentionTest.aLargeCleanUpIsSplitIntoSeveralTransactionsAndStillKeepsTheNewestRows`（古い行 1198 件） |
| 公開型の利用: README の例が別のパッケージから公開型だけで動き、`build/reports/public-api-usage/spanner.json` を出力する | `SpannerUserAccountExampleTest` |

## 4. エラーの分類

| 事象 | 分類 |
|---|---|
| head 以下の番号。journal や新しい head の主キー重複 | 楽観ロックエラー |
| head + 1 以外の番号（W-8） | 契約違反 |
| 上限を超える `ABORTED`、コミット応答の喪失、読み取りの失敗、履歴の主キー重複、範囲内の journal の欠番、データベースに接続できない | 保存先エラー |
| 表・列・主キー・設定の行・行削除ポリシーの欠落や不一致、不正な接頭辞や保持の値、変更フィードの要求 | 設定エラー |
| シリアライズ・デシリアライズの失敗 | シリアライズエラー |

楽観ロックのメッセージには aid・`seq_nr`・head だけを入れ、ドライバーの文言や表の名前は入れません（E-2）。

## 5. 検証の限界

次は検証済みの成功として数えません。

| 項目 | 限界 | 他の証拠 |
|---|---|---|
| 実サービスでの衝突検出・容量上限・TTL の物理削除 | エミュレーターでは再現できない（プロファイル 9 章） | 印の付いた行と行削除ポリシーは確かめるが、物理削除は確かめない |
| 複数のセッションや、複数の計算機にまたがる実際の競合 | エミュレーターのプロセスと JVM が 1 つ | `SpannerTransactionRetryTest` の 4 クライアントの同時追記 |
| 実際のネットワークでのコミット応答の喪失 | 実際のコミットの後に障害を差し込んで模擬する | `spanner-commit-response-lost`、`SpannerTransactionRetryTest` |
| 変更フィード（SPN-14・SPN-15） | 最初の版では提供しない（SPN-D8） | 対象外 |
