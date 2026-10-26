# SQLite storage schema

This is the layout used by `com.github.j5ik2o.event.store.adapter.java.sqlite`. It is not part of `5.0.1`; it is available from the next release. The library follows the [common contract v5](https://github.com/j5ik2o/event-store-adapter/blob/main/docs/spec/README.md) and its [SQLite profile](https://github.com/j5ik2o/event-store-adapter/blob/main/docs/spec/storage/sqlite.md). Conformance data is 1.3.0. The stored `layout_version` is **1**, a separate storage-layout number. The files use the same layout as the Rust implementation.

See [README](../README.md) for the public types and [MIGRATION_GUIDE](MIGRATION_GUIDE.md) for the version differences. The design record, in Japanese, is [sqlite-backend](design/sqlite-backend.md).

## Layout

### Tables

One SQLite file holds five tables. All of them are `STRICT`; the opening check fails if any table is missing or is not `STRICT`. No change-feed table is created.

| Table | Primary key | Columns (`NOT NULL` unless noted) |
|-------|-------------|-----------------------------------|
| `journal` | `(aid, seq_nr)` | `aid` TEXT, `seq_nr` INTEGER, `occurred_at_ns` INTEGER, `manifest` TEXT, `payload` BLOB |
| `head` | `aid` | `aid` TEXT, `type_name` TEXT, `seq_nr` INTEGER, `event_occurred_at_ns` INTEGER, `event_manifest` TEXT, `event_payload` BLOB |
| `snapshot_current` | `aid` | `aid` TEXT, `seq_nr` INTEGER, `manifest` TEXT, `payload` BLOB |
| `snapshot_history` | `(aid, seq_nr)` | `aid` TEXT, `seq_nr` INTEGER, `manifest` TEXT, `payload` BLOB |
| `config` | `singleton` | `singleton` INTEGER, `store_id` TEXT, `layout_version` INTEGER, `keep_snapshot_count` INTEGER (nullable), `retention_mode` TEXT, `change_feed_enabled` INTEGER |

`aid` is exactly `AggregateId.of(typeName, value).asString()` and is declared `COLLATE BINARY`, so it is compared exactly. It is always bound as a parameter and never matched with `LIKE` or a prefix. Every `seq_nr` has `CHECK (seq_nr BETWEEN 1 AND 9007199254740991)`.

### Connection settings

Every connection is opened with a private cache (no shared cache) and then configured and read back: `locking_mode=NORMAL`, `journal_mode=WAL`, `synchronous=FULL`, `read_uncommitted=OFF` and `busy_timeout` equal to the lock wait. A value that does not read back as set is a configuration error. The bundled SQLite must be **3.51.3 or later**, compared as three integers; an older library is a configuration error. The file name is made absolute; a name containing `?` is rejected so it cannot be read as connection parameters.

## Configuration

### Store settings

```java
SqliteStorageConfig settings = SqliteStorageConfig.builder()
    .retentionPolicy(RetentionPolicy.delete(2))
    .lockWait(Duration.ofSeconds(5))
    .build();
```

| Setting | Meaning |
|---------|---------|
| `retentionPolicy` | Default `none()`; `delete(n)` requires `n >= 1`. TTL is a configuration error |
| `lockWait` | Default 5 seconds; at least zero and at most `Integer.MAX_VALUE` milliseconds |
| `EventStoreConfig` | Required payload and snapshot serializers; optional retention failure listener |

Settings are checked by `build()` before any file is touched.

### Settings recorded in the file

The `config` row records `keep_snapshot_count` (NULL without retention) and `retention_mode` (`delete`). Opening compares them with the client settings: a different retention count, in either direction, is a configuration error. There is no setting for a change feed; a file whose `change_feed_enabled` is 1 cannot be opened.

## Initialization

### Initializing a file

`SqliteStorage.initialize(file, settings)` initializes a new or empty file in one `BEGIN IMMEDIATE` transaction: the five tables, then one `config` row with a random lower-case UUID `store_id`, `layout_version=1`, the retention count, `retention_mode='delete'` and `change_feed_enabled=0`. A file that is already initialized, or that holds any other table, fails with a configuration error and is left unchanged. A missing parent directory is a storage error.

### Opening a file

`SqliteStorage.open(file, settings)` never creates or initializes a file. A missing file is a storage error. Inside one read transaction it checks that the file is initialized, that each table is `STRICT` with the expected columns, types, `NOT NULL` flags and primary key, that the `aid` key uses `BINARY` collation, and that `config` has exactly one valid row: `layout_version=1`, a lower-case UUID `store_id`, `retention_mode='delete'`, `change_feed_enabled=0` and the same retention count as the client. Any difference is a configuration error and the connection is closed.

## Records

### Column values

- `occurred_at_ns` and `event_occurred_at_ns` are the event's epoch **nanoseconds**, a signed 64-bit INTEGER, never a REAL or text.
- `seq_nr` is an integer from 1 through 2^53−1.
- `manifest` defaults to the empty string and is stored without interpretation.
- `payload` and `event_payload` contain only the serialized domain payload or aggregate state (JSON bytes by default), as BLOB.
- `head` holds the latest committed event of each aggregate. `snapshot_current` holds the latest snapshot. `snapshot_history` is written only when a retention count is set.

## Writes and reads

### Append

An append takes the connection's lock, then runs `BEGIN IMMEDIATE` so the write lock is held before the head is read. It reads the head, then:

- a sequence number at or below the head is an optimistic lock failure;
- a number other than head + 1 is a contract violation (W-8);
- otherwise it inserts the journal row, inserts or updates the head, upserts `snapshot_current` and, with retention, inserts `snapshot_history`, then `COMMIT`.

`COMMIT` is the commit point. Any earlier failure rolls back. A primary-key conflict on the journal or on a new head is an optimistic lock failure; a write lock wait that exceeds `lockWait`, `SQLITE_BUSY`, `SQLITE_LOCKED` and every other constraint failure are storage errors. A failed `COMMIT` is a storage error whose outcome is unknown; do not renumber the event.

### Read and restoration

`getLatestSnapshotById` reads the head and `snapshot_current` in the same read transaction, so the pair is consistent. `getEventsByIdSinceSeqNr` reads the head and the journal rows from `max(1, start)` up to the head in one read transaction. A missing row inside that range is a storage error, never a short result. Journal rows beyond the head are never returned.

Replay starts at `snapshot.seqNr() + 1`, or 1 without a snapshot.

## Retention

### Retention runs

Retention runs only after an append with a snapshot has committed, in a separate `BEGIN IMMEDIATE` transaction. It keeps the newest `n` history rows and deletes older ones. It never modifies the journal, the head or the current snapshot. A failure leaves the committed append successful, is logged at WARN and delivered once to the optional listener; leftovers are removed by the next snapshot write.

## Operations

### Files and processes

Use a local file on one host. Several `SqliteStorage` objects, in one process or several, can share the file; writers wait for the write lock for up to `lockWait`. Do not use a network file system. In WAL mode the main file alone is not a complete copy: back up with the SQLite backup facilities, not by copying only the main file. Close every `SqliteStorage` when done.

### Not verified

Process kills, power loss, honouring of `fsync` by the storage, and long multi-process runs are not verified by the tests. See [sqlite-backend](design/sqlite-backend.md) for the list and the reasons.
