> この文書は [bigtable-backend.md](bigtable-backend.md) の日本語版です。英語版が正本です。

# Bigtable 保存先の設計記録

Java の Bigtable 保存先が、ハブの Bigtable プロファイル（共通契約 v5）の規則をどう実装し、何の試験で確かめているかを対応づける記録です。規範（ハブの `docs/spec`）と配布データ（`conformance/` 1.3.0）は変更していません。利用者向けの形式の説明は [BIGTABLE_DATABASE_SCHEMA](../BIGTABLE_DATABASE_SCHEMA.ja.md) にあります。

## 1. 公開型と資源の所有

- `BigtableStorageConfig` は、journal 表と head 表の ID、保持方針（core の `RetentionPolicy`。既定は保持なし）、`envelopeLimitBytes`（既定 1048576、17 から 8388608）を持ちます。`build()` は、変更フィードの要求（BT-17）、TTL の方針（BT-13）、方針が null、表 ID の欠落や空、2 つの表に同じ ID、範囲外の上限を、通信の前に設定エラーにします。Bigtable の保持はスナップショットの件数だけを数えるので core の `RetentionPolicy` を使い回し、core の契約は変えません。
- `BigtableStorage.open(BigtableDataClient, BigtableTableAdminClient, BigtableStorageConfig)` は配置を照合して保存先を返します。`BigtableStorage.initialize(BigtableDataClient, BigtableStorageConfig)` は設定行 2 つだけを書きます。2 つのクライアント（認証、1 つのクラスタに固定した app profile、指標、エミュレーターの接続先）は呼び出し側が作って所有し、保存先は作成も、閉じることも、設定もせず、表も作りません。クライアントが公開シグネチャに現れるので、`google-cloud-bigtable:2.85.0` は `api` の依存です。`grpc-api` は Spanner と同じ版（1.83.0）に解決されます。
- `BigtableEventStore.create` が同期のインターフェイスを、`createAsync` が非同期のインターフェイスを提供します。他の保存先と同じく、呼び出しスレッドで実行して完了済みの future を返します。

## 2. 規則・実装・試験

実装は `src/main/java/.../bigtable/` にあります。試験は `src/test/java/.../bigtable/` にあり、固定した Cloud Bigtable エミュレーター（`BigtableEmulator`）で実行します。Docker がなくても飛ばされる試験はありません。

