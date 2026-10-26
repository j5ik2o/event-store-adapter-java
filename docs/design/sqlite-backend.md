# SQLite backend design

[日本語](sqlite-backend.ja.md)

This record maps the Java SQLite backend to the hub's SQLite profile for common contract v5. The specification and conformance data 1.3.0 remain the reference. The user-facing format is described in [SQLite storage schema](../SQLITE_DATABASE_SCHEMA.md).

## 1. Public types and connection ownership

- `SqliteStorageConfig` holds the retention policy, which defaults to `RetentionPolicy.none()`, and the write-lock wait, which defaults to 5 seconds. `build()` rejects retention using time to live (TTL), null values, negative waits, and waits greater than `Integer.MAX_VALUE` milliseconds as configuration errors. The public configuration has no change-feed option.
- `SqliteStorage` owns one local file and one Java Database Connectivity (JDBC) connection. `initialize` accepts only an uninitialized empty file. `open` validates an existing file without initializing it. `close` is idempotent. A `ReentrantLock` serializes operations on this storage instance.
- `SqliteEventStore.create` provides the synchronous interface. `createAsync` runs operations on the calling thread and returns completed futures, as the memory backend does.
- Callers do not supply a `Connection` or `DataSource`. The backend controls every connection so it can disable shared caches and verify the pragma settings.
- The public API exposes no `java.sql` types. `org.xerial:sqlite-jdbc:3.53.4.0` is an `implementation` dependency.

## 2. Rules, implementation, and tests

Implementation classes are under `src/main/java/.../sqlite/`. Most tests are under `src/test/java/.../sqlite/`. The table lists class names and case identifiers from `conformance/`.

| Rule | Behavior | Implementation | Tests | Conformance cases |
|---|---|---|---|---|
| SQ-1 | Private cache; `locking_mode=NORMAL`, `journal_mode=WAL`, `synchronous=FULL`, `read_uncommitted=OFF`; configurable wait | `SqliteConnections` | `SqliteConnectionsTest`, `SqliteStorageConfigTest` | None |
| SQ-2 | Separate explicit initialization from opening; validate configuration and layout when opening | `SqliteStorage`, `SqliteLayout` | `SqliteInitializeTest`, `SqliteOpenTest`, `SqliteAidCollationTest` | `sqlite-seeded-*` |
| SQ-3 | Validate inputs, then bind and read `occurred_at_ns` as a signed 64-bit integer, never REAL | `SqliteAppend`, `SqliteRead`, `SqliteEventStore` | `SqliteAppendTest`, including negative one nanosecond, the `Long.MIN/MAX` nanosecond boundaries, and `typeof` | `occurred-at-*` |
| SQ-4 | Separate manifest and payload columns; bind aid parameters for exact matches | `SqliteAppend`, `SqliteRead` | `SqliteAppendTest`, including aid values with `%` and `_`; `SqliteRustFormatTest` | `aid-*` |
| SQ-5 | Append in one transaction on one connection; start `BEGIN IMMEDIATE` before checking the head | `SqliteAppend`, `SqliteTransactions` | `SqliteConcurrencyTest` | `sqlite-write-lock-busy` |
| SQ-6 | Successful `COMMIT` is the commit point; roll back earlier failures; classify a failed `COMMIT` as a storage error | `SqliteAppend` | `SqliteAppendTest`, including zero rows in all tables after a failure before commit | Failures before commit in `core-*` |
| SQ-7 | Classify BUSY, LOCKED, and wait expiration as storage errors; only duplicate journal or new-head primary keys are optimistic-lock errors | `SqliteAppend`, `SqliteTransactions` | `SqliteAppendTest`, `SqliteConcurrencyTest` | `sqlite-write-lock-busy`, `store-shared-persistent` |
| SQ-8 | Read the head and `snapshot_current` in the same read transaction | `SqliteRead` | `SqliteReadTest` | Reads in `core-*` |
| SQ-9 | Read the head and journal in the same read transaction, starting at `max(1, seqNr)`; report a gap as a storage error | `SqliteRead` | `SqliteReadTest` | Reads in `core-*` |
| SQ-10 | After committing an append with a snapshot, delete only old history in a separate `BEGIN IMMEDIATE` transaction | `SqliteRetention`, `SqliteEventStore` | `SqliteRetentionTest` | `core-retention-*` |
| SQ-11 | Report retention failures without changing a successful append; reject TTL retention and a retention count of zero | `SqliteRetention`, `SqliteStorageConfig` | `SqliteRetentionTest`, `SqliteStorageConfigTest` | `core-retention-failure-after-commit`, `sqlite-ttl-unsupported` |
| SQ-12 | Append change-feed transitions | Not applicable because the change feed is not provided, per SQ-D8 | None | None |
| SQ-13 | Subscribe to the change feed, a draft rule | Not applicable for the same reason | None | None |
| SQ-14 | Set and read back WAL and FULL on every connection; reject a mismatch as a configuration error | `SqliteConnections` | `SqliteConnectionsTest` | None |
| SQ-15 | Require SQLite 3.51.3 or later and compare version components as integers | `SqliteConnections.meetsMinimumVersion` | `SqliteConnectionsTest`, `SqliteDriverSmokeTest` | None |
| SQ-16 | Create every table as STRICT and validate its declaration | `SqliteLayout` | `SqliteInitializeTest`, `SqliteOpenTest` | None |
| SQ-17 | Use `BEGIN IMMEDIATE` for appends and explicit read transactions for reads | `SqliteAppend`, `SqliteRead` | `SqliteConcurrencyTest`, `SqliteReadTest` | None |
| SQ-18 | Retain change-feed transitions | Not applicable because the change feed is not provided | None | None |
| SQ-19 | Compare the retention count with config; reject a mismatch as a configuration error | `SqliteLayout.verify` | `SqliteOpenTest` | `sqlite-seeded-keep-count-mismatch`, `sqlite-seeded-layout-version-mismatch`, `sqlite-seeded-configuration-match` |
| SQ-20 | Record `change_feed_enabled=0`; reject stores with value 1; do not create `head_transition` | `SqliteLayout` | `SqliteInitializeTest`, `SqliteOpenTest` | `sqlite-seeded-change-feed-enabled`, `sqlite-change-feed-unsupported` |

