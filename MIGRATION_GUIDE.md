# Java 1.xから新公開APIへの移行

現在の成果物は `2.0.0-SNAPSHOT` です。共通契約の呼称v4、配布適合データの版1.0.0、DynamoDBの `layout_version=1` は別の番号です。

Javaでは手順書による移行を提供します。**旧版で読む→利用者がpayloadと封筒へ変換→新版へ連続して書き直す**流れです。新版は旧公開型・旧internal・旧保存形式の自動読取り・互換alias・移行ツールを提供しません。

For Java 1.x users: read with the old library and your domain types, convert the data in your application, then rewrite consecutive events through the new public API into separate new tables. The new library does not automatically read the old layout or infer missing type information or timestamp precision. Executable examples are linked from the [English README](README.md).

## APIの対応

新しい型は `com.github.j5ik2o.event.store.adapter.java.core`、保存先は `memory` と `dynamodb` にあります。直下の旧公開パッケージと旧 `internal` は削除しています。

| 1.x | 新API・扱い |
|-----|------------|
| `EventStore<AID,A,E>` | `core.EventStore<P,A>` |
| `EventStoreAsync<AID,A,E>` | `core.AsyncEventStore<P,A>` |
| `EventStore.ofDynamoDB`、`EventStoreAsync.ofDynamoDB` | `DynamoDbEventStore.create`、`createAsync`。3表とsnapshot GSI名を指定 |
| 旧実装のみのDynamoDB入口 | Memoryは `MemoryEventStore.create/createAsync` と `MemoryStorage.create` で利用 |
| 利用者が実装する `AggregateId` | `core.AggregateId.of(typeName, value)`。型名と値からライブラリが文字列を組み立てる |
| `Aggregate`、`Event` | 実装要件を削除。ドメインpayload・状態を `EventEnvelope<P>`、`SnapshotEnvelope<A>` で包む |
| `AggregateAndEvent` | 削除。イベントとsnapshotの封筒を別々に渡す |
| `EventStoreOptions` と可変の `with...` 設定 | `EventStoreConfig` のビルダー、`RetentionPolicy`、`DynamoDbTableConfig` または `MemoryStorage` |
| `EventSerializer`、`SnapshotSerializer` と既定実装 | `PayloadSerializer<T>`、`JsonPayloadSerializer`。生成時に型を束縛 |
| `KeyResolver`、`DefaultKeyResolver`、shard数、journal GSI名 | 削除。aidを直接PKに使い、journalは `(aid, seq_nr)` をQuery |
| `persistEvent(event, version)` | `persistEvent(EventEnvelope<P>)`。照合の期待値は `event.seqNr()-1` |
| `persistEventAndSnapshot(event, aggregate)` | `persistEventAndSnapshot(EventEnvelope<P>, SnapshotEnvelope<A>)`。両番号を一致させる |
| `getLatestSnapshotById(Class<A>, id)` | `getLatestSnapshotById(id)`。`Optional<SnapshotReadResult<A>>` を返す |
| `getEventsByIdSinceSequenceNumber(Class<E>, id, seq)` | `getEventsByIdSinceSeqNr(id, seq)`。封筒を昇順に全件返す |
| 旧base例外、read/write例外、検査・非検査の対、`DeserializationException` | `EventStoreException` と5つの非検査例外。復元失敗も `SerializationException` |

新しい5分類は `OPTIMISTIC_LOCK`、`CONTRACT_VIOLATION`、`SERIALIZATION`、`CONFIGURATION`、`STORAGE` です。例外型または `category()` を使い、メッセージから推測しません。非同期のfutureの包みは `EventStoreExceptions.unwrap(error)` で外せます。契約違反は `rule()` と `seqNr()` を持ちます。

Memoryの非同期factoryは `AsyncEventStore` を直接返します。DynamoDBの非同期factoryは設定照合の通信を行うため `CompletableFuture<AsyncEventStore<P,A>>` を返します。生成futureの成功後に4操作を呼びます。SDK clientは利用者が所有・終了します。

## payloadと封筒

1.xの既定形式はドメインイベント・集約全体をJSONにし、ID・version・時刻などを含んでいました。新シリアライザはpayloadまたは集約状態だけを直列化し、aid・番号・時刻・manifestを別に保存します。利用者の実際の型とシリアライザに従って分離してください。ライブラリはmanifestを解釈しません。