| 規則 | 振る舞い | 実装 | 試験 | 適合ケース |
|---|---|---|---|---|
| BT-1 | すべての操作を 1 つのクラスタに向ける。呼び出し側のクライアントの設定で、ライブラリは確かめられない | ライブラリには該当なし（クライアントは呼び出し側が所有） | なし。スキーマ文書に書く | なし（データ上は対象外） |
| BT-2 | 列ファミリがあること。データの列ファミリはちょうど 1 版を残すこと。`config` に年齢の規則を付けないこと | `BigtableLayout` | `BigtableOpenTest`（列ファミリの欠落、年齢による GC、2 版以上） | `bigtable-seeded-*` |
| BT-3 / BT-4 / K-1 | 行キーは `H \|\| L16 \|\| A`、journal はその後ろに `U64BE(seq_nr)`。範囲は `prefix + 0xff` を使わない | `BigtableKey` | `BigtableKeyTest`、`BigtableHashCollisionTest`（ハッシュを衝突させる） | `hash-fnv1a64-*`、`aid-*` |
| BT-5 | journal の行は `event:envelope` がないときだけ、1 回の条件付き更新で作る。既にある行は照合し、retired の行はそのまま受け入れる | `BigtableAppend.writeJournalRow` | `BigtableAppendTest`、`BigtableRetentionTest.aDelayedWriterCannotReviveHistory…` | `core-*` |
| BT-6 | セルは時刻 0 で書く。置き換える列は、1 回の変更の中で削除して書き直す。条件は最新の 1 セルだけを見る | `BigtableRows`（`replaceCell`、`latestCellEquals`） | `BigtableAppendTest.afterEveryAppend…`、`aConditionThatMatchesOnlyAnOlderCellVersion…` | `core-*` |
| BT-7 | head を読み、直前のイベントを書き出し、head を条件付きで更新し（確定点）、新しい末尾を書き出す。応答が届かなければ保存先エラーで、再送しない | `BigtableAppend`、`BigtableRows.checkAndMutate` | `BigtableAppendTest`、`BigtableTransportTest.aLostConditionalUpdateResponseIsNeverResent…`、`BigtableConcurrencyTest` | `bigtable-previous-event-write-failure`、`bigtable-new-tail-write-failure`、`bigtable-cas-response-lost`、`core-*` |
| BT-8 | 最新のスナップショットと head の番号は、head の 1 行から得る | `BigtableRead.readSnapshot` | `BigtableReadTest` | `core-*` |
| BT-9 | 先に head を読んで `h` を使い続ける。`start >= h` では journal を読まない。範囲は `[start, h)` | `BigtableRead.readEvents` | `BigtableReadTest`（journal の行を消して読まないことを確かめる。読み取り中に head が進む場合） | `core-*` |
| BT-10 | クライアントが分割された行を組み立て、最後に完成した行の次から再開する。範囲の欠番は保存先エラー | `BigtableRead.readJournal` | `BigtableReadTest`（欠番、300 KB の行 12 件）、`BigtableTransportTest.anInterruptedReadRowsResumes…` | `core-storage-read-events` |
| BT-11 | 保持は行 `[1, h]` の `history:state` のセルを走査し、`h` を加える | `BigtableRetention` | `BigtableRetentionTest`、`BigtableHashCollisionTest` | `core-retention-*` |
| BT-12 | 候補の行は、`active` の間だけ成り立つ 1 回の条件付き更新で `retired` にする。イベントは残す | `BigtableRetention.retire` | `BigtableRetentionTest` | `core-retention-*` |
| BT-13 | 保持の失敗は確定した追記を変えない。ログに出し、リスナーへ 1 回通知する。ログやリスナーが `Error` を投げても隔離する。TTL は設定エラー | `BigtableEventStore`、`BigtableRetention`、`BigtableStorageConfig` | `BigtablePostCommitNotificationTest`（各試験が経路の呼び出しを確かめる）、`BigtableRetentionTest`、`BigtableStorageConfigTest` | `core-retention-failure-after-commit`、`core-retention-query-failure`、`bigtable-ttl-unsupported` |
| BT-14 / BT-15 | 変更フィード | 該当なし。BT-D8 により提供しない | なし | なし |
| BT-16 | 封筒の上限。head を読む前に検査する。両方の設定行がクライアントの上限と等しいこと | `BigtableAppend`、`BigtableLayout` | `BigtableAppendTest`（上限ちょうど、1 バイト超過、スナップショットの超過）、`BigtableOpenTest`、`BigtableStorageConfigTest` | `bigtable-envelope-over-limit`、`bigtable-envelope-limit-*`、`bigtable-seeded-*-envelope-limit-mismatch` |
| BT-17 | 変更フィードの要求は設定エラー | `BigtableStorageConfig` | `BigtableStorageConfigTest` | `bigtable-change-feed-unsupported` |
| BT-D1 / BT-D6a | open は照合だけを行う。封筒は固定のバイナリ形式 | `BigtableStorage`、`BigtableLayout`、`BigtableEnvelope` | `BigtableOpenTest`、`BigtableEnvelopeTest` | `bigtable-seeded-*`、`bigtable-envelope-*` |

必須の Bigtable 適合ケース 87 件は、実際のエミュレーターですべて合格し、失敗 0・未検証 0 です。`BigtableCaseRunnerTest` が全件を実行し、`ConformanceRunnerTest` が結果を `build/reports/conformance/report.json` の `bigtable-emulator` に書きます。DynamoDB の配置のケースや、他の保存先に固有のケースは Bigtable に該当しません。実行されない必須のケースがあるとビルドが失敗します。

## 3. 共通データの外にある規則

