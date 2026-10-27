# Bigtable backend design

[日本語](bigtable-backend.ja.md)

This record maps the Java Bigtable backend to the hub's Bigtable profile for common contract v5. The specification and conformance data 1.3.0 remain the reference. The user-facing format is described in [Bigtable storage schema](../BIGTABLE_DATABASE_SCHEMA.md).

## 1. Public types and resource ownership

- `BigtableStorageConfig` holds the journal and head table IDs, the retention policy (core `RetentionPolicy`, default none) and `envelopeLimitBytes` (default 1048576, from 17 to 8388608). `build()` rejects a change feed request (BT-17), a TTL policy (BT-13), a null policy, a missing or empty table ID, the same ID for both tables, and a limit out of range as configuration errors, before any request. The core `RetentionPolicy` is reused because Bigtable's retention counts only snapshots, so the core contract is unchanged.
- `BigtableStorage.open(BigtableDataClient, BigtableTableAdminClient, BigtableStorageConfig)` verifies the layout and returns the storage. `BigtableStorage.initialize(BigtableDataClient, BigtableStorageConfig)` writes only the two configuration rows. The caller builds and owns both clients (credentials, app profile pinned to one cluster, metrics, emulator host); the backend never creates, closes or configures them and never creates a table. `google-cloud-bigtable:2.85.0` is an `api` dependency because the clients are part of the public signature. It resolves `grpc-api` to the same version as Spanner (1.83.0).
- `BigtableEventStore.create` provides the synchronous interface. `createAsync` runs on the calling thread and returns completed futures, as the other backends do.

## 2. Rules, implementation, and tests

Implementation classes are under `src/main/java/.../bigtable/`. Tests are under `src/test/java/.../bigtable/`, run against the pinned Cloud Bigtable emulator (`BigtableEmulator`), and none of them is skipped when Docker is absent.

| Rule | Behavior | Implementation | Tests | Conformance cases |
|---|---|---|---|---|
| BT-1 | One cluster for all operations. This is a setting of the caller's clients and cannot be checked by the library | Not applicable in the library (caller owns the clients) | None. Documented in the schema document | None (out of scope in the data) |
| BT-2 | Column families exist; data families keep exactly one version; `config` has no age rule | `BigtableLayout` | `BigtableOpenTest` (missing family, age-based GC, more than one version) | `bigtable-seeded-*` |
| BT-3 / BT-4 / K-1 | Row key `H \|\| L16 \|\| A`, journal key plus `U64BE(seq_nr)`; ranges use the key, not `prefix + 0xff` | `BigtableKey` | `BigtableKeyTest`, `BigtableHashCollisionTest` (hashes forced to collide) | `hash-fnv1a64-*`, `aid-*` |
| BT-5 | A journal row is created by one conditional update only while `event:envelope` is absent; an existing row is checked; a retired row is accepted as is | `BigtableAppend.writeJournalRow` | `BigtableAppendTest`, `BigtableRetentionTest.aDelayedWriterCannotReviveHistory…` | `core-*` |
| BT-6 | Cells are written at timestamp 0; a replaced column is deleted and rewritten in one mutation; the condition reads only the latest cell | `BigtableRows` (`replaceCell`, `latestCellEquals`) | `BigtableAppendTest.afterEveryAppend…`, `aConditionThatMatchesOnlyAnOlderCellVersion…` | `core-*` |
| BT-7 | Read head, write the previous event out, conditionally update the head (commit point), write the new tail out; a response that never arrives is a storage error and is not resent | `BigtableAppend`, `BigtableRows.checkAndMutate` | `BigtableAppendTest`, `BigtableTransportTest.aLostConditionalUpdateResponseIsNeverResent…`, `BigtableConcurrencyTest` | `bigtable-previous-event-write-failure`, `bigtable-new-tail-write-failure`, `bigtable-cas-response-lost`, `core-*` |
| BT-8 | The latest snapshot and the head number come from one head row | `BigtableRead.readSnapshot` | `BigtableReadTest` | `core-*` |
| BT-9 | Read the head first and keep its `h`; no journal read for `start >= h`; range `[start, h)` | `BigtableRead.readEvents` | `BigtableReadTest` (journal rows deleted to prove no read; a head advancing during the read) | `core-*` |
| BT-10 | The client assembles split rows and resumes after the last completed row; a gap in the range is a storage error | `BigtableRead.readJournal` | `BigtableReadTest` (gaps, 12 rows of 300 KB), `BigtableTransportTest.anInterruptedReadRowsResumes…` | `core-storage-read-events` |
| BT-11 | Retention scans the `history:state` cell of rows `[1, h]` and adds `h` | `BigtableRetention` | `BigtableRetentionTest`, `BigtableHashCollisionTest` | `core-retention-*` |
| BT-12 | A candidate becomes `retired` by one conditional update that applies only while it is `active`; events stay | `BigtableRetention.retire` | `BigtableRetentionTest` | `core-retention-*` |
| BT-13 | A retention failure never changes a committed append; it is logged and delivered once to the listener; a thrown `Error` from the log or the listener is isolated; TTL is a configuration error | `BigtableEventStore`, `BigtableRetention`, `BigtableStorageConfig` | `BigtablePostCommitNotificationTest` (each test asserts that the path ran), `BigtableRetentionTest`, `BigtableStorageConfigTest` | `core-retention-failure-after-commit`, `core-retention-query-failure`, `bigtable-ttl-unsupported` |
| BT-14 / BT-15 | Change feed | Not applicable: not provided, per BT-D8 | None | None |
| BT-16 | Envelope limit, checked before the head is read; both configuration rows must hold the client's limit | `BigtableAppend`, `BigtableLayout` | `BigtableAppendTest` (exactly at the limit, one byte over, snapshot over), `BigtableOpenTest`, `BigtableStorageConfigTest` | `bigtable-envelope-over-limit`, `bigtable-envelope-limit-*`, `bigtable-seeded-*-envelope-limit-mismatch` |
| BT-17 | A request for a change feed is a configuration error | `BigtableStorageConfig` | `BigtableStorageConfigTest` | `bigtable-change-feed-unsupported` |
| BT-D1 / BT-D6a | Open only verifies; the envelope has the fixed binary format | `BigtableStorage`, `BigtableLayout`, `BigtableEnvelope` | `BigtableOpenTest`, `BigtableEnvelopeTest` | `bigtable-seeded-*`, `bigtable-envelope-*` |