`AggregateId.of(typeName, value)` の型名にハイフンは使えず、値には使えます。UTF-8で全体1024バイト以下です。旧IDの文字列から型を推測せず、利用者が確認できる型名と値の対応を用意します。

イベント番号は1から始め、以後連続させます。重複・head以下は楽観ロック、飛び番は契約違反です。snapshotと一緒に書く場合は同じ番号の封筒を渡します。旧versionは新しい照合条件へ移しません。

`occurredAt` は利用者が渡す `Instant` です。保存された時刻の実際の単位・精度を確認して変換します。旧データがミリ秒だけを保存している場合は、その値を正確に表す `Instant` にし、失われたナノ秒を補いません。欠けた型・ID・時刻を現在時刻や推測値で置き換えません。

## 旧データを書き直す手順

1. 利用者が旧保存先への書込みを制御し、読取り対象を確定させます。旧データと対応情報を確認できる状態を保持します。
2. 旧版のライブラリと利用者の既存ドメイン型・シリアライザで、旧イベントを集約ごとに最後まで読みます。旧snapshotを利用する場合は、その番号と状態を確認します。
3. 利用者が新aidへの対応、payload、manifest、時刻、連続したイベント番号を確認・変換します。1から全イベントを連続して書けることを先に確認します。欠番や失われた情報があれば、推測・自動補完をせず、利用者が実データに基づいて解決してから進めます。
4. [スキーマ文書](docs/DATABASE_SCHEMA.ja.md)に従って独立した新しいjournal・snapshot・headの3表とsnapshot GSIを作ります。旧2表と新3表は混在させません。TTL方式ならsnapshot表の `ttl` を有効にします。
5. 新しいserializer設定と `DynamoDbTableConfig` を使って公開factoryを呼びます。空の3表には、factoryが同じ `store_id` と `layout_version=1` の設定項目を作ります。
6. 集約ごとに番号1から昇順・連続に `persistEvent` します。変換したsnapshotを保存する場合は、同じ番号のイベントを追記するときに `persistEventAndSnapshot` を使います。snapshotだけを別に書くAPIはありません。旧version・旧キー・旧JSONをそのまま書きません。
7. 新APIで全イベントとsnapshotを読み戻し、確認済みの変換データ・番号・時刻・manifest・復元状態と比較します。書込み失敗を空結果で隠さず、分類されたエラーを確認します。
8. 利用者が書直しと利用側の確認を終えた後に読書き先を切り替えます。旧表の削除も確認後に利用者が行います。この手順書は削除を自動実行しません。

途中で失敗した場合、すでに確定した番号を無条件に再追記すると楽観ロックになります。新APIで実保存を確認し、利用者が続行位置を決めます。新版ライブラリに旧形式のfallbackを追加して続行しません。

## snapshotからの復元

外側の空結果はhead不存在です。結果がありsnapshotだけが空なら、番号1からイベントを読みます。snapshotがあれば **`snapshot.seqNr()+1`** から読み、状態へ昇順に適用します。`headSeqNr()` を起点・上限にしません。

Memoryはsnapshotとheadを原子的に読みます。DynamoDBは各項目を強整合で非原子的に読むため、snapshotがheadより古い組も新しい組も合法です。取得したsnapshotをheadへ切り詰めず、snapshotに含まれた番号を再適用しません。イベント読取りは全頁を読み、後から確定したイベントを含むことがあります。

## 保持と通知の変更

既定は `RetentionPolicy.none()` で現在snapshotだけです。履歴保持は `delete(n)`、DynamoDBの期限付き保持は `ttl(n, graceSeconds)` を明示します。nは1以上、猶予は非負の整数秒です。MemoryはTTLを設定エラーにします。

DynamoDBはsnapshot書込み確定後だけ保持を行い、Memoryはイベントだけの追記でも取り残しを再試行します。TTLの印付き履歴は保持件数外で、期限を延長しません。保持はjournal・head・現在snapshotを削除しません。

確定後の保持失敗は書込み成功を変えず、最終失敗をWARNと任意の `retentionFailureListener` へ1回通知します。通知には集約ID・方式・元原因があります。Memoryはロック解除後、同期実装とMemory非同期は呼出しスレッド、DynamoDB非同期は公開future完了前のSDK完了スレッドで通知します。通知先をブロックさせず、通知先の例外でも確定済み書込みの成功を維持します。

公開factory・コンパイル可能なサンプル・実利用試験は [README](README.ja.md)、実3表と全属性は [DATABASE_SCHEMA](docs/DATABASE_SCHEMA.ja.md)を参照してください。
