# DynamoDBのスキーマ

`com.github.j5ik2o.event.store.adapter.java.dynamodb` が使う配置です。成果物版は `2.0.0-SNAPSHOT`、共通契約はv4、配布適合データは1.0.0です。保存する `layout_version` は **1** で、これらとは別の配置番号です。

## テーブルの準備

公開factoryを呼ぶ前に、同じリージョンに3表を作ります。名前とsnapshotのGSI名を `DynamoDbTableConfig` に渡します。ライブラリは設定項目を初期化しますが、テーブル作成は行いません。

| 表 | パーティションキー | ソートキー | GSI | Streams | TTL |
|----|--------------------|------------|-----|---------|-----|
| journal | `aid` (S) | `seq_nr` (N) | なし | 無効 | 無効 |
| snapshot | `aid` (S) | `skey` (N) | PK `aid` (S)、SK `active_history_seq_nr` (N)、射影 **KEYS_ONLY** | 無効 | 属性 `ttl`。TTL保持時だけ有効 |
| head | `aid` (S) | なし | なし | 有効、**NEW_IMAGE** | 無効 |

`CreateTable` の `AttributeDefinitions` は、journalが `aid`・`seq_nr`、snapshotが `aid`・`skey`・`active_history_seq_nr`、headが `aid` だけです。それ以外の項目属性はキーの属性定義に含めません。[独立した試験用テーブル定義](../src/test/java/com/github/j5ik2o/event/store/adapter/java/dynamodbtest/DynamoDbConfigurationTables.java)に、実行可能な利用試験が使うSDK要求があります。

`aid` は `AggregateId.of(typeName, value).asString()` の結果、つまり `typeName + "-" + value` です。型名にハイフンは使えず、値には使えます。全体でUTF-8の1024バイト以下にします。論理シャード・ハッシュ・キーresolverは使いません。

通常の操作は1集約IDに絞り、Scanを使いません。journalと設定・snapshot読取りは強整合、履歴GSI読取りだけが結果整合です。headの変更はStreamsに残りますが、購読・再同期の補助はライブラリに含みません。

## 設定項目

3表に1件ずつ置きます。

| 表 | キー |
|----|------|
| journal | `aid(S)="__config__"`、`seq_nr(N)=0` |
| snapshot | `aid(S)="__config__"`、`skey(N)=0` |
| head | `aid(S)="__config__"` |

キー以外の属性は `store_id` (S) と `layout_version` (N) だけです。3項目で生成された `store_id` が一致し、`layout_version=1` である必要があります。snapshotの設定項目には `active_history_seq_nr` と `ttl` を付けません。

factoryは3項目を強整合の `BatchGetItem` で読み、応答を蓄積し、`UnprocessedKeys` だけを再要求します。全キーの処理が終わるまで不存在と判定しません。3項目ともない場合、`attribute_not_exists(aid)` の条件付きPutを1トランザクションで実行します。並行生成の条件不成立・トランザクション競合では、全項目を強整合で1度読み直します。読み直してもすべてなければ `StorageException` です。

一部だけ存在する設定、store IDの不一致、未対応の配置番号は `ConfigurationException` です。通信失敗・再要求上限到達は `StorageException` です。異なる保存先の表を組み合わせません。

## 項目の全属性

S・N・B・L・MはDynamoDBの文字列・数値・バイナリ・リスト・マップです。

| 項目 | 全属性 |
|------|--------|
| journalイベント | `aid` (S)、`seq_nr` (N)、`occurred_at` (N)、`manifest` (S)、`payload` (B) |
| 現在snapshot | `aid` (S)、`skey` (N、0)、`seq_nr` (N)、`manifest` (S)、`payload` (B)、`last_updated_at` (N) |
| 有効な履歴snapshot | 現在snapshotと同じ属性で `skey=seq_nr`、加えて `active_history_seq_nr` (N、`seq_nr` と同じ) |
| TTL印付き履歴snapshot | 履歴属性から `active_history_seq_nr` を除き、`ttl` (N) を追加 |
| head | `aid` (S)、`type_name` (S)、`seq_nr` (N)、`events` (L) |

headの `events` は直前に確定したイベントを表すMを1要素だけ持ちます。Mの全属性は `seq_nr` (N)・`occurred_at` (N)・`manifest` (S)・`payload` (B) です。

