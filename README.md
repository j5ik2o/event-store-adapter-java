# event-store-adapter-java

[![CI](https://github.com/j5ik2o/event-store-adapter-java/actions/workflows/ci.yml/badge.svg)](https://github.com/j5ik2o/event-store-adapter-java/actions/workflows/ci.yml)
[![Maven Central](https://maven-badges.herokuapp.com/maven-central/io.github.j5ik2o/event-store-adapter-java/badge.svg)](https://maven-badges.herokuapp.com/maven-central/io.github.j5ik2o/event-store-adapter-java)
[![Renovate](https://img.shields.io/badge/renovate-enabled-brightgreen.svg)](https://renovatebot.com)
[![License](https://img.shields.io/badge/License-APACHE2.0-blue.svg)](https://opensource.org/licenses/apache-2-0)
[![License](https://img.shields.io/badge/License-MIT-blue.svg)](https://opensource.org/licenses/MIT)
[![tokei](https://tokei.rs/b1/github/j5ik2o/event-store-adapter-java)](https://github.com/XAMPPRocky/tokei)

An event store for CQRS/Event Sourcing, with Memory, DynamoDB, SQLite and Spanner implementations. Java 11 or later is required.

[日本語](README.ja.md)

Developers changing this repository: see [CONTRIBUTING](CONTRIBUTING.md).

## Overview

### Versions

The current artifact version is `5.0.1`, published to Maven Central. This library follows [common contract v5](https://github.com/j5ik2o/event-store-adapter/blob/main/docs/spec/README.md); its major version 5 matches the contract revision. The distributed conformance data has version **1.3.0**, a separate number.

Version 2.0.0 was released before the decision to align versions, and its contents are the same as 5.0.0. Use 5.0.1 or later.

### Public packages

| Package | Public types |
|---------|--------------|
| `com.github.j5ik2o.event.store.adapter.java.core` | IDs, envelopes, serializers, configuration, exceptions, `EventStore<P,A>`, `AsyncEventStore<P,A>` |
| `com.github.j5ik2o.event.store.adapter.java.memory` | `MemoryStorage`, `MemoryEventStore` |
| `com.github.j5ik2o.event.store.adapter.java.dynamodb` | `DynamoDbTableConfig`, `DynamoDbEventStore` |
| `com.github.j5ik2o.event.store.adapter.java.sqlite` | `SqliteStorageConfig`, `SqliteStorage`, `SqliteEventStore` |
| `com.github.j5ik2o.event.store.adapter.java.spanner` | `SpannerStorageConfig`, `SpannerRetentionPolicy`, `SpannerStorage`, `SpannerEventStore` |

### Links

- [Common Documents](https://github.com/j5ik2o/event-store-adapter)
- [for Java](https://github.com/j5ik2o/event-store-adapter-java)
- [for Scala](https://github.com/j5ik2o/event-store-adapter-scala)
- [for Kotlin](https://github.com/j5ik2o/event-store-adapter-kotlin)
- [for Rust](https://github.com/j5ik2o/event-store-adapter-rs)
- [for Go](https://github.com/j5ik2o/event-store-adapter-go)
- [for JavaScript/TypeScript](https://github.com/j5ik2o/event-store-adapter-js)
- [for .NET](https://github.com/j5ik2o/event-store-adapter-dotnet)
- [for PHP](https://github.com/j5ik2o/event-store-adapter-php)

## Installation

```kotlin
repositories {
    mavenCentral()
}
dependencies {
    implementation("io.github.j5ik2o:event-store-adapter-java:5.0.1")
}
```

## Quick start

### Save and restore with Memory

The following uses a name as both the event payload and account state. The complete [UserAccountExample](src/test/java/com/github/j5ik2o/event/store/adapter/java/examples/UserAccountExample.java) contains the envelope builders and synchronous/asynchronous restoration functions used below. Copy that example together with these snippets when running them outside the repository.

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

### Operations

All stores provide four operations:

| Operation | Result |
|-----------|--------|
| `persistEvent(event)` | Append one event |
| `persistEventAndSnapshot(event, snapshot)` | Commit the event and snapshot together |
| `getLatestSnapshotById(id)` | Optional snapshot envelope and head sequence number |
| `getEventsByIdSinceSeqNr(id, start)` | All events at or above `start`, in ascending order |

Sequence 1 creates an aggregate, including without a snapshot. Every later event must immediately follow the head. Duplicate or stale sequence numbers cause an optimistic lock failure; gaps cause a contract violation. The event and snapshot sequence numbers must match when written together.

### Restoration and consistency

An empty outer `Optional` from `getLatestSnapshotById` means no head exists. A present result with an empty `snapshot()` means the aggregate exists without a snapshot. Start replay at **1** in that case, or at **`snapshot.seqNr() + 1`** when a snapshot exists. Do not use `headSeqNr()` as the replay start or an upper bound.

Memory and SQLite read the head and snapshot atomically; SQLite reads both in one read transaction, and Spanner reads both in one strong read-only transaction. DynamoDB reads both items strongly consistently, but the pair is **non-atomic**: the snapshot may be older or newer than the returned head, including across retries for unprocessed keys. Both combinations are valid. Keep the returned snapshot and replay only later events, so its sequence is not applied twice. Event reads consume every page and can include later concurrent appends.

## Storages

### Memory

Only stores given the same `MemoryStorage` object share records, settings and locking. Each `MemoryStorage.create()` creates an independent, process-local store. The asynchronous Memory API returns completed futures on the calling thread. The memory storage lives in a single process. It does not persist to files, survive restarts, or share data across processes.

### DynamoDB

Provision the three tables and snapshot GSI described in [DYNAMODB_DATABASE_SCHEMA](docs/DYNAMODB_DATABASE_SCHEMA.md) first. The application owns and closes its configured SDK clients. Factories validate or initialize the three configuration items; they do not create tables. Keep the three tables in one Region and write to them only from that Region. Global tables (multi-Region replicas) are not supported.

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

// client and asyncClient are caller-owned, configured SDK clients.
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

DynamoDB asynchronous creation returns a future because configuration validation performs I/O. Use the store after that future succeeds.

### SQLite

SQLite stores events in one local file on the same host. It is not available in 5.0.1; use the next release. Initialize a new or empty file once with `SqliteStorage.initialize`, then open it with `SqliteStorage.open`. Opening never initializes a file. It fails with a `STORAGE` error when the file is missing, and with a `CONFIGURATION` error when the file is uninitialized or does not match the settings. Each `SqliteStorage` owns one JDBC connection and must be closed. Several `SqliteStorage` objects, in one process or in several processes on the same host, can use the same file; each object serializes its own operations. A write that cannot get the write lock within `lockWait` (default 5 seconds) fails with a `STORAGE` error and commits nothing. The asynchronous SQLite API runs on the calling thread, including the lock wait, and returns completed futures. Do not place the file on a network file system. See [SQLITE_DATABASE_SCHEMA](docs/SQLITE_DATABASE_SCHEMA.md) for the file format and operations.

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

Spanner stores events in five tables of one Cloud Spanner database that use the GoogleSQL dialect. It is not available in 5.0.1; use the next release. You create the tables and one configuration row yourself with the DDL in [SPANNER_DATABASE_SCHEMA](docs/SPANNER_DATABASE_SCHEMA.md); the library never runs DDL. `SpannerStorage.open(DatabaseClient, SpannerStorageConfig)` only verifies the layout. It fails with a `CONFIGURATION` error when a table, a column, a primary key, the configuration row or the row deletion policy is missing or does not match, and with a `STORAGE` error when the database cannot be read. You build and own the `DatabaseClient`, including credentials and the emulator host; the library never closes it. Writes run in a SERIALIZABLE read-write transaction. An aborted transaction is retried with the same envelope up to `transactionRetryLimit` times (default 5, the first attempt is not counted), then fails with a `STORAGE` error. When the commit response is lost, the append fails with a `STORAGE` error whose outcome is unknown; sending the same envelope again then fails with an `OPTIMISTIC_LOCK` error if the first commit went through. Reads use one strong read-only transaction. The retention policy is `SpannerRetentionPolicy.none()`, `delete(n)` or `ttl(n, graceDays)`; the grace period is counted in days and must equal `ttl_grace_days` in the configuration row and the `INTERVAL n DAY` of the row deletion policy. The asynchronous Spanner API runs on the calling thread and returns completed futures. No change feed is provided; `SpannerStorageConfig.Builder.changeFeed(true)` fails with a `CONFIGURATION` error. The tables use the same layout as the JavaScript v5 implementation.

```java
import com.github.j5ik2o.event.store.adapter.java.spanner.*;
import com.google.cloud.spanner.DatabaseClient;

DatabaseClient client = spanner.getDatabaseClient(DatabaseId.of("project", "instance", "database"));
SpannerStorageConfig settings = SpannerStorageConfig.builder()
    .tablePrefix("esa") // tables esa_journal, esa_head, esa_snapshot_current, ...
    .retentionPolicy(SpannerRetentionPolicy.none())
    .transactionRetryLimit(5)
    .build();
SpannerStorage storage = SpannerStorage.open(client, settings); // verifies the layout only
EventStore<String, String> spannerStore = SpannerEventStore.create(storage, config);
AsyncEventStore<String, String> asyncSpanner = SpannerEventStore.createAsync(storage, config);
AggregateId spannerId = AggregateId.of("UserAccount", "spanner-example");
spannerStore.persistEvent(UserAccountExample.event(spannerId, 1, "Alice"));
asyncSpanner.persistEventAndSnapshot(
    UserAccountExample.event(spannerId, 2, "Bob"), UserAccountExample.snapshot(2, "Bob")).join();
Optional<String> spannerName = UserAccountExample.restore(spannerStore, spannerId); // Bob
```

## Serialization

Domain types only need to be serializable by `PayloadSerializer<T>`. Bind the payload and snapshot types when creating `EventStoreConfig`; read operations do not take a `Class<T>`. The default JSON serializer serializes the payload or aggregate state separately from envelope metadata. The library does not interpret `manifest`.

## Retention

### Retention policy

The default `RetentionPolicy.none()` keeps only the current snapshot. `RetentionPolicy.delete(n)` keeps the newest `n` history snapshots, in addition to the current one. DynamoDB also supports `RetentionPolicy.ttl(n, graceSeconds)`; enable TTL on the snapshot table's `ttl` attribute. Marked history is excluded from the retained count, and its expiry is not extended. Memory and SQLite reject TTL configuration and do not provide a change feed. SQLite supports `RetentionPolicy.none()` and `RetentionPolicy.delete(n)`. Spanner takes its own `SpannerRetentionPolicy` (`none()`, `delete(n)`, `ttl(n, graceDays)`), because its TTL grace is counted in whole days; it provides no change feed.

Set the policy on `MemoryStorage.create(policy)`, `DynamoDbTableConfig`, `SqliteStorageConfig` or `SpannerStorageConfig`. DynamoDB runs retention after a committed snapshot write; SQLite and Spanner run it in separate transactions after the append commits (Spanner in chunks of at most 500 rows); Memory also retries leftover cleanup after event-only appends. Retention does not remove the journal, head or current snapshot.

### Failures and notifications

A retention failure after commit preserves write success. The final failure is logged at WARN and delivered once to an optional `EventStoreConfig.Builder.retentionFailureListener`, with aggregate ID, mode and original cause. Memory, SQLite and Spanner release their locks and transactions before notifying. Synchronous stores, asynchronous Memory, asynchronous SQLite and asynchronous Spanner notify on the calling thread; asynchronous DynamoDB notifies on the SDK completion thread before the public future completes. Keep listeners non-blocking. Listener failures do not turn a committed write into a failed write.

## Errors

Operation and creation errors use the five `ErrorCategory` values: `OPTIMISTIC_LOCK`, `CONTRACT_VIOLATION`, `SERIALIZATION`, `CONFIGURATION`, `STORAGE`. For asynchronous failures, use `EventStoreExceptions.unwrap(error)` to remove future wrappers, then inspect the exception type or `category()`. Contract violations expose `rule()` and `seqNr()`.

## Migration

See [MIGRATION_GUIDE](docs/MIGRATION_GUIDE.md) for API changes and the old-version read → application conversion → new-version rewrite procedure.

## Conformance

The distributed conformance data (version 1.3.0) is in [conformance/](conformance/README.md).

[Memory usage tests](src/test/java/com/github/j5ik2o/event/store/adapter/java/examples/UserAccountExampleTest.java), [DynamoDB usage tests](src/test/java/com/github/j5ik2o/event/store/adapter/java/dynamodbtest/DynamoDbPublicApiUsageTest.java) [SQLite usage tests](src/test/java/com/github/j5ik2o/event/store/adapter/java/examples/SqliteUserAccountExampleTest.java) and [Spanner usage tests](src/test/java/com/github/j5ik2o/event/store/adapter/java/examples/SpannerUserAccountExampleTest.java) compile and run the example through the public factories. DynamoDB tests use the pinned DynamoDB Local image and Spanner tests use the pinned Cloud Spanner emulator image; Docker is required for both. SQLite tests use temporary local files; Docker is not required.

```sh
./gradlew spotlessCheck test
```

## License

MIT License. See [LICENSE-MIT](LICENSE-MIT).
