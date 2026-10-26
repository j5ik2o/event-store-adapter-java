> この文書は [SQLITE_DATABASE_SCHEMA.md](SQLITE_DATABASE_SCHEMA.md) の日本語版です。英語版が正本で、食い違うときは英語版に従います。

# SQLite の保存先のスキーマ

`com.github.j5ik2o.event.store.adapter.java.sqlite` が使う配置です。`5.0.1` には含まれず、次の版から使えます。[共通契約 v5](https://github.com/j5ik2o/event-store-adapter/blob/main/docs/spec/README.md) と [SQLite プロファイル](https://github.com/j5ik2o/event-store-adapter/blob/main/docs/spec/storage/sqlite.md) に従います。適合データは 1.3.0 です。保存する `layout_version` は **1** で、保存形式の別の番号です。Rust 実装と同じ配置のファイルを使います。

公開型は [README](../README.ja.md)、版の違いは [MIGRATION_GUIDE](MIGRATION_GUIDE.ja.md) を参照してください。日本語の設計記録は [sqlite-backend](design/sqlite-backend.md) です。

## 配置

### テーブル

1 つの SQLite ファイルに 5 つのテーブルがあります。すべて `STRICT` で、欠けているテーブルや `STRICT` でないテーブルがあると、開く時の照合が失敗します。変更フィード用のテーブルは作りません。

| テーブル | 主キー | 列（記載のない限り `NOT NULL`） |
|----------|--------|----------------------------------|
| `journal` | `(aid, seq_nr)` | `aid` TEXT、`seq_nr` INTEGER、`occurred_at_ns` INTEGER、`manifest` TEXT、`payload` BLOB |
| `head` | `aid` | `aid` TEXT、`type_name` TEXT、`seq_nr` INTEGER、`event_occurred_at_ns` INTEGER、`event_manifest` TEXT、`event_payload` BLOB |
| `snapshot_current` | `aid` | `aid` TEXT、`seq_nr` INTEGER、`manifest` TEXT、`payload` BLOB |
| `snapshot_history` | `(aid, seq_nr)` | `aid` TEXT、`seq_nr` INTEGER、`manifest` TEXT、`payload` BLOB |
| `config` | `singleton` | `singleton` INTEGER、`store_id` TEXT、`layout_version` INTEGER、`keep_snapshot_count` INTEGER（NULL 可）、`retention_mode` TEXT、`change_feed_enabled` INTEGER |

`aid` は `AggregateId.of(typeName, value).asString()` そのものを `COLLATE BINARY` で宣言し、完全一致で比べます。常にパラメータとして束縛し、`LIKE` や前方一致では比べません。すべての `seq_nr` に `CHECK (seq_nr BETWEEN 1 AND 9007199254740991)` があります。

### 接続の設定

すべての接続は共有キャッシュを使わず（専用キャッシュで）開き、設定したあとで読み直して確認します。`locking_mode=NORMAL`、`journal_mode=WAL`、`synchronous=FULL`、`read_uncommitted=OFF`、`busy_timeout` はロックの待ち時間と同じ値です。設定どおりに読み直せない値は設定エラーです。同梱の SQLite は **3.51.3 以上**が必要で、3 つの整数として比べます。古いライブラリは設定エラーです。ファイル名は絶対パスにし、`?` を含む名前は接続パラメータとして解釈されないよう拒否します。

## 設定

### ストア設定

```java
SqliteStorageConfig settings = SqliteStorageConfig.builder()
    .retentionPolicy(RetentionPolicy.delete(2))
    .lockWait(Duration.ofSeconds(5))
    .build();
```

| 設定 | 意味 |
|------|------|
| `retentionPolicy` | 既定は `none()`。`delete(n)` は `n >= 1`。TTL は設定エラー |
| `lockWait` | 既定は 5 秒。0 以上、`Integer.MAX_VALUE` ミリ秒以下 |
| `EventStoreConfig` | payload と snapshot のシリアライザは必須。保持失敗の通知先は任意 |

設定は、ファイルに触れる前に `build()` で検査します。

### ファイルに記録する設定

`config` 行は `keep_snapshot_count`（保持なしは NULL）と `retention_mode`（`delete`）を記録します。開く時にクライアントの設定と比べ、保持件数がどちら向きに違っても設定エラーです。変更フィードの設定項目はなく、`change_feed_enabled` が 1 のファイルは開けません。

## 初期化

### ファイルの初期化

`SqliteStorage.initialize(file, settings)` は、新規または空のファイルを 1 つの `BEGIN IMMEDIATE` トランザクションで初期化します。5 つのテーブルを作り、小文字の UUID の `store_id`、`layout_version=1`、保持件数、`retention_mode='delete'`、`change_feed_enabled=0` の `config` 行を 1 つ入れます。初期化済みのファイルや他のテーブルがあるファイルは設定エラーで、内容は変わりません。親ディレクトリがないときは保存先エラーです。

### ファイルを開く

`SqliteStorage.open(file, settings)` はファイルを作らず、初期化もしません。ファイルがないときは保存先エラーです。1 つの読み取りトランザクションの中で、初期化済みであること、各テーブルが `STRICT` で列・型・`NOT NULL`・主キーが期待どおりであること、`aid` の主キーが `BINARY` 照合であること、`config` がちょうど 1 行で有効（`layout_version=1`、小文字 UUID の `store_id`、`retention_mode='delete'`、`change_feed_enabled=0`、保持件数がクライアントと同じ）であることを確かめます。違いがあれば設定エラーで、接続を閉じます。

## 記録

### 列の値

- `occurred_at_ns` と `event_occurred_at_ns` はイベントのエポックからの**ナノ秒**で、符号付き 64 ビットの INTEGER です。REAL や文字列にしません。
- `seq_nr` は 1 から 2^53−1 までの整数です。
- `manifest` の既定は空文字列で、解釈せずに保存します。
- `payload` と `event_payload` には、直列化したドメインの payload または集約状態だけを BLOB で入れます（既定は JSON のバイト列）。
- `head` は集約ごとの最新の確定イベント、`snapshot_current` は最新の snapshot を持ちます。`snapshot_history` は保持件数を設定したときだけ書きます。

## 書き込みと読み取り

### 追記

追記は接続のロックを取り、`BEGIN IMMEDIATE` で head を読む前に書き込みロックを取ります。head を読んだあと、次のとおりです。

- head 以下の番号は楽観ロック失敗です。
- head + 1 以外の番号は契約違反（W-8）です。
- それ以外は journal 行を挿入し、head を挿入または更新し、`snapshot_current` を upsert し、保持があれば `snapshot_history` に挿入して `COMMIT` します。

`COMMIT` が確定点です。それより前の失敗はロールバックします。journal または新しい head の主キーの衝突は楽観ロック失敗です。`lockWait` を超えた書き込みロックの待ち、`SQLITE_BUSY`、`SQLITE_LOCKED`、その他の制約違反は保存先エラーです。`COMMIT` の失敗は結果が不明な保存先エラーで、イベントの番号を振り直さないでください。

### 読み取りと復元

`getLatestSnapshotById` は head と `snapshot_current` を同じ読み取りトランザクションで読むため、組は一貫しています。`getEventsByIdSinceSeqNr` は head と、`max(1, start)` から head までの journal 行を 1 つの読み取りトランザクションで読みます。その範囲に欠けた行があれば、短い結果ではなく保存先エラーです。head より先の journal 行は返しません。

復元は `snapshot.seqNr() + 1` から、snapshot がなければ 1 から始めます。

## 保持

### 保持の実行

保持は、snapshot 付きの追記が確定したあとに、別の `BEGIN IMMEDIATE` トランザクションで行います。新しい `n` 件の履歴行を残し、古いものを削除します。journal・head・現在の snapshot は変更しません。失敗しても確定済みの追記は成功のままで、WARN ログと任意の通知先へ 1 回通知します。残ったものは次の snapshot 書き込みで整理します。

## 運用

### ファイルとプロセス

1 つのホスト上のローカルファイルを使います。同じプロセスまたは複数のプロセスの複数の `SqliteStorage` が同じファイルを共有でき、書き込みは最大 `lockWait` の間、書き込みロックを待ちます。ネットワークファイルシステムは使わないでください。WAL モードではメインファイルだけでは完全な複製になりません。メインファイルだけをコピーせず、SQLite のバックアップ機能を使ってください。使い終わったら `SqliteStorage` を閉じてください。

### 検証していないこと

プロセスの強制終了、電源断、保存先が `fsync` を守ること、長時間の複数プロセスの実行は、試験では検証していません。一覧と理由は [sqlite-backend](design/sqlite-backend.md) を参照してください。