- `occurred_at` は利用者が渡すイベント時刻のエポック**ナノ秒**で、符号付き64bit整数です。Javaの `Instant` を使い、読取りでも同じ時刻を返します。
- `last_updated_at` は同じイベント時刻のエポック**ミリ秒**です。`Instant.toEpochMilli()` で、負の小数ミリ秒は床関数で求めます。
- `ttl` は期限のエポック**秒**です。印なし履歴・現在snapshot・設定項目は属性自体を持ちません。
- `seq_nr` は0〜2^53−1の整数です。イベントは1から始めます。
- `manifest` の省略時は空文字列で、解釈せず保存します。
- `payload` はドメインpayloadまたは集約状態だけを直列化したBです。封筒メタデータを埋め込みません。既定はJSONのバイト列です。

現在snapshotは `active_history_seq_nr` と `ttl` を持ちません。履歴保持なしなら現在snapshotだけを書き、保持件数があれば現在と履歴を同じ確定で書きます。疎なGSIには有効な履歴だけが入ります。

## ストア設定

```java
DynamoDbTableConfig tables = DynamoDbTableConfig.builder()
    .journalTableName("example-journal")
    .snapshotTableName("example-snapshot")
    .headTableName("example-head")
    .snapshotAidIndexName("example-history")
    .retentionPolicy(RetentionPolicy.delete(2))
    .configurationReadRetryLimit(10)
    .build();
```

| 設定 | 意味 |
|------|------|
| 3表とGSIの名前 | 作成済み資源の必須・非空白の名前 |
| `retentionPolicy` | 既定は `none()`。`delete(n)`・`ttl(n, graceSeconds)` のnは1以上 |
| `configurationReadRetryLimit` | 既定10。初回を除く再要求回数。最新snapshotの未処理キー再要求にも適用。0は再要求なし、負は設定エラー |
| `clock` | 既定 `Clock.systemUTC()`。TTL印付けの時刻取得に使用 |
| `EventStoreConfig` | 必須のpayload・snapshotシリアライザと、任意の保持失敗通知先 |

TTL猶予は非負の整数秒です。期限は印付け時刻＋猶予で、符号付きlongの桁あふれを避けて計算します。保存する設定項目は保存先IDと配置番号だけで、シリアライザ・保持設定は含めません。

## 確定・保持・失敗

追記は1回の `TransactWriteItems` です。journal Put、head Put/Update、必要に応じ現在・履歴snapshot Putを含みます。新規作成はhead不存在、それ以降は `head.seq_nr = event.seqNr() - 1` を条件にします。journal Putは同じキーの不存在を条件にします。headの条件不成立には分類用の `ALL_OLD` を求めます。snapshotのversion属性・version引数はありません。同時に書くイベントとsnapshotの番号を一致させます。全項目を送信前に409600バイト上限で検査します。

DynamoDBの保持はsnapshot書込み確定後だけ実行し、公開呼出し・futureの成功前に完了します。降順GSIの全頁を読み、直前に書いた履歴を重複なしで加え、最新n件の有効履歴を残します。別集約・journal・head・現在snapshot・設定項目は変更しません。

- DELETEは `BatchWriteItem` で古い履歴を最大25件ずつ削除します。未処理削除だけを再送し、再送上限は10回です。
- TTLは1件ずつ `SET #ttl = :expires REMOVE active_history_seq_nr` で印を付けます。`#ttl` を `ttl` に束縛し、条件は `attribute_exists(active_history_seq_nr)` です。印付きの条件不成立は読み飛ばします。印付き履歴は件数外で、期限を延長しません。物理削除はDynamoDB TTLが行います。

最終保持失敗でも確定済み書込みは成功します。WARNログと任意listenerへ、集約ID・方式・元原因を1回通知します。取り残しは後のsnapshot書込みで再び対象になります。同期通知は呼出しスレッド、非同期通知は公開future完了前のSDK完了スレッドで行うため、通知先はブロックしない実装にします。通知先の例外でも書込み成功を維持します。

## 読取りと復元

`getLatestSnapshotById` はheadと現在snapshotを強整合の `BatchGetItem` で読み、未処理キーを再要求します。組は原子的ではありません。旧headと新snapshot、新headと旧snapshot、再要求をまたいだ混在はいずれも合法で、観測した組を返します。headがなければ空、headだけあれば `snapshot()` が空の結果を返します。

復元起点はsnapshotありなら `snapshot.seqNr() + 1`、なしなら1で、イベントを昇順に適用します。新しいsnapshotをheadに合わせて切り詰めたり、headを起点・上限にしたりしません。`getEventsByIdSinceSeqNr` はjournalを `aid = :aid AND seq_nr >= :seq_nr` で読み、全頁を消費します。取得済みsnapshotに含まれる番号を再適用しないための起点です。

公開factoryは [README](../README.ja.md)、旧データを独立した新3表へ書き直す手順は [MIGRATION_GUIDE](../MIGRATION_GUIDE.md)を参照してください。