The 67 required SQLite conformance cases pass against real files, with zero failures and zero unverified cases. `sqlite-change-feed-unsupported` is unrepresentable because the public configuration cannot express `change_feed=true`, so it is excluded from the required case set. DynamoDB layout cases do not apply to SQLite. Results are written to `build/reports/conformance/report.json`.

The initial implementation referenced Rust's local `main` at `fe2ae0b`. The coordinator later compared it with `origin/main` at `c29f305`. The four changed files under `lib/src/sqlite/` differ only in visibility modifiers. Their contents match after those modifiers are removed; the storage format is unchanged.

## 3. Tables and column types

All five tables are STRICT and use the same column types and constraints as the Rust implementation.

| Table | Columns and types | Primary key |
|---|---|---|
| `journal` | `aid` TEXT with BINARY collation, `seq_nr` INTEGER from 1 through 2^53-1, `occurred_at_ns` INTEGER, `manifest` TEXT, `payload` BLOB; all NOT NULL | `(aid, seq_nr)` |
| `head` | `aid` TEXT with BINARY collation, `type_name` TEXT, `seq_nr` INTEGER from 1 through 2^53-1, `event_occurred_at_ns` INTEGER, `event_manifest` TEXT, `event_payload` BLOB; all NOT NULL | `aid` |
| `snapshot_current` | `aid` TEXT with BINARY collation, `seq_nr` INTEGER from 1 through 2^53-1, `manifest` TEXT, `payload` BLOB; all NOT NULL | `aid` |
| `snapshot_history` | The same columns as `snapshot_current` | `(aid, seq_nr)` |
| `config` | `singleton` INTEGER equal to 1, `store_id` TEXT, `layout_version` INTEGER, `keep_snapshot_count` INTEGER that is NULL or at least 1, `retention_mode` TEXT equal to `delete`, `change_feed_enabled` INTEGER equal to 0 or 1; all except `keep_snapshot_count` are NOT NULL | `singleton` |

There is no `head_transition` table.

## 4. Configuration validation and error categories

