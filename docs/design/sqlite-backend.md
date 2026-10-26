# SQLite 保存先の設計記録

Java の SQLite 保存先が、ハブの SQLite プロファイル（共通契約 v5）の規則をどう実装し、何の試験で確かめているかを対応づける短い記録です。規範（ハブの `docs/spec`）と配布データ（`conformance/` 1.3.0）は変更していません。利用者向けの形式の説明は [SQLITE_DATABASE_SCHEMA](../SQLITE_DATABASE_SCHEMA.ja.md) にあります。

## 1. 公開型と接続の所有

- `SqliteStorageConfig`: 保持方式（既定 `RetentionPolicy.none()`）と書き込みロックの待ち時間（既定 5 秒）。TTL・null・負の値・`Integer.MAX_VALUE` ミリ秒超は `build()` が設定エラーにする。変更フィードを求める設定項目は持たない。
- `SqliteStorage`: 1 つのローカルファイルと、JDBC 接続を 1 本所有する。`initialize`（未初期化の空ファイルだけ）、`open`（既存ファイルを開いて照合。初期化はしない）、`close`（冪等）。各操作は `ReentrantLock` で直列化する。
- `SqliteEventStore`: `create`（同期）と `createAsync`（呼び出しスレッドで実行して完了済みの Future を返す。メモリ保存先と同じ形）。
- 利用者が `Connection` や `DataSource` を渡す形は採らない。共有キャッシュの無効化や pragma の確認を、すべての接続で保証するため。
- 公開 API に `java.sql` の型は出さない。依存 `org.xerial:sqlite-jdbc:3.53.4.0` は `implementation`。

## 2. 規則と実装・試験の対応

実装は `src/main/java/.../sqlite/`、試験は `src/test/java/.../sqlite/` などにあります（クラス名のみ記載）。適合ケース ID は `conformance/` のものです。

| 規則 | 内容 | 実装 | 試験 | 適合ケース |
|------|------|------|------|------------|
| SQ-1 | 専用キャッシュ、`locking_mode=NORMAL`・`journal_mode=WAL`・`synchronous=FULL`・`read_uncommitted=OFF`、待ち時間は設定 | `SqliteConnections` | `SqliteConnectionsTest`、`SqliteStorageConfigTest` | — |
| SQ-2 | 明示的な初期化と、開く操作の分離。開く時に config と配置を照合 | `SqliteStorage`、`SqliteLayout` | `SqliteInitializeTest`、`SqliteOpenTest` | `sqlite-seeded-*` |
| SQ-3 | 入力検査後、`occurred_at_ns` を符号付き 64 ビット整数で束縛・取得し、REAL を使わない | `SqliteAppend`、`SqliteRead`、`SqliteEventStore` | `SqliteAppendTest`（−1ns、`Long.MIN/MAX` ns、`typeof`） | `occurred-at-*` |
| SQ-4 | manifest と payload は別列。aid はパラメータで完全一致 | `SqliteAppend`、`SqliteRead` | `SqliteAppendTest`（`%`・`_` を含む aid）、`SqliteRustFormatTest` | `aid-*` |
| SQ-5 | 追記は 1 接続の 1 トランザクション。`BEGIN IMMEDIATE` を head 確認の前に開始 | `SqliteAppend`、`SqliteTransactions` | `SqliteConcurrencyTest` | `sqlite-write-lock-busy` |
| SQ-6 | `COMMIT` の成功が確定点。途中の失敗は `ROLLBACK`。`COMMIT` の失敗は保存先エラー | `SqliteAppend` | `SqliteAppendTest`（確定前の失敗で全表 0 行） | `core-*` の確定前の障害 |
| SQ-7 | BUSY・LOCKED・待ち時間超過は保存先エラー。journal と新規 head の主キー重複だけ楽観ロック | `SqliteAppend`、`SqliteTransactions` | `SqliteAppendTest`、`SqliteConcurrencyTest` | `sqlite-write-lock-busy`、`store-shared-persistent` |
| SQ-8 | 最新 snapshot は head と `snapshot_current` を同じ読み取りトランザクションで読む | `SqliteRead` | `SqliteReadTest` | `core-*` の読み取り |
| SQ-9 | イベント取得は head と journal を同じ読み取りトランザクションで読み、`start = max(1, seqNr)`。欠番は保存先エラー | `SqliteRead` | `SqliteReadTest` | `core-*` の読み取り |
| SQ-10 | snapshot 付き追記の確定後、別の `BEGIN IMMEDIATE` で古い履歴だけを削除 | `SqliteRetention`、`SqliteEventStore` | `SqliteRetentionTest` | `core-retention-*` |
| SQ-11 | 保持の失敗は通知し、追記の成功を変えない。TTL 方式と保持件数 0 は設定エラー | `SqliteRetention`、`SqliteStorageConfig` | `SqliteRetentionTest`、`SqliteStorageConfigTest` | `core-retention-failure-after-commit`、`sqlite-ttl-unsupported` |
| SQ-12 | 変更フィード提供時の追記（head_transition） | 対象外（フィードを提供しない。SQ-D8） | — | — |
| SQ-13 | 変更フィードの購読（草案） | 対象外（同上） | — | — |
| SQ-14 | すべての接続で WAL と FULL を設定して読み直す。違えば設定エラー | `SqliteConnections` | `SqliteConnectionsTest` | — |
| SQ-15 | 実際に使う SQLite の版が 3.51.3 以上。各部分を整数で比べる | `SqliteConnections.meetsMinimumVersion` | `SqliteConnectionsTest`、`SqliteDriverSmokeTest` | — |
| SQ-16 | すべてのテーブルを STRICT にし、宣言を照合 | `SqliteLayout` | `SqliteInitializeTest`、`SqliteOpenTest` | — |
| SQ-17 | 追記は `BEGIN IMMEDIATE`、読み取りは明示的な読み取りトランザクション | `SqliteAppend`、`SqliteRead` | `SqliteConcurrencyTest`、`SqliteReadTest` | — |
| SQ-18 | 変更フィード提供時の head_transition の保持 | 対象外（同上） | — | — |
| SQ-19 | 保持件数を config と照合する。食い違いは設定エラー | `SqliteLayout.verify` | `SqliteOpenTest` | `sqlite-seeded-keep-count-mismatch`、`sqlite-seeded-layout-version-mismatch`、`sqlite-seeded-configuration-match` |
| SQ-20 | 変更フィードを提供しない。`change_feed_enabled` は 0 で記録し、1 のストアは開けない。head_transition を作らない | `SqliteLayout` | `SqliteInitializeTest`、`SqliteOpenTest` | `sqlite-seeded-change-feed-enabled`、`sqlite-change-feed-unsupported` |

