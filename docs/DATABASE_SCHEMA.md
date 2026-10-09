# DynamoDB schema

This is the layout used by `com.github.j5ik2o.event.store.adapter.java.dynamodb`. The artifact is `2.0.0-SNAPSHOT`, the common contract is v4, and conformance data is 1.0.0. The stored `layout_version` is **1**, a separate storage-layout number.

## Provisioning

Create all three tables in the same region before calling a public factory. Supply their names and the snapshot GSI name through `DynamoDbTableConfig`. The library initializes configuration items but does not provision tables.

| Table | Partition key | Sort key | GSI | Streams | TTL |
|-------|---------------|----------|-----|---------|-----|
| journal | `aid` (S) | `seq_nr` (N) | None | Disabled | Disabled |
| snapshot | `aid` (S) | `skey` (N) | PK `aid` (S), SK `active_history_seq_nr` (N), projection **KEYS_ONLY** | Disabled | Attribute `ttl`, enabled only for TTL retention |
| head | `aid` (S) | None | None | Enabled, **NEW_IMAGE** | Disabled |

For `CreateTable`, journal declares `aid` and `seq_nr` in `AttributeDefinitions`; snapshot declares `aid`, `skey` and `active_history_seq_nr`; head declares only `aid`. Other item fields are not key attribute definitions. The [independent test table definitions](../src/test/java/com/github/j5ik2o/event/store/adapter/java/dynamodbtest/DynamoDbConfigurationTables.java) show the SDK requests used by the executable usage tests.

`aid` is exactly `AggregateId.of(typeName, value).asString()`: `typeName + "-" + value`. The type name cannot contain a hyphen; the value may. The complete UTF-8 ID must be at most 1024 bytes. No logical shard, hash or key resolver is used.

Ordinary operations target one aid and do not scan tables. Journal and configuration/snapshot reads are strongly consistent. History GSI reads are eventually consistent. The library writes the head change to Streams; it does not provide a Streams subscriber or resynchronization helper.

## Configuration items

There is one configuration item in each table:

| Table | Key |
|-------|-----|
| journal | `aid(S)="__config__"`, `seq_nr(N)=0` |
| snapshot | `aid(S)="__config__"`, `skey(N)=0` |
| head | `aid(S)="__config__"` |

Besides their keys, these items contain only `store_id` (S) and `layout_version` (N). All three must have the same generated `store_id` and `layout_version=1`. The snapshot configuration item has no `active_history_seq_nr` or `ttl`.

Factory creation reads all three with strongly consistent `BatchGetItem`, accumulating responses and retrying only `UnprocessedKeys`. It treats them as absent only after every key has been processed. If all three are absent, it creates them in one transaction with conditional Puts using `attribute_not_exists(aid)`. A concurrent conditional failure or transaction conflict triggers one complete strong reread. If configuration is still wholly absent, creation fails with `StorageException`.

Partial configuration, different store IDs or an unsupported layout version cause `ConfigurationException`. Communications failure or exhausted retries cause `StorageException`. Never combine three tables from different stores.

## Item attributes

S, N, B, L and M are DynamoDB string, number, binary, list and map types.

| Item | Complete attribute set |
|------|------------------------|
| journal event | `aid` (S), `seq_nr` (N), `occurred_at` (N), `manifest` (S), `payload` (B) |
| current snapshot | `aid` (S), `skey` (N, 0), `seq_nr` (N), `manifest` (S), `payload` (B), `last_updated_at` (N) |
| active history snapshot | Current snapshot fields, with `skey=seq_nr`, plus `active_history_seq_nr` (N, equal to `seq_nr`) |
| TTL-marked history snapshot | History fields with `ttl` (N), without `active_history_seq_nr` |
| head | `aid` (S), `type_name` (S), `seq_nr` (N), `events` (L) |

The head's `events` has exactly one M element: `seq_nr` (N), `occurred_at` (N), `manifest` (S), `payload` (B), for the most recently committed event.