| 共通データの外の規則 | 証拠 |
|---|---|
| 保存形式: 2 つの表、行キー、封筒のバイト列が Rust の v5 の配置と等しく、その形の行を読み、続きを追記できる | `BigtableRustFormatTest`（行とハッシュは試験の中で組み立て、Java の本体から独立している）。`theHeadSeqNrIsTwentyAsciiDigitsWhateverTheDefaultLocale` と `anAsciiHeadWrittenByAnotherWriterIsUpdatedWhateverTheDefaultLocale` が、ASCII でない数字を既定とするロケールでも `head:seq_nr` が 20 桁の ASCII 数字であることを確かめる |
| 同時実行: 同じ head を読んだ 2 つの書き手のうち 1 つだけが確定する。`seq_nr=1` を並行に作ると 1 件だけ残る。書き出しと更新の間に割り込まれた書き手は負ける | `BigtableConcurrencyTest` |
| 要求の分割と再開: 分割された行を組み立て直す。途中で切れた `ReadRows` は、最後に完成した行の次から、欠けも重複もなく再開する | `BigtableReadTest.manyLargeRowsAreReassembled…`、`BigtableTransportTest.anInterruptedReadRowsResumes…` |
| 認証の差し込み（データと管理）: 呼び出し側の Bearer トークンが `GetTable`、`ReadRows`、`CheckAndMutateRow` に付く。認証の取得に失敗すると何も書かない | `BigtableTransportTest`（エミュレーターの前に置いた中継が、サーバー側で `authorization` を記録する） |
| 1 回だけ送る: 条件付き更新は再送せず、ライブラリは `MutateRow` を使わない | `BigtableTransportTest` |
| 公開の使用例: README の例を別のパッケージから公開型だけで実行し、`build/reports/public-api-usage/bigtable.json` を書く | `BigtableUserAccountExampleTest` |

## 4. エラーの分類

| 事象 | 分類 |
|---|---|
| head 以下の番号、条件が成り立たない head の更新 | 楽観ロックのエラー |
| head+1 でない番号（W-8）、上限を超える封筒（BT-16） | 契約違反 |
| 更新の応答の喪失、読み書きの失敗、不正な封筒や head の行、範囲の journal の行の欠落、Bigtable に届かない、認証の失敗 | 保存先エラー |
| 表や列ファミリの欠落、ちょうど 1 版を残さないデータの列ファミリ、設定行の欠落・不正・不一致、不正な設定、変更フィードや TTL の要求 | 設定エラー |
| 直列化・逆直列化の失敗 | 直列化エラー |

楽観ロックのメッセージには、aid、`seq_nr`、head を読んだ場合はその番号だけを含め、クライアントの文言や表 ID は含めません（E-2）。

## 5. 確かめられていないこと

次は、確認済みの成功には数えません。

| 項目 | 限界 | ほかの証拠 |
|---|---|---|
| 実サービスの挙動: 複数クラスタのルーティング、複製の遅れ、容量の上限、物理的なガベージコレクション | エミュレーターは再現しない | ガベージコレクションの規則と設定は照合する。物理的な削除は確かめない |
| 1 つのクラスタに固定した app profile（BT-1） | 呼び出し側の責任で、ライブラリからは見えない | 文書に書く |
| 変更フィード（BT-14、BT-15） | エミュレーターは `ReadChangeStream` を持たない。最初の版では提供しない（BT-D8） | 該当なし |
| 行の途中で切れた `ReadRows` | エミュレーターは 1 行を 1 つのメッセージで送るので、中継は行の間で切る | 再開した範囲が最初の範囲より前から始まらないこと、結果が完全で順序どおりであることを確かめる |
| 平文の通信路での認証ヘッダー | エミュレーター用のクライアントは平文の通信路を使う | 試験で、中継が記録した `authorization` ヘッダーとして観測する |
| 複数のエミュレーターのプロセスや、マシンをまたぐ実際の競合 | エミュレーターのプロセス 1 つ、JVM 1 つ | `BigtableConcurrencyTest` の 8 つの並行する書き手 |
