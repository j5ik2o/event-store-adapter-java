# Spanner backend design

[日本語](spanner-backend.ja.md)

This record maps the Java Spanner backend to the hub's Spanner profile for common contract v5. The specification and conformance data 1.3.0 remain the reference. The user-facing format is described in [Spanner storage schema](../SPANNER_DATABASE_SCHEMA.md).

## 1. Public types and resource ownership

- `SpannerRetentionPolicy` is `none()`, `delete(n)` or `ttl(n, graceDays)`. The grace period is a positive number of days (SPN-D7); core's `RetentionPolicy.ttl` counts seconds, so it is not reused and the core contract is unchanged.
- `SpannerStorageConfig` holds the table prefix, the retention policy (default none), and `transactionRetryLimit` (default 5, the first attempt is not counted). `build()` rejects an invalid prefix, a keep count below 1, a grace below 1 day, a negative retry limit and `changeFeed(true)` as configuration errors (SPN-D8).
- `SpannerStorage.open(DatabaseClient, SpannerStorageConfig)` verifies the layout and returns the storage. The caller builds and owns the `DatabaseClient` (credentials, emulator host); the backend never creates, closes or configures it. `google-cloud-spanner:6.124.0` is an `api` dependency because `DatabaseClient` is part of the public signature.
- `SpannerEventStore.create` provides the synchronous interface. `createAsync` runs on the calling thread and returns completed futures, as the memory and SQLite backends do.

## 2. Rules, implementation, and tests

Implementation classes are under `src/main/java/.../spanner/`. Tests are under `src/test/java/.../spanner/`, run against the pinned Cloud Spanner emulator (`SpannerEmulator`), and none of them is skipped when Docker is absent.

| Rule | Behavior | Implementation | Tests | Conformance cases |
|---|---|---|---|---|
| SPN-1 / SPN-D1b | Five tables derived from one prefix, each name a valid identifier of at most 128 characters; SERIALIZABLE writes; strong reads | `SpannerTableNames`, `SpannerTransactions`, `SpannerRead` | `SpannerStorageConfigTest`, `SpannerTransactionRetryTest`, `SpannerReadTest` | `core-*` |
| SPN-2 / SPN-D5 | Open only verifies dialect, columns and types, primary keys, the configuration row and the row deletion policy; it never runs DDL | `SpannerStorage`, `SpannerLayout` | `SpannerOpenTest` (including that a dropped table is not recreated) | `spanner-seeded-*` |
| SPN-3 | `seq_nr` from 1 to 2^53-1; `occurred_at_ns` is a signed `INT64` of nanoseconds, never a `TIMESTAMP`; the TTL mark `expired_at` is a separate column | `SpannerAppend`, `SpannerRead` | `SpannerAppendTest` (boundaries, 2^53-1), `SpannerJsFormatTest` | `occurred-at-*`, `seq-*` |
| SPN-4 / K-1 / K-2 | Key is the exact pair `(hash_prefix, aid)`; `hash_prefix` is FNV-1a 64 of the UTF-8 `aid` as 8 big-endian bytes, never signed, reduced or hex | `SpannerKey` | `SpannerKeyTest`, `SpannerAppendTest`, `SpannerJsFormatTest` | `hash-fnv1a64-*`, `aid-*` |
| SPN-5 | `INT64` values are bound as `long`; payload bytes are bound as `BYTES` | `SpannerAppend`, `SpannerRead` | `SpannerAppendTest` | `occurred-at-*` |
| SPN-6 | Head check and all writes in one SERIALIZABLE read-write transaction; only a primary key collision on the journal or the new head is an optimistic lock conflict; a history collision is a storage error | `SpannerAppend` | `SpannerAppendTest`, `SpannerTransactionRetryTest` | `core-*` |
| SPN-7 | On `ABORTED` the transaction is retried from scratch with the same envelope up to the limit, then fails as a storage error; nothing is notified or numbered inside an attempt | `SpannerTransactions` | `SpannerTransactionRetryTest` | `spanner-aborted-retry-within-limit`, `spanner-aborted-retry-limit-exceeded` |
| SPN-8 | A lost commit response is a storage error of unknown outcome; no compensating write; resending the same envelope is an optimistic lock conflict | `SpannerTransactions` | `SpannerTransactionRetryTest` | `spanner-commit-response-lost` |
| SPN-9 | Head and `snapshot_current` are read in one strong read-only transaction | `SpannerRead` | `SpannerReadTest` (a write committed during the read is not mixed into the pair) | `core-*` |
| SPN-10 | Head and journal are read in one strong read-only transaction from `max(1, seqNr)` to the head; a gap is a storage error | `SpannerRead` | `SpannerReadTest` | `core-*` |
| SPN-11 / SPN-12 | After the append commits, a separate SERIALIZABLE transaction keeps the newest `n` unmarked history rows; delete mode deletes only history rows, TTL mode sets `expired_at = CURRENT_TIMESTAMP()` once on unmarked rows | `SpannerRetention` | `SpannerRetentionTest` | `core-retention-*` |
| SPN-13 / S-4 | A retention failure never changes a committed append; it is logged and delivered once to the listener; a thrown `Error` from the log or the listener is isolated | `SpannerRetention`, `SpannerEventStore` | `SpannerPostCommitNotificationTest`, `SpannerAsyncEventStoreTest`, `SpannerRetentionTest`; each asserts that the failure path ran | `core-retention-failure-after-commit`, `core-retention-query-failure` |
| SPN-14 / SPN-15 | Change feed | Not applicable: not provided, per SPN-D8 | None | None |
| SPN-16 | The TTL grace in days must equal the client value, `ttl_grace_days` and the `INTERVAL n DAY` of the row deletion policy; delete mode has no policy | `SpannerStorageConfig`, `SpannerLayout` | `SpannerOpenTest`, `SpannerStorageConfigTest` | `spanner-seeded-ttl-*`, `spanner-ttl-grace-days-not-positive` |
| SPN-17 | `change_feed_enabled=true` in the row, or a configuration requesting the feed, is a configuration error | `SpannerStorageConfig`, `SpannerLayout` | `SpannerStorageConfigTest`, `SpannerOpenTest` | `spanner-change-feed-unsupported`, `spanner-seeded-change-feed-enabled` |