- `occurred_at` is the supplied event's epoch **nanoseconds**, a signed 64-bit integer, preserved on read. Java uses `Instant`.
- `last_updated_at` is that event's epoch **milliseconds**, using `Instant.toEpochMilli()` (floor for negative fractional milliseconds).
- `ttl` is an expiry in epoch **seconds**. Unmarked history, current snapshots and configuration items omit the attribute entirely.
- `seq_nr` is an integer from 0 through 2^53−1; events begin at 1.
- `manifest` defaults to the empty string and is stored without interpretation.
- `payload` contains only serialized domain payload or aggregate state, not envelope metadata. The default is JSON bytes.

Current snapshots never contain `active_history_seq_nr` or `ttl`. With no history retention, only the current snapshot is written. With a retained count, each snapshot write stores current and history items in the same commit. The sparse GSI includes only active history.

## Store settings

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

| Setting | Meaning |
|---------|---------|
| Four table/index names | Required, nonblank names of already provisioned resources |
| `retentionPolicy` | Default `none()`; `delete(n)` or `ttl(n, graceSeconds)` requires `n >= 1` |
| `configurationReadRetryLimit` | Default 10; excludes the initial request. Also limits latest-snapshot unprocessed-key retries. Zero disables retries; negative is a configuration error |
| `clock` | Default `Clock.systemUTC()`; used to obtain the TTL marking time |
| `EventStoreConfig` | Required payload and snapshot serializers; optional retention failure listener |

TTL grace is a nonnegative whole number of seconds. Expiry is marking time plus grace; it is calculated without signed-long overflow. Configuration items store only store identity and layout version, not serializers or retention settings.

## Commit, retention and failure

An append uses one `TransactWriteItems`: journal Put, head Put/Update, and optional current/history snapshot Puts. New creation requires an absent head. Later appends require `head.seq_nr = event.seqNr() - 1`. Journal Puts require an absent key. Head conditions request `ALL_OLD` for failure classification. There is no snapshot version field or version argument. Event and snapshot sequence numbers must match. Each item is checked against the 409600-byte size limit before transmission.

DynamoDB retention runs only after a committed snapshot write and completes before the public call/future succeeds. It consumes every descending GSI page, merges the just-written history once, and keeps the newest n active items. It never modifies another aggregate, journal, head, current snapshot or configuration.

- DELETE removes obsolete history with `BatchWriteItem`, at most 25 per batch. Only unprocessed deletes are retried; the retry limit is 10.
- TTL marks obsolete history one item at a time using `SET #ttl = :expires REMOVE active_history_seq_nr`, with `#ttl` bound to `ttl` and condition `attribute_exists(active_history_seq_nr)`. An already-marked conditional failure is skipped. Marked history is not counted and its expiry is not extended. Physical removal is performed by DynamoDB TTL.

A final retention failure preserves commit success, logs WARN and notifies the optional listener once with aggregate ID, mode and original cause. Leftovers are eligible at a later snapshot write. Synchronous notification uses the calling thread; asynchronous notification uses the SDK completion thread before the public future completes. Keep listeners non-blocking. Listener exceptions do not change write success.

## Read and restoration

`getLatestSnapshotById` reads head and current snapshot strongly consistently with `BatchGetItem`, retrying unprocessed keys. It does not read them atomically. An old head/new snapshot or new head/old snapshot is valid, including across retries; the library returns both as observed. No head means an empty result. A head without a current snapshot yields a result whose `snapshot()` is empty.

Replay starts at `snapshot.seqNr() + 1`, or 1 without a snapshot, and applies events in ascending order. Do not clip a newer snapshot to the head or use the head as the replay start/upper bound. `getEventsByIdSinceSeqNr` reads journal with `aid = :aid AND seq_nr >= :seq_nr` and consumes every page. This avoids reapplying an event already included in the returned snapshot.

See [README](../README.md) for public factories and [MIGRATION_GUIDE](../MIGRATION_GUIDE.md) for rewriting old data into separate new tables.