適合データの実行では、必須 67 件が実ファイルで成功し、失敗 0・未検証 0 です。`sqlite-change-feed-unsupported` は、公開設定に変更フィードを求める項目がなく `change_feed=true` を表せないため「表現不能」とし、必須には入れていません。DynamoDB の配置のケースは SQLite に該当せず対象外です。結果は `build/reports/conformance/report.json` にあります。

## 3. テーブルと列の型

5 つのテーブルをすべて `STRICT` で作ります。列の型と制約は Rust 実装と同じです。

| テーブル | 列と型（`NOT NULL` は記載） | 主キー |
|----------|------------------------------|--------|
| `journal` | `aid` TEXT（BINARY）、`seq_nr` INTEGER（1〜2^53−1）、`occurred_at_ns` INTEGER、`manifest` TEXT、`payload` BLOB。すべて `NOT NULL` | `(aid, seq_nr)` |
| `head` | `aid` TEXT（BINARY）、`type_name` TEXT、`seq_nr` INTEGER（1〜2^53−1）、`event_occurred_at_ns` INTEGER、`event_manifest` TEXT、`event_payload` BLOB。すべて `NOT NULL` | `aid` |
| `snapshot_current` | `aid` TEXT（BINARY）、`seq_nr` INTEGER（1〜2^53−1）、`manifest` TEXT、`payload` BLOB。すべて `NOT NULL` | `aid` |
| `snapshot_history` | `snapshot_current` と同じ列 | `(aid, seq_nr)` |
| `config` | `singleton` INTEGER（=1）、`store_id` TEXT、`layout_version` INTEGER、`keep_snapshot_count` INTEGER（NULL か 1 以上）、`retention_mode` TEXT（`delete`）、`change_feed_enabled` INTEGER（0 か 1）。`keep_snapshot_count` 以外は `NOT NULL` | `singleton` |

`head_transition` は作りません。

## 4. 設定の照合とエラー分類

開く時に、1 つの読み取りトランザクションで次を照合します。DDL の文字列ではなく、pragma の意味（`pragma_table_list` の `strict`、`pragma_table_info`、主キー索引の `pragma_index_xinfo` の照合順序）で比べます。

