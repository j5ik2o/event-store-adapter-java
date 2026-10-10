# event-store-adapter-java

[![CI](https://github.com/j5ik2o/event-store-adapter-java/actions/workflows/ci.yml/badge.svg)](https://github.com/j5ik2o/event-store-adapter-java/actions/workflows/ci.yml)
[![Maven Central](https://maven-badges.herokuapp.com/maven-central/io.github.j5ik2o/event-store-adapter-java/badge.svg)](https://maven-badges.herokuapp.com/maven-central/io.github.j5ik2o/event-store-adapter-java)
[![Renovate](https://img.shields.io/badge/renovate-enabled-brightgreen.svg)](https://renovatebot.com)
[![License](https://img.shields.io/badge/License-MIT-blue.svg)](https://opensource.org/licenses/MIT)
[![tokei](https://tokei.rs/b1/github/j5ik2o/event-store-adapter-java)](https://github.com/XAMPPRocky/tokei)

CQRS/Event Sourcing向けのイベントストアです。MemoryとDynamoDBを提供し、Java 11以降で利用できます。

[English](README.md)

## 依存と公開API

現在の成果物版は `2.0.0-SNAPSHOT` です。共通契約の呼称 **v4**、配布適合データの版 **1.0.0** とは別の番号です。

```kotlin
repositories {
    mavenCentral()
    maven { url = uri("https://central.sonatype.com/repository/maven-snapshots/") }
}
dependencies {
    implementation("io.github.j5ik2o:event-store-adapter-java:2.0.0-SNAPSHOT")
}
```

| パッケージ | 公開型 |
|------------|--------|
| `com.github.j5ik2o.event.store.adapter.java.core` | ID、封筒、シリアライザ、設定、例外、`EventStore<P,A>`、`AsyncEventStore<P,A>` |
| `com.github.j5ik2o.event.store.adapter.java.memory` | `MemoryStorage`、`MemoryEventStore` |
| `com.github.j5ik2o.event.store.adapter.java.dynamodb` | `DynamoDbTableConfig`、`DynamoDbEventStore` |

ドメイン型には、`PayloadSerializer<T>` で直列化できることだけを求めます。payloadとsnapshotの型は `EventStoreConfig` の生成時に束縛し、読取り操作に `Class<T>` を渡しません。既定のJSONシリアライザはpayloadまたは集約状態だけを直列化し、封筒のメタデータと分けて保存します。ライブラリは `manifest` を解釈しません。

## Memoryで保存・復元する

以下は名前をイベントpayloadと集約状態に使う例です。[UserAccountExample](src/test/java/com/github/j5ik2o/event/store/adapter/java/examples/UserAccountExample.java)に、封筒の生成と同期・非同期の復元関数を含むコンパイル可能な実装があります。リポジトリ外で実行するときは、このサンプルもコピーしてください。

```java
import com.github.j5ik2o.event.store.adapter.java.core.*;
import com.github.j5ik2o.event.store.adapter.java.memory.*;
import com.github.j5ik2o.event.store.adapter.java.examples.UserAccountExample;
import java.util.Optional;

EventStoreConfig<String, String> config = UserAccountExample.config();
MemoryStorage storage = MemoryStorage.create();
EventStore<String, String> store = MemoryEventStore.create(storage, config);
AggregateId id = AggregateId.of("UserAccount", "example-1");

store.persistEvent(UserAccountExample.event(id, 1, "Alice"));
store.persistEventAndSnapshot(
    UserAccountExample.event(id, 2, "Bob"), UserAccountExample.snapshot(2, "Bob"));
Optional<String> name = UserAccountExample.restore(store, id); // Bob

AsyncEventStore<String, String> asyncStore = MemoryEventStore.createAsync(storage, config);
asyncStore.persistEvent(UserAccountExample.event(id, 3, "Carol")).join();
Optional<String> updated = UserAccountExample.restoreAsync(asyncStore, id).join(); // Carol
```

同じ `MemoryStorage` オブジェクトを渡したストアだけが、記録・設定・排他制御を共有します。`MemoryStorage.create()` ごとに独立した、プロセス内だけの保存先を作ります。Memoryの非同期APIは、呼出しスレッドで完了済みfutureを返します。

両保存先が提供する操作は4つです。

| 操作 | 結果 |
|------|------|
| `persistEvent(event)` | イベントを1件追記 |
| `persistEventAndSnapshot(event, snapshot)` | イベントとsnapshotを同時に確定 |
| `getLatestSnapshotById(id)` | snapshot封筒とhead番号の組、または空 |
| `getEventsByIdSinceSeqNr(id, start)` | `start` 以上のイベント全件を昇順で返す |

番号1は集約の新規作成で、snapshotなしでも作れます。それ以降はheadの直後の番号を使います。重複・head以下は楽観ロック、飛び番は契約違反です。同時に書くイベントとsnapshotの番号は一致させます。

## DynamoDBのfactory

[DATABASE_SCHEMA](docs/DATABASE_SCHEMA.ja.md)に従って3表とsnapshotのGSIを用意します。設定済みSDK clientは利用者が所有・終了します。factoryは3表の設定項目を照合・初期化し、テーブル作成は行いません。

```java
import com.github.j5ik2o.event.store.adapter.java.dynamodb.*;
import java.util.concurrent.CompletableFuture;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;

DynamoDbTableConfig tables = DynamoDbTableConfig.builder()
    .journalTableName("example-journal")
    .snapshotTableName("example-snapshot")
    .headTableName("example-head")
    .snapshotAidIndexName("example-history")
    .retentionPolicy(RetentionPolicy.none())
    .build();

// clientとasyncClientは利用者が所有する、設定済みSDK client。
EventStore<String, String> dynamo = DynamoDbEventStore.create(client, tables, config);
CompletableFuture<AsyncEventStore<String, String>> creation =
    DynamoDbEventStore.createAsync(asyncClient, tables, config);

AggregateId dynamoId = AggregateId.of("UserAccount", "dynamodb-example");
dynamo.persistEvent(UserAccountExample.event(dynamoId, 1, "Alice"));
Optional<String> saved = UserAccountExample.restore(dynamo, dynamoId);

CompletableFuture<Optional<String>> restored = creation.thenCompose(s ->
    s.persistEventAndSnapshot(
        UserAccountExample.event(dynamoId, 2, "Bob"),
        UserAccountExample.snapshot(2, "Bob"))
     .thenCompose(ignored -> UserAccountExample.restoreAsync(s, dynamoId)));
Optional<String> latest = restored.join(); // Bob
```

DynamoDBの非同期生成には設定照合の通信があるため、factoryはfutureを返します。生成futureの成功後にストアを利用します。

## 復元と整合性

`getLatestSnapshotById` の外側の `Optional` が空ならheadがありません。結果があり `snapshot()` が空なら、snapshotなしで集約が存在します。復元はsnapshotなしなら **1**、ありなら **`snapshot.seqNr() + 1`** から始めます。`headSeqNr()` を復元起点やイベント読取りの上限にしません。

Memoryはheadとsnapshotを原子的に読みます。DynamoDBは各項目を強整合で読みますが、組は**非原子的**です。snapshotがheadより古い組も、新しい組も、未処理キー再要求をまたいだ混在も合法です。取得したsnapshotを保持し、その番号より後のイベントだけを適用して、適用済み番号を再適用しません。イベント読取りは全ページを消費し、後から確定した並行追記を含むことがあります。

## 保持・失敗・通知

既定の `RetentionPolicy.none()` は現在snapshotだけを保存します。`RetentionPolicy.delete(n)` は現在snapshotに加えて最新n件の履歴を残します。DynamoDBは `RetentionPolicy.ttl(n, graceSeconds)` も提供し、snapshot表の `ttl` 属性でTTLを有効にします。印付き履歴は保持件数に数えず、期限を延長しません。MemoryはTTL設定を拒否し、変更フィードを提供しません。

保持方針は `MemoryStorage.create(policy)` または `DynamoDbTableConfig` に渡します。DynamoDBはsnapshot書込みの確定後に保持を行い、Memoryはイベントだけの追記でも残った履歴の処理を再試行します。保持はjournal・head・現在snapshotを削除しません。

確定後の保持失敗でも書込みは成功します。最終失敗はWARNログと、任意の `EventStoreConfig.Builder.retentionFailureListener` に1回通知します。通知には集約ID・方式・元原因があり、Memoryはロック解除後に通知します。同期実装とMemory非同期は呼出しスレッド、DynamoDB非同期は公開future完了前のSDK完了スレッドで通知します。通知先はブロックしない実装にします。通知先の失敗でも、確定済み書込みを失敗にしません。

操作・生成のエラーは `ErrorCategory` の5分類です。`OPTIMISTIC_LOCK`、`CONTRACT_VIOLATION`、`SERIALIZATION`、`CONFIGURATION`、`STORAGE` を例外型または `category()` で判別します。非同期では `EventStoreExceptions.unwrap(error)` でfutureの包みを外します。契約違反は `rule()` と `seqNr()` を公開します。

## 移行と実行可能な試験

APIの変更と「旧版で読む→利用者が変換→新版へ書き直す」手順は [MIGRATION_GUIDE](MIGRATION_GUIDE.md)を参照してください。

[Memory利用試験](src/test/java/com/github/j5ik2o/event/store/adapter/java/examples/UserAccountExampleTest.java)と[DynamoDB利用試験](src/test/java/com/github/j5ik2o/event/store/adapter/java/dynamodbtest/DynamoDbPublicApiUsageTest.java)は、この例を4つの公開factoryでコンパイル・実行します。DynamoDB試験は固定されたDynamoDB Localイメージを使うためDockerが必要です。

```sh
./gradlew spotlessCheck test
```

## ライセンス

MITライセンスです。[LICENSE](LICENSE)を参照してください。

## 他の言語のための実装

- [for Java](https://github.com/j5ik2o/event-store-adapter-java)
- [for Scala](https://github.com/j5ik2o/event-store-adapter-scala)
- [for Kotlin](https://github.com/j5ik2o/event-store-adapter-kotlin)
- [for Rust](https://github.com/j5ik2o/event-store-adapter-rs)
- [for Go](https://github.com/j5ik2o/event-store-adapter-go)
- [for JavaScript/TypeScript](https://github.com/j5ik2o/event-store-adapter-js)
- [for .NET](https://github.com/j5ik2o/event-store-adapter-dotnet)
- [for PHP](https://github.com/j5ik2o/event-store-adapter-php)
