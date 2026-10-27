# Bigtable storage schema

This is the layout used by `com.github.j5ik2o.event.store.adapter.java.bigtable`. It is not part of `5.0.1`; it is available from the next release. The library follows the [common contract v5](https://github.com/j5ik2o/event-store-adapter/blob/main/docs/spec/README.md) and its [Bigtable profile](https://github.com/j5ik2o/event-store-adapter/blob/main/docs/spec/storage/bigtable.md). Conformance data is 1.3.0. The stored `layout_version` is **1**, a separate storage-layout number. The tables use the same layout as the Rust v5 implementation.

See [README](../README.md) for the public types and [MIGRATION_GUIDE](MIGRATION_GUIDE.md) for the version differences. The design record is [bigtable-backend](design/bigtable-backend.md).

## Layout

This section describes the two tables that `BigtableStorage.open(...)` expects.

The store uses two tables of one Bigtable instance: a **journal** table and a **head** table. The two table IDs are chosen by you, must be different, and are passed to `BigtableStorageConfig.Builder.journalTableId` and `headTableId`. `BigtableStorage.open(...)` never creates a table, a column family or a row. It only verifies them. You create the tables yourself with the procedure below. The examples use the IDs `esa_journal` and `esa_head`; replace them with your own.

| Table | Column families | Data cells |
|:--|:--|:--|
| journal | `event`, `history`, `config` | `event:envelope`, `event:has_snapshot`, `history:state`, `history:envelope` |
| head | `head`, `snapshot`, `config` | `head:seq_nr`, `head:last_event`, `head:has_snapshot`, `snapshot:current` |

Garbage collection (BT-2):

- Every data column family (`event`, `history`, `head`, `snapshot`) must have exactly the version-count rule "keep at most 1 version". Age-based rules, rules joined with `and` or `or`, and a missing rule are all rejected as configuration errors.
- `config` has no rule that deletes by age. If age-based GC were attached to `config`, the configuration row would eventually disappear and every later `open` would fail.

```sh
# Replace PROJECT and INSTANCE. cbt is the Bigtable command line tool.
cbt -project PROJECT -instance INSTANCE createtable esa_journal
cbt -project PROJECT -instance INSTANCE createfamily esa_journal event
cbt -project PROJECT -instance INSTANCE createfamily esa_journal history
cbt -project PROJECT -instance INSTANCE createfamily esa_journal config
cbt -project PROJECT -instance INSTANCE setgcpolicy esa_journal event maxversions=1
cbt -project PROJECT -instance INSTANCE setgcpolicy esa_journal history maxversions=1

cbt -project PROJECT -instance INSTANCE createtable esa_head
cbt -project PROJECT -instance INSTANCE createfamily esa_head head
cbt -project PROJECT -instance INSTANCE createfamily esa_head snapshot
cbt -project PROJECT -instance INSTANCE createfamily esa_head config
cbt -project PROJECT -instance INSTANCE setgcpolicy esa_head head maxversions=1
cbt -project PROJECT -instance INSTANCE setgcpolicy esa_head snapshot maxversions=1
```

One cluster (BT-1): every read, write, retention step and configuration check of a store must go through an app profile that is pinned to the same cluster. The library cannot check this; you set it on the clients you pass in. Even with replica clusters, simultaneous writes through several clusters, multi-cluster routing and automatic failover are not supported.

## Configuration

The configuration row has the row key of the single byte `0x00`. Data row keys always start with an 8-byte hash and a 2-byte length, so they never equal it. The row has one cell, `config:metadata`, written at timestamp 0. Its value is a UTF-8 JSON object with these fields:

- `store_id`: a lowercase UUID string. Both tables must hold the same value.
- `layout_version`: the JSON integer `1`.
- `keep_snapshot_count`: `null` for no snapshot history. Otherwise a **decimal string** of a positive integer, without a sign, a leading zero or an exponent, such as `"3"`. It is a string so that no JSON reader turns it into a floating-point number. It must equal the keep count of the `RetentionPolicy` of every client. A JSON number such as `3` is rejected.
- `retention_mode`: the string `delete`. It is `delete` even when there is no snapshot history. TTL is not provided.
- `envelope_limit_bytes`: a JSON integer from 17 to 8388608. It is the upper limit of one serialized envelope (BT-16) and must equal `BigtableStorageConfig.envelopeLimitBytes()` of every client. The default is 1048576.

The order of the keys and the whitespace are not compared. A missing field, a field of a wrong type, a key that appears twice (even with the same value), or values that differ between the two tables is a configuration error. A configuration row that exists on only one side is a configuration error too; it is never filled in automatically.

```json
{"store_id":"5f0c8b8e-3c1a-4d7e-9a4b-2f6d1c0e7a11","layout_version":1,"keep_snapshot_count":"3","retention_mode":"delete","envelope_limit_bytes":1048576}
```

This version provides no change feed. `BigtableStorageConfig.Builder.changeFeed(true)` fails at build with a configuration error, and so does `RetentionPolicy.ttl(...)`.

## Initialization

Run this procedure outside the application, while no client is writing.

1. Stop every writer of the store.
2. Create the two tables and their column families with the commands above, and set the GC rules.
3. Confirm that both tables are empty.
4. Call `BigtableStorage.initialize(dataClient, config)` once. It writes the same new configuration row (a random lowercase UUID as `store_id`) into both tables, only when neither table already has one. If either already has a configuration row it throws a `ConfigurationException` and writes nothing. It does not create tables.
5. Start the writers. `BigtableStorage.open(dataClient, adminClient, config)` verifies the layout.

`open` throws a `ConfigurationException` when anything it checks is missing or does not match: a table that does not exist, a missing column family, a data column family whose GC rule is not "at most 1 version", a missing or invalid configuration row, or a value that differs from the client's setting. It throws a `StorageException` when Bigtable cannot be reached. It does not repair anything. Table or column family changes while the store is in use, and data changes that bypass the library, are not supported.

## Records

Row keys (BT-3, BT-4). `A` is the UTF-8 bytes of `aid`, `H` is the 8-byte big-endian FNV-1a 64 hash of `A`, and `L16` is the byte length of `A` as an unsigned 16-bit big-endian number:

```text
head row key    = H || L16(len(A)) || A
journal row key = H || L16(len(A)) || A || U64BE(seq_nr)
```

Because the length is part of the key, the key of another `aid` is never a prefix of it, even when the hashes collide. A decimal string, a delimiter or `prefix + 0xff` is never used to build a range. `aid` is `typeName-value` and its UTF-8 size is at most 1024 bytes.

Envelopes (BT-D6a). All integers are big-endian:

```text
event    = 0x01 || U16 len(aid) || aid || U64 seq_nr || I64 occurred_at_ns
           || U32 len(manifest) || manifest || U32 len(payload) || payload
snapshot = 0x01 || U64 seq_nr || U32 len(manifest) || manifest || U32 len(payload) || payload
```

Unused trailing bytes, a length that does not match, an unknown version byte and invalid UTF-8 are storage errors. `occurred_at_ns` is a signed 64-bit count of nanoseconds since the Unix epoch.

Cells. Every cell is written at timestamp 0 and each column holds exactly one cell:

| Table and cell | Value |
|:--|:--|
| head / `head:seq_nr` | 20 ASCII digits, zero padded, from 1 to 2^53 - 1 |
| head / `head:last_event` | the event envelope of that `seq_nr` |
| head / `head:has_snapshot` | ASCII `1` when that append carried a snapshot, `0` for an events-only append |
| head / `snapshot:current` | the current snapshot envelope; absent when no snapshot was ever written |
| journal / `event:envelope` | the event envelope of the row's `seq_nr`; never deleted |
| journal / `event:has_snapshot` | ASCII `1` or `0`, the same as the head; never deleted |
| journal / `history:state` | `active` when the history is written out, `retired` after it was deleted |
| journal / `history:envelope` | the history snapshot envelope; deleted when the row becomes `retired` |

When a retention count is not set, no history cells are created. For a head with `has_snapshot = 1`, the `seq_nr` of `snapshot:current` equals the head `seq_nr`; for `has_snapshot = 0` it is smaller.

## Writes and reads

An append runs these steps in order (BT-7):

1. Read the head row. A missing row means `h = 0`. A row that lacks a required cell is not a new aggregate; it is a `StorageException`.
2. A new `seq_nr` that is less than or equal to `h` is an `OptimisticLockException`; one greater than `h + 1` is a `ContractViolationException` (rule `W-8`).
3. When `h > 0`, write the event `h` out to the journal, with its history when needed. The row is created by one conditional update only while `event:envelope` is absent. An existing row is never overwritten; its event and flag are checked. If this cannot be confirmed, the append fails with a `StorageException` and the head does not advance.
4. Update the head row with one conditional update. The condition is that the latest `head:seq_nr` cell holds the `h` that was read (or, for a new aggregate, that the cell does not exist). All versions of each replaced column are deleted and the new value is written in the same mutation. **The success of this update is the commit point of the append.** If the condition does not hold, another writer committed first and the append fails with an `OptimisticLockException` that does not contain the head number. If the response never arrives, the append fails with a `StorageException` of unknown outcome and is not resent; resending the same envelope then fails with an `OptimisticLockException` when the first update went through.
5. Write the new event out to the journal and run retention. A failure here is reported and postponed; it never changes the committed append.

The size of every envelope, including the snapshot envelope, is checked against `envelope_limit_bytes` right after serialization, before the head is read. A larger envelope is a `ContractViolationException` of rule `BT-16` with the `seq_nr`, and nothing is written.

Reads. The latest snapshot is read from one head row, so the head number and the current snapshot always belong together. Events are read by first reading the head and then using that `h` for the whole read, with `start = max(1, seqNr)`:

- no head, or `start > h`: an empty result, and the journal is not read;
- `start = h`: only the event in the head, and the journal is not read;
- `start < h`: the journal rows `[start, h)` read as one range (start closed, end open), followed by the event in the head. Missing rows in the range are a `StorageException`.

A head that advances during the read is never mixed into the result. The Bigtable client assembles split cells and rows and resumes an interrupted read after the last completed row.

## Retention

Retention keeps the newest `n` snapshots of an aggregate where `n` is the keep count of `RetentionPolicy.delete(n)`. It runs after a committed append that carried a snapshot.

1. Read the `history:state` cell of the journal rows `[1, h]` of the aggregate, only that cell.
2. Take the rows whose state is `active`, add the head `h`, keep the newest `n`, and select the rest.
3. For each selected row, one conditional update that applies only while the state is `active` deletes `history:envelope` and sets `history:state` to `retired`. The event cells stay.

A delayed writer can never revive deleted history, because a journal row is created only while `event:envelope` is absent and a `retired` row is accepted as it is. A retention failure does not change the committed append: it is logged at WARN on the logger of `BigtableEventStore` and delivered once to the optional `EventStoreConfig.Builder.retentionFailureListener`. It runs again at the next snapshot append.

## Operations

### Failures

A failure of the write-out of the new tail event after the commit is logged at WARN on the logger `com.github.j5ik2o.event.store.adapter.java.bigtable.journal`. The listener is not called for it. The next append writes the missing row before it advances the head.

### Changing the retention count

Changing `keep_snapshot_count` is a maintenance procedure that you run outside the library, with every client stopped. The library does not provide a tool for it.

1. Stop every client of the store and confirm that in-flight work has finished. Record the old and new settings and your progress somewhere you can resume from.
2. For each aggregate, finish the pending write-out of the tail row with the old count, then retire the history that falls outside the new count: keep the newest `n` active rows and retire the rest as in the Retention section. Do not change events, flags, heads or current snapshots, and do not recreate history that was deleted.
3. When every aggregate is processed, change `keep_snapshot_count` in the configuration rows of both tables to the new value, then restart the clients so that each one verifies the new setting.

If the procedure fails part-way, keep the clients stopped and run it again with the same target. Rows that are already retired are not processed again. When you increase the count, or set a count where there was none, deleted history is not restored; the new setting applies from the next snapshot append.
