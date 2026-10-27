> この文書は [SPANNER_DATABASE_SCHEMA.md](SPANNER_DATABASE_SCHEMA.md) の日本語版です。英語版が正本で、食い違うときは英語版に従います。

# Spanner の保存先のスキーマ

`com.github.j5ik2o.event.store.adapter.java.spanner` が使う配置です。`5.0.1` には含まれず、次の版から使えます。[共通契約 v5](https://github.com/j5ik2o/event-store-adapter/blob/main/docs/spec/README.md) と [Spanner プロファイル](https://github.com/j5ik2o/event-store-adapter/blob/main/docs/spec/storage/spanner.md) に従います。適合データは 1.3.0 です。保存する `layout_version` は **1** で、保存形式の別の番号です。JavaScript の v5 実装と同じ配置のテーブルを使います。

公開型は [README](../README.ja.md)、版の違いは [MIGRATION_GUIDE](MIGRATION_GUIDE.ja.md) を参照してください。設計記録は [spanner-backend](design/spanner-backend.ja.md) です。

## 配置

この節は、`SpannerStorage.open(...)` が前提にするテーブルを説明します。

対象は GoogleSQL 方言だけです。5 つのテーブルは同じデータベースに置きます。テーブル名はすべて 1 つの接頭辞から `<接頭辞>_<論理名>` として導きます。接頭辞が `esa` なら、`esa_journal`・`esa_head`・`esa_snapshot_current`・`esa_snapshot_history`・`esa_config` です。接頭辞と、導いた各テーブル名は SQL の識別子として正しく、導いた名前は 128 文字以内でなければなりません。

`SpannerStorage.open(...)` は DDL を実行しません。テーブルと設定の行を照合するだけで、欠けているものを作りません。テーブルと設定の行は、下の手順で利用者が作ります。例の接頭辞は `esa` です。自分の接頭辞に置き換えてください。

```sql
CREATE TABLE esa_journal (
  hash_prefix BYTES(8) NOT NULL,
  aid STRING(1024) NOT NULL,
  seq_nr INT64 NOT NULL,
  occurred_at_ns INT64 NOT NULL,
  manifest STRING(MAX) NOT NULL,
  payload BYTES(MAX) NOT NULL
) PRIMARY KEY (hash_prefix, aid, seq_nr);

CREATE TABLE esa_head (
  hash_prefix BYTES(8) NOT NULL,
  aid STRING(1024) NOT NULL,
  type_name STRING(1024) NOT NULL,
  seq_nr INT64 NOT NULL,
  event_occurred_at_ns INT64 NOT NULL,
  event_manifest STRING(MAX) NOT NULL,
  event_payload BYTES(MAX) NOT NULL
) PRIMARY KEY (hash_prefix, aid);

CREATE TABLE esa_snapshot_current (
  hash_prefix BYTES(8) NOT NULL,
  aid STRING(1024) NOT NULL,
  seq_nr INT64 NOT NULL,
  manifest STRING(MAX) NOT NULL,
  payload BYTES(MAX) NOT NULL
) PRIMARY KEY (hash_prefix, aid);

CREATE TABLE esa_config (
  singleton INT64 NOT NULL,
  store_id STRING(36) NOT NULL,
  layout_version INT64 NOT NULL,
  keep_snapshot_count INT64,
  retention_mode STRING(8) NOT NULL,
  ttl_grace_days INT64,
  change_feed_enabled BOOL NOT NULL
) PRIMARY KEY (singleton);
```

`esa_snapshot_history` は保持方式によって変わります。次の 2 つの定義のどちらか 1 つを使います。

delete 方式は、古いスナップショット履歴の行をすぐに削除します。行削除ポリシーはありません。

```sql
CREATE TABLE esa_snapshot_history (
  hash_prefix BYTES(8) NOT NULL,
  aid STRING(1024) NOT NULL,
  seq_nr INT64 NOT NULL,
  manifest STRING(MAX) NOT NULL,
  payload BYTES(MAX) NOT NULL,
  expired_at TIMESTAMP
) PRIMARY KEY (hash_prefix, aid, seq_nr);
```

TTL 方式は、古い行に印を付け、後で Spanner の TTL に削除させます。行削除ポリシーが猶予の日数を持ちます。`7` は例であり、既定値ではありません。

```sql
CREATE TABLE esa_snapshot_history (
  hash_prefix BYTES(8) NOT NULL,
  aid STRING(1024) NOT NULL,
  seq_nr INT64 NOT NULL,
  manifest STRING(MAX) NOT NULL,
  payload BYTES(MAX) NOT NULL,
  expired_at TIMESTAMP
) PRIMARY KEY (hash_prefix, aid, seq_nr),
  ROW DELETION POLICY (OLDER_THAN(expired_at, INTERVAL 7 DAY));
```

## 設定

`esa_config` には、`singleton = 1` の行をちょうど 1 件だけ置きます。

- `store_id`: 小文字の UUID 文字列です。
- `layout_version`: `INT64` の `1` です。
- `keep_snapshot_count`: スナップショット履歴を残さないなら `NULL` です。残すなら正の整数で、全クライアントの `SpannerRetentionPolicy` の保持件数と同じ値にします。
- `retention_mode`: `delete` または `ttl` です。
- `ttl_grace_days`: TTL 方式では正の日数、それ以外は `NULL` です。
- `change_feed_enabled`: この版は変更フィードを提供しないので、`FALSE` で記録します。`TRUE` の行は設定エラーになります。

スナップショット履歴を残さない delete 方式では、`keep_snapshot_count` を `NULL`、`retention_mode` を `'delete'` にします。TTL 方式は保持件数が必要なので、`keep_snapshot_count` は正の整数にします。

delete 方式で、集約ごとに新しい履歴 3 件を残す例です。

```sql
INSERT INTO esa_config (singleton, store_id, layout_version, keep_snapshot_count, retention_mode, ttl_grace_days, change_feed_enabled)
VALUES (1, GENERATE_UUID(), 1, 3, 'delete', NULL, FALSE);
```

TTL 方式で、新しい履歴 3 件を残し、猶予を 7 日にする例です。`7` は、行削除ポリシーの `INTERVAL 7 DAY` と、クライアントの `SpannerRetentionPolicy.ttl` の `graceDays` と同じ値にします。

```sql
INSERT INTO esa_config (singleton, store_id, layout_version, keep_snapshot_count, retention_mode, ttl_grace_days, change_feed_enabled)
VALUES (1, GENERATE_UUID(), 1, 3, 'ttl', 7, FALSE);
```

## 初期化

この手順は、アプリケーションの外で、どのクライアントも書き込んでいない間に行います。

1. ストアの書き手をすべて止めます。
2. 上の DDL を、自分の接頭辞で実行し、完了を待ちます。`esa_snapshot_history` は、保持方式に合う定義を使います。
3. 5 つのテーブルがそれぞれ空であることを確かめます。

   ```sql
   SELECT COUNT(*) FROM esa_journal;
   SELECT COUNT(*) FROM esa_head;
   SELECT COUNT(*) FROM esa_snapshot_current;
   SELECT COUNT(*) FROM esa_snapshot_history;
   SELECT COUNT(*) FROM esa_config;
   ```

4. 上の対応する `INSERT` で、設定の行を 1 件書きます。
5. 書き手を動かします。`SpannerStorage.open(...)` が配置を照合します。

照合する項目に欠けや食い違いがあれば、`open` は `ConfigurationException` を投げます。照合する項目は次のとおりです。データベースが GoogleSQL 方言であること。5 つのテーブルの列の名前と型、主キー。設定の行（小文字の UUID の `store_id`、`layout_version` が 1、`keep_snapshot_count` がクライアントの `SpannerRetentionPolicy` の保持件数と一致、`retention_mode`、`ttl_grace_days`、`change_feed_enabled` が `FALSE`）。`esa_snapshot_history` の行削除ポリシー（TTL 方式では猶予の日数と一致、delete 方式ではなし）。欠けたものを直すことはしません。

ストアの使用中の DDL の変更や、ライブラリを通さないデータの変更は、対象外です。

## 記録

列の意味は次のとおりです。

- `hash_prefix`: `aid` の FNV-1a 64 のハッシュを、8 バイトのビッグエンディアンにした値です。符号付き整数への読み替えや、シャード数での剰余は使いません。キーは `(hash_prefix, aid)` の組の完全一致で比べます。ハッシュが衝突しても、`aid` が異なれば別のキーです。
- `aid`: `typeName-value` の形です。UTF-8 で 1024 バイト以下であることを、ライブラリが書き込みの前に検査します。
- `seq_nr`: 1 から 2^53 - 1 です。
- `occurred_at_ns`・`event_occurred_at_ns`: Unix エポックからの符号付き 64 ビットのナノ秒を `INT64` で保存します。`TIMESTAMP` 型にはしません。
- `esa_snapshot_history.expired_at`: TTL のために行へ印を付けた時刻です。イベントの発生時刻ではありません。印のない行は `NULL` のままで、TTL は削除しません。
- `esa_head` は、最新の確定イベントとそのメタデータ・payload を持ちます。`esa_snapshot_current` は最新のスナップショットを持ち、スナップショットを一度も書いていない集約には行がありません。

## 書き込みと読み取り

書き込みは SERIALIZABLE の読み書きトランザクションで行います。トランザクションが ABORTED になったときは、`SpannerStorageConfig.Builder.transactionRetryLimit` 回（既定は 5）までやり直します。最初の試行は数えません。

トランザクションの中で、まず集約の head の `seq_nr` を読みます。新しいイベントの番号が head の番号以下なら `OptimisticLockException`、head + 1 より大きい番号なら `ContractViolationException`（rule は `W-8`）です。そうでなければ、journal の行と head の行（最初のイベントでは追加、以後は更新）を 1 つのトランザクションで確定します。スナップショットを伴う書き込みでは、`esa_snapshot_current` の行の追加または更新も同じトランザクションで行い、保持を設定していれば `expired_at` を `NULL` にした `esa_snapshot_history` の行も追加します。`occurred_at_ns` は、イベントの時刻の Unix エポックからのナノ秒をそのまま保存した値です。JavaScript 実装が書いた行は、エポックミリ秒に 1,000,000 を掛けた値で、ナノ秒として読みます。

イベントの読み取りは 1 つの strong の読み取り専用トランザクションで行います。先に head の番号を読み、`seq_nr >= 開始番号 AND seq_nr <= head` を昇順に読みます。行の数が範囲と合わなければ `StorageException` で失敗します。最新のスナップショットは、head の行と `esa_snapshot_current` の行を 1 つの strong の読み取り専用トランザクションで読みます。

## 保持

| | delete | TTL |
|:--|:--|:--|
| クライアントの設定 | `SpannerRetentionPolicy.delete(count)`（履歴なしは `none()`） | `SpannerRetentionPolicy.ttl(count, graceDays)` |
| `retention_mode` | `delete` | `ttl` |
| `ttl_grace_days` | `NULL` | `graceDays` |
| `esa_snapshot_history` の行削除ポリシー | なし | `OLDER_THAN(expired_at, INTERVAL graceDays DAY)` |
| 古い履歴の行 | スナップショットの書き込みの直後に削除する | `expired_at` を設定して 1 回だけ印を付け、後で Spanner の TTL が削除する |

TTL 方式では、`INTERVAL n DAY` の `n`、`ttl_grace_days`、`graceDays` の 3 つを同じ値にします。印のある行は保持件数に数えず、印付けの対象に選び直しません。物理的な削除は、Spanner の都合で後から行われます。対象は `esa_snapshot_history` だけで、`esa_journal`・`esa_head`・`esa_snapshot_current` は削除しません。

保持の処理は、スナップショットの書き込みが確定した後に、1 回に 500 行以下の別々のトランザクションで行います。トランザクションごとに対象を選び直します。保持の処理が失敗しても、確定済みの書き込みは変わりません。失敗は WARN でログに残し、任意の `EventStoreConfig.Builder.retentionFailureListener` に 1 回だけ通知します。

## 運用

### 保持件数の変更

`keep_snapshot_count` の変更は、すべてのクライアントを止めて、ライブラリの外で行う保守の手順です。ライブラリは、この手順のためのツールを提供しません。

TTL 方式は保持件数を必要とするので、TTL 方式では変更後の件数も正の整数でなければなりません。TTL 方式のストアを履歴なし（`NULL`）にする変更と、`retention_mode` の変更は、この手順の対象外です。

1. ストアを使うすべてのクライアントを止め、進行中の書き込み・読み取り・保持の処理が終わったことを確かめます。変更前と変更後の設定、および進み具合を、再開に使える場所に記録します。
2. 履歴の行がある `aid` ごとに、SERIALIZABLE の読み書きトランザクションで、`expired_at IS NULL` の行を `seq_nr` の降順に読みます。変更後の件数が `n` なら新しい `n` 件を残し、履歴を残さない（`NULL`。delete 方式だけ）なら、印のない行をすべて対象にします。delete 方式では、対象の行を `DELETE` します。TTL 方式では、`expired_at IS NULL` の対象の行だけに `expired_at` を `CURRENT_TIMESTAMP()` に設定します。すでに印のある行とその時刻は変えません。大きな整理は複数のトランザクションに分け、そのたびに対象を選び直します。`esa_journal`・`esa_head`・`esa_snapshot_current` には触りません。
3. すべての集約の整理が終わってから、`esa_config` の `keep_snapshot_count` を更新します。TTL 方式では、印付けが終わっていることが条件で、物理的な削除は待ちません。そのあとクライアントを再起動し、それぞれが新しい設定を照合します。

途中で失敗したときは、クライアントを止めたまま、手順 1 で記録した同じ変更後の設定で、もう一度行います。削除済みの行と印付け済みの行は処理し直さず、期限も先送りしません。ストア全体を元に戻す手段はありません。件数を増やすときや、件数のなかった状態から設定するとき（delete 方式だけ）も、削除済み・印付け済み・保持していなかった過去の行は、復活も補って作ることもしません。新しい設定は、次のスナップショットの書き込みから適用されます。