| 照合項目 | 不一致の扱い |
|----------|--------------|
| 5 つのテーブルが存在し、STRICT であること | 設定エラー |
| 各テーブルの列の名前・型・`NOT NULL`・主キー内の位置 | 設定エラー |
| `aid` を持つ 4 つのテーブルの主キー索引の `aid` が BINARY 照合 | 設定エラー |
| `config` がちょうど 1 行 | 設定エラー |
| `layout_version` が 1 | 設定エラー |
| `store_id` が小文字の UUID | 設定エラー |
| `retention_mode` が `delete` | 設定エラー |
| `change_feed_enabled` が 0（1 は「変更フィードは提供しない」） | 設定エラー |
| `keep_snapshot_count` がクライアントの保持件数と同じ（NULL は保持なし） | 設定エラー |
| 未初期化のファイル | 設定エラー（表は作らない） |
| ファイルが存在しない | 保存先エラー（ファイルは作らない） |

実行時のエラー分類は次のとおりです。

| 事象 | 分類 |
|------|------|
| ヘッド以下の番号、journal・新規 head の主キー重複 | 楽観ロック |
| head + 1 以外の番号（飛び番、W-8） | 契約違反 |
| 書き込みロックの待ち時間超過、BUSY、LOCKED、その他の制約違反、COMMIT の失敗 | 保存先エラー |
| 範囲内の journal の欠番 | 保存先エラー |
| 直列化・復元の失敗 | 直列化エラー |
| TTL 方式、不正な待ち時間、`?` を含むファイル名、版が古い、接続設定の食い違い | 設定エラー |

## 5. SQLite 本体の最低版

最低版は **3.51.3** です。開いた接続で `SELECT sqlite_version()` を取得し、`.` で 3 つに分けて各部分を整数で比べます（"3.100.0" は 3.51.3 以上、"3.51" は偽）。下回る場合と、形式が不正な場合は設定エラーです。同梱するドライバー `sqlite-jdbc 3.53.4.0` の SQLite の版は、`SqliteUserAccountExampleTest` が書く `build/reports/public-api-usage/sqlite.json` の `sqlite_version` で実測できます（この作業で実測した値は 3.53.4）。

## 6. 接続の設定

| 設定 | 値 |
|------|----|
| 開き方 | 共有キャッシュなし（専用キャッシュ）。存在しないファイルは作らない（`initialize` だけが作る） |
| ファイル名 | 絶対パスにする。`?` を含む名前は拒否する |
| `busy_timeout` | `lockWait`（既定 5 秒）のミリ秒 |
| `locking_mode` | NORMAL |
| `journal_mode` | WAL |
| `synchronous` | FULL（2） |
| `read_uncommitted` | OFF（0） |
| 自動確定 | 有効のまま、`BEGIN IMMEDIATE`／`BEGIN`／`COMMIT`／`ROLLBACK` を SQL で明示する |

設定したあとで pragma を読み直し、食い違いは設定エラーにします。

## 7. 保持方式

- 方式は `delete` だけです。`none()` では履歴行を作らず、`delete(n)` では新しい `n` 件の履歴を残します。
- 期限切れ方式（TTL）は設定エラーです。
- 保持は、snapshot 付きの追記が `COMMIT` された後に、別の `BEGIN IMMEDIATE` トランザクションで実行します。journal・head・現在の snapshot は削除しません。
- 保持の失敗は確定済みの追記の成功を変えず、WARN ログと任意の通知先へ 1 回通知します（ロック解放後）。残った履歴は次の snapshot 付き追記で整理します。イベントだけの追記では保持を走らせません。

## 8. 検証できないもの（成功とは扱わない）

| 項目 | 理由 | 代わりに行っていること |
|------|------|------------------------|
| 保存先が `fsync` を守ること、電源断、プロセスの強制終了、長時間の複数プロセスの実行 | プロファイル 10 章が未検証と定める。この作業では再現できない | なし。未検証として残す |
| 3.51.3 そのもの、および 3.51.3 未満の実ライブラリでの拒否 | 同梱の 1 つの版しか動かせない | 版の比較の単体試験（`SqliteConnectionsTest`） |
| pragma の読み直しが食い違う状況 | 実 SQLite で再現する条件がない | 判定関数 `verifySettings` の単体試験 |
| 主キー句で照合順序を上書きして、列宣言だけ別の照合順序にした場合 | JDBC から列メタデータを取れず、主キー索引の照合順序だけを確認している | 主キー索引が NOCASE の拒否の試験 |
| ネットワークファイルシステムの検出 | 検出する手段がない | 文書で使わないよう案内するだけ |
| Rust 実装の `origin/main`（c29f305）との差 | 計画の段階では別リポジトリを読めず、ローカルの `main`（fe2ae0b）を参照した。11 コミットの差の内容は確認していない | 保存形式は Rust の DDL と INSERT の形を定数にした `SqliteRustFormatTest` で確認 |
| 変更フィード（SQ-12・SQ-13・SQ-18） | 最初の版で提供しない（SQ-D8） | 対象外 |