All 79 required Spanner conformance cases pass against the real emulator, with zero failures and zero unverified cases; `SpannerCaseRunnerTest` runs each of them, and `ConformanceRunnerTest` writes the results to `build/reports/conformance/report.json` under `spanner-emulator`. Bigtable-only cases and DynamoDB layout cases do not apply to Spanner. A required case that is not executed fails the build.

## 3. Rules outside the shared data

| Rule outside the conformance data | Evidence |
|---|---|
| Storage format: the five tables, key columns and `INT64` nanoseconds equal the JavaScript v5 layout, and rows written in that shape are read and extended | `SpannerJsFormatTest` (the DDL is copied from the JavaScript implementation; the hash is computed independently) |
| Concurrency: concurrent first appends of `seq_nr=1` store exactly one event | `SpannerTransactionRetryTest.concurrentFirstAppendsOfSequenceOneStoreExactlyOneEvent` |
| Request splitting: retention works in chunks of at most 500 rows and selects its targets again in each transaction | `SpannerRetentionTest.aLargeCleanUpIsSplitIntoSeveralTransactionsAndStillKeepsTheNewestRows` (1198 old rows) |
| Public usage: the README example runs from another package through public types only and writes `build/reports/public-api-usage/spanner.json` | `SpannerUserAccountExampleTest` |

## 4. Error classification

| Event | Category |
|---|---|
| Sequence number at or below the head; primary key collision on the journal or the new head | Optimistic-lock error |
| Sequence number other than head plus one (W-8) | Contract violation |
| `ABORTED` above the retry limit, a lost commit response, a failed read, a history key collision, a missing journal row in the requested range, an unreachable database | Storage error |
| Missing or mismatched table, column, primary key, configuration row or row deletion policy; invalid prefix or retention values; a change feed request | Configuration error |
| Serialization or deserialization failure | Serialization error |

The optimistic-lock message holds only the aid, the `seq_nr` and the head; driver text and table names stay out of it (E-2).

## 5. Verification limits

The following are not counted as verified success.

| Item | Limit | Other evidence |
|---|---|---|
| Real-service conflict detection, capacity limits and physical TTL deletion | The emulator does not reproduce them (chapter 9 of the profile) | The marked rows and the row deletion policy are checked; physical deletion is not |
| Multiple emulator sessions and real contention across machines | One emulator process, one JVM | Concurrent appends from four clients in `SpannerTransactionRetryTest` |
| A lost commit response from a real network | Simulated with an injected failure after a real commit | `spanner-commit-response-lost`, `SpannerTransactionRetryTest` |
| Change feed, per SPN-14 and SPN-15 | Not provided in the first version, per SPN-D8 | Not applicable |
