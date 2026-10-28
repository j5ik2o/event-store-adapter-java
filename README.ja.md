> この文書は [README.md](README.md) の日本語版です。英語版が正本で、食い違うときは英語版に従います。

# event-store-adapter-java

[![CI](https://github.com/j5ik2o/event-store-adapter-java/actions/workflows/ci.yml/badge.svg?branch=main&event=push)](https://github.com/j5ik2o/event-store-adapter-java/actions/workflows/ci.yml?query=branch%3Amain)
[![Maven Central](https://img.shields.io/maven-central/v/io.github.j5ik2o/event-store-adapter-java.svg)](https://central.sonatype.com/artifact/io.github.j5ik2o/event-store-adapter-java)
[![javadoc](https://img.shields.io/maven-central/v/io.github.j5ik2o/event-store-adapter-java.svg?label=javadoc)](https://javadoc.io/doc/io.github.j5ik2o/event-store-adapter-java)
[![Java](https://img.shields.io/badge/Java-11%2B-blue.svg)](https://github.com/j5ik2o/event-store-adapter-java/blob/main/build.gradle.kts)
[![Contract v5](https://img.shields.io/badge/contract-v5-blueviolet.svg)](https://github.com/j5ik2o/event-store-adapter/tree/contract-v5/docs/spec)
[![Storages](https://img.shields.io/badge/storages-memory%20%7C%20DynamoDB%20%7C%20SQLite%20%7C%20Spanner%20%7C%20Bigtable-informational.svg)](https://github.com/j5ik2o/event-store-adapter/tree/main/docs/spec/storage)
[![Renovate](https://img.shields.io/badge/renovate-enabled-brightgreen.svg)](https://renovatebot.com)
[![License](https://img.shields.io/badge/license-MIT%20OR%20Apache--2.0-blue.svg)](https://github.com/j5ik2o/event-store-adapter-java#license)

CQRS/Event Sourcing向けのイベントストアです。Memory、DynamoDB、SQLite、Spanner、Bigtableを提供し、Java 11以降で利用できます。

[English](README.md)

このリポジトリを変更する開発者は、[CONTRIBUTING](CONTRIBUTING.ja.md) を参照してください。

## 概要

### バージョン

現在の成果物版は `5.0.1` で、Maven Central に公開しています。[共通契約 v5](https://github.com/j5ik2o/event-store-adapter/blob/main/docs/spec/README.md) に従います。メジャーの番号 5 は、共通契約の版と同じです。配布適合データの版は **1.3.0** で、別の番号です。

2.0.0 は版をそろえる決定の前に出した版で、中身は 5.0.0 と同じです。5.0.1 以降を使ってください。

### 公開パッケージ

| パッケージ | 公開型 |
|------------|--------|
| `com.github.j5ik2o.event.store.adapter.java.core` | ID、封筒、シリアライザ、設定、例外、`EventStore<P,A>`、`AsyncEventStore<P,A>` |
| `com.github.j5ik2o.event.store.adapter.java.memory` | `MemoryStorage`、`MemoryEventStore` |
| `com.github.j5ik2o.event.store.adapter.java.dynamodb` | `DynamoDbTableConfig`、`DynamoDbEventStore` |
| `com.github.j5ik2o.event.store.adapter.java.sqlite` | `SqliteStorageConfig`、`SqliteStorage`、`SqliteEventStore` |
| `com.github.j5ik2o.event.store.adapter.java.spanner` | `SpannerStorageConfig`、`SpannerRetentionPolicy`、`SpannerStorage`、`SpannerEventStore` |
| `com.github.j5ik2o.event.store.adapter.java.bigtable` | `BigtableStorageConfig`、`BigtableStorage`、`BigtableEventStore` |

### リンク

- [共通文書](https://github.com/j5ik2o/event-store-adapter)
- [for Java](https://github.com/j5ik2o/event-store-adapter-java)
- [for Scala](https://github.com/j5ik2o/event-store-adapter-scala)
- [for Kotlin](https://github.com/j5ik2o/event-store-adapter-kotlin)
- [for Rust](https://github.com/j5ik2o/event-store-adapter-rs)
- [for Go](https://github.com/j5ik2o/event-store-adapter-go)
- [for JavaScript/TypeScript](https://github.com/j5ik2o/event-store-adapter-js)
- [for .NET](https://github.com/j5ik2o/event-store-adapter-dotnet)
- [for PHP](https://github.com/j5ik2o/event-store-adapter-php)

## インストール

```kotlin
repositories {
    mavenCentral()
}
dependencies {
    implementation("io.github.j5ik2o:event-store-adapter-java:5.0.1")
}
```

## はじめに

### Memoryで保存・復元する

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

### 操作

すべての保存先が提供する操作は4つです。

| 操作 | 結果 |
|------|------|
| `persistEvent(event)` | イベントを1件追記 |
| `persistEventAndSnapshot(event, snapshot)` | イベントとsnapshotを同時に確定 |
| `getLatestSnapshotById(id)` | snapshot封筒とhead番号の組、または空 |
| `getEventsByIdSinceSeqNr(id, start)` | `start` 以上のイベント全件を昇順で返す |

番号1は集約の新規作成で、snapshotなしでも作れます。それ以降はheadの直後の番号を使います。重複・head以下は楽観ロック、飛び番は契約違反です。同時に書くイベントとsnapshotの番号は一致させます。

### 復元と整合性

`getLatestSnapshotById` の外側の `Optional` が空ならheadがありません。結果があり `snapshot()` が空なら、snapshotなしで集約が存在します。復元はsnapshotなしなら **1**、ありなら **`snapshot.seqNr() + 1`** から始めます。`headSeqNr()` を復元起点やイベント読取りの上限にしません。

MemoryとSQLiteはheadとsnapshotを原子的に読みます。SQLiteは両方を同じ読み取りトランザクションで、Spannerは両方を同じstrongの読み取り専用トランザクションで、Bigtableは両方をheadの1行から読みます。DynamoDBは各項目を強整合で読みますが、組は**非原子的**です。snapshotがheadより古い組も、新しい組も、未処理キー再要求をまたいだ混在も合法です。取得したsnapshotを保持し、その番号より後のイベントだけを適用して、適用済み番号を再適用しません。イベント読取りは全ページを消費し、後から確定した並行追記を含むことがあります。

## 保存先

### メモリ

同じ `MemoryStorage` オブジェクトを渡したストアだけが、記録・設定・排他制御を共有します。`MemoryStorage.create()` ごとに独立した、プロセス内だけの保存先を作ります。Memoryの非同期APIは、呼出しスレッドで完了済みfutureを返します。メモリの保存先は単一プロセスのメモリだけを使います。ファイルへの保存、再起動後の回復、プロセス間の共有はしません。

### DynamoDB

[DYNAMODB_DATABASE_SCHEMA](docs/DYNAMODB_DATABASE_SCHEMA.ja.md)に従って3表とsnapshotのGSIを用意します。設定済みSDK clientは利用者が所有・終了します。factoryは3表の設定項目を照合・初期化し、テーブル作成は行いません。3 つの表は同じリージョンに置き、書き込みもそのリージョンからだけ行ってください。グローバルテーブル（複数リージョンのレプリカ）は範囲外です。

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

### SQLite

SQLiteは、同じホスト上のローカルファイル1つにイベントを保存します。5.0.1には含まれず、次の版から使えます。新規または空のファイルは `SqliteStorage.initialize` で一度だけ初期化し、`SqliteStorage.open` で開きます。開く操作はファイルを初期化しません。ファイルがないときは `STORAGE` エラー、未初期化または設定と一致しないときは `CONFIGURATION` エラーで失敗します。`SqliteStorage` はJDBC接続を1本所有し、閉じる必要があります。同じファイルを、同じプロセスまたは同じホストの複数プロセスの複数の `SqliteStorage` から使えます。各オブジェクトは自分の操作を直列化します。`lockWait`（既定5秒）以内に書き込みロックを取れない書込みは `STORAGE` エラーで失敗し、何も確定しません。SQLiteの非同期APIはロック待ちを含めて呼出しスレッドで実行し、完了済みのfutureを返します。ファイルをネットワークファイルシステムに置かないでください。ファイルの形式と運用は [SQLITE_DATABASE_SCHEMA](docs/SQLITE_DATABASE_SCHEMA.ja.md) を参照してください。

```java
import com.github.j5ik2o.event.store.adapter.java.sqlite.*;
import java.nio.file.Path;
import java.time.Duration;

Path file = Path.of("example-events.db");
SqliteStorageConfig settings = SqliteStorageConfig.builder()
    .retentionPolicy(RetentionPolicy.none())
    .lockWait(Duration.ofSeconds(5))
    .build();
SqliteStorage.initialize(file, settings); // once, for a new or empty file
try (SqliteStorage sqlite = SqliteStorage.open(file, settings)) {
  EventStore<String, String> sqliteStore = SqliteEventStore.create(sqlite, config);
  AsyncEventStore<String, String> asyncSqlite = SqliteEventStore.createAsync(sqlite, config);
  AggregateId sqliteId = AggregateId.of("UserAccount", "sqlite-example");
  sqliteStore.persistEvent(UserAccountExample.event(sqliteId, 1, "Alice"));
  asyncSqlite.persistEventAndSnapshot(
      UserAccountExample.event(sqliteId, 2, "Bob"), UserAccountExample.snapshot(2, "Bob")).join();
  Optional<String> sqliteName = UserAccountExample.restore(sqliteStore, sqliteId); // Bob
}
```

### Spanner

Spannerは、GoogleSQL方言のCloud Spannerデータベース1つの5つの表にイベントを保存します。5.0.1には含まれず、次の版から使えます。5つの表と設定の行は、[SPANNER_DATABASE_SCHEMA](docs/SPANNER_DATABASE_SCHEMA.ja.md) のDDLで利用者が作成します。ライブラリはDDLを実行しません。`SpannerStorage.open(DatabaseClient, SpannerStorageConfig)` は配置を照合するだけです。表・列・主キー・設定の行・行削除ポリシーの欠落や不一致は `CONFIGURATION` エラー、データベースを読めないときは `STORAGE` エラーになります。`DatabaseClient`（認証やエミュレーターの接続先を含む）は利用者が作って所有し、ライブラリは閉じません。書込みはSERIALIZABLEの読み書きトランザクションで行います。トランザクションが中断（ABORTED）されたときは、同じ封筒で `transactionRetryLimit` 回（既定5回、初回は数えない）まで再試行し、超えると `STORAGE` エラーになります。コミットの応答を失ったときは、成否不明の `STORAGE` エラーになります。同じ封筒を送り直すと、最初のコミットが通っていれば `OPTIMISTIC_LOCK` エラーになります。読み取りはstrongの読み取り専用トランザクション1つで行います。保持方針は `SpannerRetentionPolicy.none()`、`delete(n)`、`ttl(n, graceDays)` です。猶予は日数で数え、設定の行の `ttl_grace_days` と行削除ポリシーの `INTERVAL n DAY` と同じ値にします。非同期のSpanner APIは呼出しスレッドで実行し、完了済みのfutureを返します。変更フィードは提供せず、`SpannerStorageConfig.Builder.changeFeed(true)` は `CONFIGURATION` エラーになります。表はJavaScriptのv5実装と同じ配置です。

```java
import com.github.j5ik2o.event.store.adapter.java.spanner.*;
import com.google.cloud.spanner.DatabaseClient;

DatabaseClient client = spanner.getDatabaseClient(DatabaseId.of("project", "instance", "database"));
SpannerStorageConfig settings = SpannerStorageConfig.builder()
    .tablePrefix("esa") // 表は esa_journal、esa_head、esa_snapshot_current など
    .retentionPolicy(SpannerRetentionPolicy.none())
    .transactionRetryLimit(5)
    .build();
SpannerStorage storage = SpannerStorage.open(client, settings); // 配置の照合だけを行う
EventStore<String, String> spannerStore = SpannerEventStore.create(storage, config);
AsyncEventStore<String, String> asyncSpanner = SpannerEventStore.createAsync(storage, config);
AggregateId spannerId = AggregateId.of("UserAccount", "spanner-example");
spannerStore.persistEvent(UserAccountExample.event(spannerId, 1, "Alice"));
asyncSpanner.persistEventAndSnapshot(
    UserAccountExample.event(spannerId, 2, "Bob"), UserAccountExample.snapshot(2, "Bob")).join();
Optional<String> spannerName = UserAccountExample.restore(spannerStore, spannerId); // Bob
```

### Bigtable

Bigtableは、Cloud Bigtableの1つのインスタンスの2つの表（journal表とhead表）にイベントを保存します。5.0.1には含まれず、次の版から使えます。2つの表、列ファミリ、ガベージコレクションの規則は、[BIGTABLE_DATABASE_SCHEMA](docs/BIGTABLE_DATABASE_SCHEMA.ja.md) のコマンドで利用者が作成し、その後 `BigtableStorage.initialize(BigtableDataClient, BigtableStorageConfig)` を1回呼んで、同じ設定の行を両方の表に書きます。ライブラリは表を作りません。`BigtableStorage.open(BigtableDataClient, BigtableTableAdminClient, BigtableStorageConfig)` は配置を照合するだけです。表・列ファミリ・ガベージコレクションの規則・設定の行の欠落や不一致は `CONFIGURATION` エラー、Bigtableに届かないときは `STORAGE` エラーになります。2つのクライアント（認証、1つのクラスタに固定したapp profile、エミュレーターの接続先を含む）は利用者が作って所有し、ライブラリは閉じません。追記は、headの行の条件付き更新1回で確定します。直前のイベントはその前に、新しいイベントはその後にjournalへ書き出します。その更新の条件が成り立たないときは `OPTIMISTIC_LOCK` エラーになり、更新を再試行することはありません。応答を失ったときは、成否不明の `STORAGE` エラーになります。同じ封筒を送り直すと、最初の更新が通っていれば `OPTIMISTIC_LOCK` エラーになります。直列化した封筒が `envelopeLimitBytes`（既定1 MiB、最大8 MiB、両方の設定の行の値と同じ）を超えると、何も書く前に規則 `BT-16` の `CONTRACT_VIOLATION` エラーになります。保持方針は `RetentionPolicy.none()` または `delete(n)` で、TTLはbuildで失敗します。非同期のBigtable APIは呼出しスレッドで実行し、完了済みのfutureを返します。変更フィードは提供せず、`BigtableStorageConfig.Builder.changeFeed(true)` は `CONFIGURATION` エラーになります。表はRustのv5実装と同じ配置です。

```java
import com.github.j5ik2o.event.store.adapter.java.bigtable.*;
import com.google.cloud.bigtable.admin.v2.BigtableTableAdminClient;
import com.google.cloud.bigtable.data.v2.BigtableDataClient;

BigtableDataClient data = BigtableDataClient.create("project", "instance");
BigtableTableAdminClient admin = BigtableTableAdminClient.create("project", "instance");
BigtableStorageConfig settings = BigtableStorageConfig.builder()
    .journalTableId("esa_journal")
    .headTableId("esa_head")
    .retentionPolicy(RetentionPolicy.none())
    .build();
BigtableStorage.initialize(data, settings); // 新しい空の表に1回だけ
BigtableStorage storage = BigtableStorage.open(data, admin, settings); // 配置の照合だけを行う
EventStore<String, String> bigtableStore = BigtableEventStore.create(storage, config);
AsyncEventStore<String, String> asyncBigtable = BigtableEventStore.createAsync(storage, config);
AggregateId bigtableId = AggregateId.of("UserAccount", "bigtable-example");
bigtableStore.persistEvent(UserAccountExample.event(bigtableId, 1, "Alice"));
asyncBigtable.persistEventAndSnapshot(
    UserAccountExample.event(bigtableId, 2, "Bob"), UserAccountExample.snapshot(2, "Bob")).join();
Optional<String> bigtableName = UserAccountExample.restore(bigtableStore, bigtableId); // Bob
```

## 直列化

ドメイン型には、`PayloadSerializer<T>` で直列化できることだけを求めます。payloadとsnapshotの型は `EventStoreConfig` の生成時に束縛し、読取り操作に `Class<T>` を渡しません。既定のJSONシリアライザはpayloadまたは集約状態だけを直列化し、封筒のメタデータと分けて保存します。ライブラリは `manifest` を解釈しません。

## 保持

### 保持方針

既定の `RetentionPolicy.none()` は現在snapshotだけを保存します。`RetentionPolicy.delete(n)` は現在snapshotに加えて最新n件の履歴を残します。DynamoDBは `RetentionPolicy.ttl(n, graceSeconds)` も提供し、snapshot表の `ttl` 属性でTTLを有効にします。印付き履歴は保持件数に数えず、期限を延長しません。MemoryとSQLiteはTTL設定を拒否し、変更フィードを提供しません。SQLiteは `RetentionPolicy.none()` と `RetentionPolicy.delete(n)` を提供します。Spannerは猶予を日数で数えるため、独自の `SpannerRetentionPolicy`（`none()`、`delete(n)`、`ttl(n, graceDays)`）を使い、変更フィードを提供しません。Bigtableはcoreの `RetentionPolicy.none()` または `delete(n)` を `BigtableStorageConfig` に渡し、TTLは拒否して、変更フィードを提供しません。

保持方針は `MemoryStorage.create(policy)`、`DynamoDbTableConfig`、`SqliteStorageConfig`、`SpannerStorageConfig`、`BigtableStorageConfig` のいずれかに渡します。DynamoDBはsnapshot書込みの確定後に保持を行い、SQLiteとSpannerは追記の確定後に別のトランザクションで保持を行い（Spannerは1回500行以下に分割）、Bigtableは追記の確定後に古い履歴の行を1回の条件付き更新ずつretiredにし、Memoryはイベントだけの追記でも残った履歴の処理を再試行します。保持はjournal・head・現在snapshotを削除しません。

### 失敗と通知

確定後の保持失敗でも書込みは成功します。最終失敗はWARNログと、任意の `EventStoreConfig.Builder.retentionFailureListener` に1回通知します。通知には集約ID・方式・元原因があり、Memory・SQLite・Spannerはロックやトランザクションの解放後に通知します。同期実装、Memory非同期、SQLite非同期、Spanner非同期、Bigtable非同期は呼出しスレッド、DynamoDB非同期は公開future完了前のSDK完了スレッドで通知します。通知先はブロックしない実装にします。通知先の失敗でも、確定済み書込みを失敗にしません。Bigtableは、確定後に新しい末尾のイベントをjournalへ書き出せなかった失敗も、ロガー `com.github.j5ik2o.event.store.adapter.java.bigtable.journal` にWARNで記録します。この失敗ではリスナーを呼ばず、追記は確定したままです。ログやリスナーが `Error` を投げても、確定済みの追記は変わりません。

## エラー

操作・生成のエラーは `ErrorCategory` の5分類です。`OPTIMISTIC_LOCK`、`CONTRACT_VIOLATION`、`SERIALIZATION`、`CONFIGURATION`、`STORAGE` を例外型または `category()` で判別します。非同期では `EventStoreExceptions.unwrap(error)` でfutureの包みを外します。契約違反は `rule()` と `seqNr()` を公開します。

## 移行

APIの変更と「旧版で読む→利用者が変換→新版へ書き直す」手順は [MIGRATION_GUIDE](docs/MIGRATION_GUIDE.ja.md)を参照してください。

## 適合

配布する適合データ（版1.3.0）は [conformance/](conformance/README.md) にあります。

[Memory利用試験](src/test/java/com/github/j5ik2o/event/store/adapter/java/examples/UserAccountExampleTest.java)、[DynamoDB利用試験](src/test/java/com/github/j5ik2o/event/store/adapter/java/dynamodbtest/DynamoDbPublicApiUsageTest.java)、[SQLite利用試験](src/test/java/com/github/j5ik2o/event/store/adapter/java/examples/SqliteUserAccountExampleTest.java)、[Spanner利用試験](src/test/java/com/github/j5ik2o/event/store/adapter/java/examples/SpannerUserAccountExampleTest.java)、[Bigtable利用試験](src/test/java/com/github/j5ik2o/event/store/adapter/java/examples/BigtableUserAccountExampleTest.java)は、この例を公開factoryでコンパイル・実行します。DynamoDB試験は固定されたDynamoDB Localイメージ、Spanner試験は固定されたCloud Spannerエミュレーターのイメージ、Bigtable試験は固定されたCloud Bigtableエミュレーターのイメージを使うため、いずれもDockerが必要です。SQLite試験は一時的なローカルファイルを使うためDockerは不要です。

```sh
./gradlew spotlessCheck test
```

## ライセンス

MIT と Apache-2.0 のどちらかを選べる二重ライセンス（`MIT OR Apache-2.0`）です。[LICENSE-MIT](LICENSE-MIT) と [LICENSE-APACHE](LICENSE-APACHE) を参照してください。