All 87 required Bigtable conformance cases pass against the real emulator, with zero failures and zero unverified cases; `BigtableCaseRunnerTest` runs each of them, and `ConformanceRunnerTest` writes the results to `build/reports/conformance/report.json` under `bigtable-emulator`. DynamoDB layout cases and the other backends' own cases do not apply to Bigtable. A required case that is not executed fails the build.

## 3. Rules outside the shared data

| Rule outside the conformance data | Evidence |
|---|---|
| Storage format: the two tables, the row keys and the envelope bytes equal the Rust v5 layout, and rows written in that shape are read and extended | `BigtableRustFormatTest` (the rows and the hash are assembled in the test, independent of the Java code); `theHeadSeqNrIsTwentyAsciiDigitsWhateverTheDefaultLocale` and `anAsciiHeadWrittenByAnotherWriterIsUpdatedWhateverTheDefaultLocale` check that `head:seq_nr` is 20 ASCII digits under a default locale with non-ASCII digits |
| Concurrency: of two writers that read the same head only one commits; concurrent first appends of `seq_nr=1` store exactly one event; a writer interrupted between the write-out and the update loses | `BigtableConcurrencyTest` |
| Request splitting and resumption: split rows are reassembled; an interrupted `ReadRows` resumes from the last completed row without gaps or duplicates | `BigtableReadTest.manyLargeRowsAreReassembled…`, `BigtableTransportTest.anInterruptedReadRowsResumes…` |
| Authentication seam, data and admin: the caller's Bearer token is on `GetTable`, `ReadRows` and `CheckAndMutateRow`; a failing credential writes nothing | `BigtableTransportTest` (a relay in front of the emulator records the `authorization` header on the server side) |
| One send only: the conditional update is never resent, and `MutateRow` is never used by the library | `BigtableTransportTest` |
| Public usage: the README example runs from another package through public types only and writes `build/reports/public-api-usage/bigtable.json` | `BigtableUserAccountExampleTest` |

## 4. Error classification

| Event | Category |
|---|---|
| Sequence number at or below the head; a head update whose condition does not hold | Optimistic-lock error |
| Sequence number other than head plus one (W-8); an envelope over the limit (BT-16) | Contract violation |
| A lost update response, a failed read or write, a malformed envelope or head row, a missing journal row in the requested range, an unreachable Bigtable, a failing credential | Storage error |
| Missing table or column family, a data family that does not keep exactly one version, a missing, malformed or mismatching configuration row, invalid settings, a change feed or TTL request | Configuration error |
| Serialization or deserialization failure | Serialization error |

The optimistic-lock message holds only the aid, the `seq_nr` and, when the head was read, its number; client text and table IDs stay out of it (E-2).

## 5. Verification limits

The following are not counted as verified success.

| Item | Limit | Other evidence |
|---|---|---|
| Real-service behaviour: multi-cluster routing, replication delay, capacity limits and physical garbage collection | The emulator does not reproduce them | Garbage-collection rules and configuration are verified; physical deletion is not |
| App profile pinned to one cluster (BT-1) | The caller's responsibility; the library cannot see it | Documented |
| Change feed (BT-14, BT-15) | The emulator does not support `ReadChangeStream`; not provided in the first version (BT-D8) | Not applicable |
| An interrupted `ReadRows` in the middle of a row | The emulator sends one row per message, so the relay cuts between rows | The test checks that the resumed range never starts before the first one and that the result is complete and ordered |
| The authentication header on a plaintext channel | The emulator client uses a plaintext channel | Observed in the test as the `authorization` header recorded by the relay |
| Several emulator processes, real contention across machines | One emulator process, one JVM | Eight concurrent writers in `BigtableConcurrencyTest` |