Opening validates the following in one read transaction. It compares pragma results rather than SQL text, using `pragma_table_list.strict`, `pragma_table_info`, and the primary-key collation from `pragma_index_xinfo`. The aid column collation is checked by meaning with a non-writing compound SELECT ordered by aid that reads no stored rows (`SqliteAidCollationTest`).

| Check | Result on mismatch |
|---|---|
| All five tables exist and are STRICT | Configuration error |
| Column names, types, NOT NULL flags, and positions in the primary key match | Configuration error |
| Both the aid column and the primary-key index use BINARY collation in all four tables that have aid | Configuration error |
| `config` contains exactly one row | Configuration error |
| `layout_version` is 1 | Configuration error |
| `store_id` is a lowercase universally unique identifier (UUID) | Configuration error |
| `retention_mode` is `delete` | Configuration error |
| `change_feed_enabled` is 0; value 1 requests an unsupported change feed | Configuration error |
| `keep_snapshot_count` matches the client's retention count; NULL means no history | Configuration error |
| The file is uninitialized | Configuration error; no tables are created |
| The file does not exist | Storage error; no file is created |

Runtime errors are classified as follows.

| Event | Category |
|---|---|
| Sequence number at or below the head; duplicate journal or new-head primary key | Optimistic-lock error |
| Sequence number other than head plus one, per W-8 | Contract violation |
| Write-lock wait expiration, BUSY, LOCKED, other constraint violations, or failed COMMIT | Storage error |
| Missing journal sequence number within the requested range | Storage error |
| Serialization or deserialization failure | Serialization error |
| TTL retention, invalid lock wait, a file name containing `?`, a SQLite version older than 3.51.3, or mismatched connection settings | Configuration error |

## 5. Minimum SQLite version

The minimum is **3.51.3**. The backend reads `SELECT sqlite_version()` from the open connection and compares three integer components. For example, `3.100.0` meets the minimum and `3.51` does not. An older or malformed version is a configuration error. `SqliteUserAccountExampleTest` records the actual bundled version in the `sqlite_version` field of `build/reports/public-api-usage/sqlite.json`. The observed version for `sqlite-jdbc 3.53.4.0` was 3.53.4.

## 6. Connection settings

| Setting | Value |
|---|---|
| Open mode | Private cache; opening does not create missing files; only `initialize` creates them |
| File name | Converted to an absolute path; names containing `?` are rejected |
| `busy_timeout` | `lockWait` in milliseconds, defaulting to 5 seconds |
| `locking_mode` | NORMAL |
| `journal_mode` | WAL |
| `synchronous` | FULL, numeric value 2 |
| `read_uncommitted` | OFF, numeric value 0 |
| Auto-commit | Left enabled; transactions use explicit SQL `BEGIN IMMEDIATE`, `BEGIN`, `COMMIT`, and `ROLLBACK` |

The backend reads the pragma values back after setting them and rejects mismatches as configuration errors.

## 7. Retention

- The only retention mode is `delete`. `none()` creates no history rows. `delete(n)` retains the newest `n` history rows.
- TTL retention is a configuration error.
- After an append with a snapshot commits, retention runs in a separate `BEGIN IMMEDIATE` transaction. It never deletes journal rows, the head, or the current snapshot.
- A retention failure preserves the successful append. After releasing the lock, the backend emits a WARN log and notifies the optional listener once. A later append with a snapshot cleans up remaining history. An append without a snapshot does not run retention.

## 8. Verification limits

The following are not counted as verified success.

| Item | Limit | Other evidence |
|---|---|---|
| Whether storage honors `fsync`; power loss, forced process termination, and long-running access from multiple processes | Chapter 10 of the profile declares these unverified; this work does not reproduce them | Remain unverified |
| SQLite 3.51.3 itself and rejection by a real library older than 3.51.3 | Only one bundled SQLite version is executed | Version-comparison unit tests in `SqliteConnectionsTest` |
| A mismatch when pragma values are read back | No reproducible condition with the actual SQLite library | Unit tests of `verifySettings` |
| Network-filesystem detection | No detection mechanism | Documented as unsupported |
| Change feed, per SQ-12, SQ-13, and SQ-18 | Not provided in the first version, per SQ-D8 | Not applicable |
