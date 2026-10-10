# event-store-adapter-java

[![CI](https://github.com/j5ik2o/event-store-adapter-java/actions/workflows/ci.yml/badge.svg)](https://github.com/j5ik2o/event-store-adapter-java/actions/workflows/ci.yml)
[![Maven Central](https://maven-badges.herokuapp.com/maven-central/io.github.j5ik2o/event-store-adapter-java/badge.svg)](https://maven-badges.herokuapp.com/maven-central/io.github.j5ik2o/event-store-adapter-java)
[![Renovate](https://img.shields.io/badge/renovate-enabled-brightgreen.svg)](https://renovatebot.com)
[![License](https://img.shields.io/badge/License-APACHE2.0-blue.svg)](https://opensource.org/licenses/apache-2-0)
[![License](https://img.shields.io/badge/License-MIT-blue.svg)](https://opensource.org/licenses/MIT)
[![tokei](https://tokei.rs/b1/github/j5ik2o/event-store-adapter-java)](https://github.com/XAMPPRocky/tokei)

An event store for CQRS/Event Sourcing, with Memory and DynamoDB implementations. Java 11 or later is required.

[日本語](README.ja.md)

## Dependency and public API

The current artifact version is `2.0.0-SNAPSHOT`. The common contract is called **v4**; the distributed conformance data has version **1.0.0**. These are separate versions.

```kotlin
repositories {
    mavenCentral()
    maven { url = uri("https://central.sonatype.com/repository/maven-snapshots/") }
}
dependencies {
    implementation("io.github.j5ik2o:event-store-adapter-java:2.0.0-SNAPSHOT")
}
```

| Package | Public types |
|---------|--------------|
| `com.github.j5ik2o.event.store.adapter.java.core` | IDs, envelopes, serializers, configuration, exceptions, `EventStore<P,A>`, `AsyncEventStore<P,A>` |
| `com.github.j5ik2o.event.store.adapter.java.memory` | `MemoryStorage`, `MemoryEventStore` |
| `com.github.j5ik2o.event.store.adapter.java.dynamodb` | `DynamoDbTableConfig`, `DynamoDbEventStore` |

Domain types only need to be serializable by `PayloadSerializer<T>`. Bind the payload and snapshot types when creating `EventStoreConfig`; read operations do not take a `Class<T>`. The default JSON serializer serializes the payload or aggregate state separately from envelope metadata. The library does not interpret `manifest`.

## Save and restore with Memory

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

Only stores given the same `MemoryStorage` object share records, settings and locking. Each `MemoryStorage.create()` creates an independent, process-local store. The asynchronous Memory API returns completed futures on the calling thread.

Both stores provide four operations:

| Operation | Result |
|-----------|--------|
| `persistEvent(event)` | Append one event |
| `persistEventAndSnapshot(event, snapshot)` | Commit the event and snapshot together |
| `getLatestSnapshotById(id)` | Optional snapshot envelope and head sequence number |
| `getEventsByIdSinceSeqNr(id, start)` | All events at or above `start`, in ascending order |

Sequence 1 creates an aggregate, including without a snapshot. Every later event must immediately follow the head. Duplicate or stale sequence numbers cause an optimistic lock failure; gaps cause a contract violation. The event and snapshot sequence numbers must match when written together.

## DynamoDB factories

Provision the three tables and snapshot GSI described in [DATABASE_SCHEMA](docs/DATABASE_SCHEMA.md) first. The application owns and closes its configured SDK clients. Factories validate or initialize the three configuration items; they do not create tables.

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

## Restoration and consistency

An empty outer `Optional` from `getLatestSnapshotById` means no head exists. A present result with an empty `snapshot()` means the aggregate exists without a snapshot. Start replay at **1** in that case, or at **`snapshot.seqNr() + 1`** when a snapshot exists. Do not use `headSeqNr()` as the replay start or an upper bound.

Memory reads the head and snapshot atomically. DynamoDB reads both items strongly consistently, but the pair is **non-atomic**: the snapshot may be older or newer than the returned head, including across retries for unprocessed keys. Both combinations are valid. Keep the returned snapshot and replay only later events, so its sequence is not applied twice. Event reads consume every page and can include later concurrent appends.

## Retention, failures and notifications

The default `RetentionPolicy.none()` keeps only the current snapshot. `RetentionPolicy.delete(n)` keeps the newest `n` history snapshots, in addition to the current one. DynamoDB also supports `RetentionPolicy.ttl(n, graceSeconds)`; enable TTL on the snapshot table's `ttl` attribute. Marked history is excluded from the retained count, and its expiry is not extended. Memory rejects TTL configuration and does not provide a change feed.

Set the policy on `MemoryStorage.create(policy)` or `DynamoDbTableConfig`. DynamoDB runs retention after a committed snapshot write; Memory also retries leftover cleanup after event-only appends. Retention does not remove the journal, head or current snapshot.

A retention failure after commit preserves write success. The final failure is logged at WARN and delivered once to an optional `EventStoreConfig.Builder.retentionFailureListener`, with aggregate ID, mode and original cause. Memory releases its lock before notifying. Synchronous stores and asynchronous Memory notify on the calling thread; asynchronous DynamoDB notifies on the SDK completion thread before the public future completes. Keep listeners non-blocking. Listener failures do not turn a committed write into a failed write.

Operation and creation errors use the five `ErrorCategory` values: `OPTIMISTIC_LOCK`, `CONTRACT_VIOLATION`, `SERIALIZATION`, `CONFIGURATION`, `STORAGE`. For asynchronous failures, use `EventStoreExceptions.unwrap(error)` to remove future wrappers, then inspect the exception type or `category()`. Contract violations expose `rule()` and `seqNr()`.

## Migration and executable tests

See [MIGRATION_GUIDE](MIGRATION_GUIDE.md) for API changes and the old-version read → application conversion → new-version rewrite procedure.

[Memory usage tests](src/test/java/com/github/j5ik2o/event/store/adapter/java/examples/UserAccountExampleTest.java) and [DynamoDB usage tests](src/test/java/com/github/j5ik2o/event/store/adapter/java/dynamodbtest/DynamoDbPublicApiUsageTest.java) compile and run the example through all four public factories. DynamoDB tests use the pinned DynamoDB Local image; Docker is required.

```sh
./gradlew spotlessCheck test
```

## License

MIT License. See [LICENSE](LICENSE).

## Links

- [Common Documents](https://github.com/j5ik2o/event-store-adapter)
