# Migration guide

## Overview

### Version and approach

The current artifact is `5.0.1` and follows the [common contract v5](https://github.com/j5ik2o/event-store-adapter/blob/main/docs/spec/README.md); its major version 5 matches the contract revision. The distributed conformance data version 1.3.0 and DynamoDB `layout_version=1` are separate numbers.

For Java 1.x users, migration is a manual procedure: **read with the old library, convert the data into payloads and envelopes in your application, then rewrite it consecutively with the new library**. The new library provides no old public types, old internal types, automatic reading of the old storage layout, compatibility aliases or migration tool. It does not infer missing type information or timestamp precision.

Java 1.x has no SQLite storage, so there is no SQLite data to migrate and no migration tool for it. To use SQLite, initialize a new file with `SqliteStorage.initialize`; it is available from the release after 5.0.1. See the [SQLite storage schema](SQLITE_DATABASE_SCHEMA.md).

### Related documents

For the public factories, compilable examples and executable usage tests, see the [README](../README.md). For the three actual tables and every attribute, see the [DynamoDB storage schema](DYNAMODB_DATABASE_SCHEMA.md).

## Code changes

### API mapping

The new types are in `com.github.j5ik2o.event.store.adapter.java.core`, and the storages are in `memory`, `dynamodb` and `sqlite`. The old public packages directly under the base package and the old `internal` packages have been removed.

| 1.x | New API / handling |
|-----|--------------------|
| `EventStore<AID,A,E>` | `core.EventStore<P,A>` |
| `EventStoreAsync<AID,A,E>` | `core.AsyncEventStore<P,A>` |
| `EventStore.ofDynamoDB`, `EventStoreAsync.ofDynamoDB` | `DynamoDbEventStore.create`, `createAsync`. Specify the three table names and the snapshot GSI name |
| DynamoDB-only entry points of the old implementation | Memory is available through `MemoryEventStore.create/createAsync` and `MemoryStorage.create` |
| (none in 1.x) | `SqliteStorage.initialize/open`, `SqliteEventStore.create/createAsync` and `SqliteStorageConfig`; new in the release after 5.0.1 |
| User-implemented `AggregateId` | `core.AggregateId.of(typeName, value)`. The library builds the string from the type name and the value |
| `Aggregate`, `Event` | The implementation requirement is removed. Wrap domain payloads and state in `EventEnvelope<P>` and `SnapshotEnvelope<A>` |
| `AggregateAndEvent` | Removed. Pass the event envelope and the snapshot envelope separately |
| `EventStoreOptions` and mutable `with...` settings | The `EventStoreConfig` builder, `RetentionPolicy`, and `DynamoDbTableConfig` or `MemoryStorage` |
| `EventSerializer`, `SnapshotSerializer` and default implementations | `PayloadSerializer<T>`, `JsonPayloadSerializer`. Types are bound at creation |
| `KeyResolver`, `DefaultKeyResolver`, shard count, journal GSI name | Removed. The aid is used directly as the partition key, and the journal is queried by `(aid, seq_nr)` |
| `persistEvent(event, version)` | `persistEvent(EventEnvelope<P>)`. The expected value of the check is `event.seqNr()-1` |
| `persistEventAndSnapshot(event, aggregate)` | `persistEventAndSnapshot(EventEnvelope<P>, SnapshotEnvelope<A>)`. Both sequence numbers must match |
| `getLatestSnapshotById(Class<A>, id)` | `getLatestSnapshotById(id)`. Returns `Optional<SnapshotReadResult<A>>` |
| `getEventsByIdSinceSequenceNumber(Class<E>, id, seq)` | `getEventsByIdSinceSeqNr(id, seq)`. Returns all envelopes in ascending order |
| Old base exception, read/write exceptions, checked/unchecked pairs, `DeserializationException` | `EventStoreException` and five unchecked exceptions. Restoration failures are also `SerializationException` |

The five categories are `OPTIMISTIC_LOCK`, `CONTRACT_VIOLATION`, `SERIALIZATION`, `CONFIGURATION` and `STORAGE`. Use the exception type or `category()`; do not infer from messages. Use `EventStoreExceptions.unwrap(error)` to remove asynchronous future wrappers. Contract violations carry `rule()` and `seqNr()`.

The asynchronous Memory factory returns `AsyncEventStore` directly. The asynchronous DynamoDB factory returns `CompletableFuture<AsyncEventStore<P,A>>` because configuration validation performs I/O. Call the four operations after that future succeeds. The application owns and closes the SDK clients.

### Payload and envelope

The 1.x default format serialized the whole domain event or aggregate as JSON, including its ID, version and timestamps. The new serializer serializes only the payload or aggregate state, and stores the aid, sequence number, timestamp and manifest separately. Separate them according to your actual types and serializer. The library does not interpret the manifest.

The type name of `AggregateId.of(typeName, value)` cannot contain a hyphen; the value may. The complete ID must be at most 1024 bytes in UTF-8. Do not infer a type from an old ID string; prepare a mapping of type names and values that you can verify.

Event sequence numbers start at 1 and continue without gaps. A duplicate or a number at or below the head is an optimistic lock failure; a gap is a contract violation. When writing with a snapshot, pass envelopes with the same number. The old version is not carried over to the new check conditions.

`occurredAt` is an `Instant` that you supply. Check the actual unit and precision of the stored timestamps and convert them. If the old data stored only milliseconds, use an `Instant` that represents that value exactly, and do not supply the lost nanoseconds. Do not replace a missing type, ID or timestamp with the current time or a guess.

### Restoring from snapshots

An empty outer result means the head does not exist. If a result is present and only the snapshot is empty, read events from sequence number 1. With a snapshot, read from **`snapshot.seqNr()+1`** and apply the events to the state in ascending order. Do not use `headSeqNr()` as the start or the upper bound.

Memory reads the snapshot and the head atomically. DynamoDB reads each item strongly consistently but non-atomically, so a pair with a snapshot older than the head and a pair with a snapshot newer than the head are both valid. Do not clip the returned snapshot to the head, and do not reapply sequence numbers already included in the snapshot. Event reads consume every page and can include events committed later.

### Retention and notification changes

The default is `RetentionPolicy.none()`, which keeps only the current snapshot. Specify history retention explicitly with `delete(n)`, and DynamoDB expiring retention with `ttl(n, graceSeconds)`. `n` is at least 1 and the grace is a nonnegative whole number of seconds. Memory treats TTL as a configuration error.

DynamoDB runs retention only after a snapshot write is committed; Memory also retries leftovers after event-only appends. TTL-marked history is outside the retained count, and its expiry is not extended. Retention does not remove the journal, head or current snapshot.

A retention failure after commit does not change write success. The final failure is logged at WARN and notified once to the optional `retentionFailureListener`, with aggregate ID, mode and original cause. Memory notifies after releasing its lock. Synchronous stores and asynchronous Memory notify on the calling thread; asynchronous DynamoDB notifies on the SDK completion thread before the public future completes. Do not let the listener block, and a listener exception keeps the committed write successful.

## Data migration

### Rewrite procedure

1. You control writes to the old storage and fix the set of data to read. Keep the old data and the mapping information verifiable.
2. With the old library and your existing domain types and serializers, read the old events of each aggregate to the end. If you use old snapshots, check their numbers and states.
3. Check and convert the mapping to the new aid, the payload, the manifest, the timestamp and consecutive event numbers. First confirm that all events can be written consecutively from 1. If a number is missing or information is lost, do not guess or fill it in automatically; proceed only after you resolve it from the real data.
4. Following the [DynamoDB storage schema](DYNAMODB_DATABASE_SCHEMA.md), create new, independent journal, snapshot and head tables and the snapshot GSI. Do not mix the old two tables with the new three. For the TTL mode, enable `ttl` on the snapshot table.
5. Call a public factory with the new serializer configuration and `DynamoDbTableConfig`. For three empty tables, the factory creates the configuration items with the same `store_id` and `layout_version=1`.
6. For each aggregate, `persistEvent` in ascending, consecutive order from sequence number 1. To save a converted snapshot, use `persistEventAndSnapshot` when appending the event with the same number. There is no API that writes only a snapshot. Do not write the old version, old keys or old JSON as they are.

### Recovering from a partial failure

If a write fails midway, re-appending a number that is already committed without condition causes an optimistic lock failure. Check the actual storage through the new API, and decide the resume position yourself. Do not continue by adding an old-format fallback to the new library.

## Verification and cutover

### Verifying and switching

7. Read back all events and snapshots through the new API, and compare them with the checked converted data, numbers, timestamps, manifests and restored state. Do not hide write failures behind empty results; check the classified errors.
8. Switch the read/write destination after you have finished the rewrite and the checks on the application side. You also delete the old tables after those checks. This guide does not delete them automatically.
