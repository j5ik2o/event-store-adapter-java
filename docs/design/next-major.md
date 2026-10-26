> This document is a design record from the time v5 was built. For current usage, check the [README](../../README.md) and the current code.

# Design document for the next major version (2.0.0) (java)

- Target repository: j5ik2o/event-store-adapter-java
- Status: design proposal. Reflects the coordinator's review (first round). The points that needed a decision were decided on 2026-10-06 (the decisions and their reasons are recorded in chapter 9).
- Normative sources: the shared contract of the hub j5ik2o/event-store-adapter (`docs/spec/core-contract.md`), the DynamoDB profile, the memory profile, the implementation plan (`docs/plan/implementation-plan.md`), and the decision records (`docs/adr/`).
- Origin of the rule numbers: T, H, W, R, E, and S come from the shared contract; D, DY, and P come from the DynamoDB profile; MEM comes from the memory profile. "IP" is a section of the implementation plan. "CR" is `conformance/README.md`.
- This document does not change the specification. It does not add rules either. Points where the reading of the specification was unclear are written in chapter 10.
- The API and implementation proposals in this document are "recommended proposals". They are not requirements of the specification. For the points where options diverged, chapter 9 records the decision and its reason.
- No code was written in the work that produced this document. The implementation is done in the PR sequence of chapter 7.

## 1. Purpose and scope

### 1.1 Purpose

Rewrite this repository to match the shared contract. The current 1.x performs optimistic locking with the aggregate's `version` and the snapshot. The new contract does it with the `seq_nr` of the aggregate head (H-2, W-8). The current API does not match the envelope, the error categories, and the head shape of the new contract. For this reason the API is rebuilt.

### 1.2 What the first major version includes

| Included | Basis |
|---|---|
| Core (value types, envelopes, validation, serializer, error categories, the four operations) | IP-D3, shared contract chapters 1 to 4 |
| Memory implementation (new; the current code has none) | IP-D3, IP-D4, IP-D5, MEM-1 to MEM-13 |
| DynamoDB implementation (rebuilt around three tables and configuration items) | IP-D3, DY-2 to DY-19 (DY-1 has been deleted) |
| Runner for the conformance test data (for both memory and DynamoDB) | Acceptance condition 1 of IP 5 |
| Updates of the README, DATABASE_SCHEMA, and the migration guide | Acceptance conditions 5 and 6 of IP 5 |

### 1.3 What is left out

| Left out | Reason |
|---|---|
| TTL mode for memory | MEM-12. A request for it is a configuration error |
| Change feed for memory | MEM-13. A request for it is a configuration error |
| Persistent memory, clear-all | MEM-1 |
| Automatic loading of old data, migration tool | IP-D8. For java, only a procedure document |
| Permanent compatibility layer for the old API | Chapter 7. Coexistence lasts only during the migration |
| Design of the wrapper layers for kotlin and scala | Separate work in stage 3. Section 2.11 shows only a proposal of the types and operations they use |
| Change feed helper functions (assembling head transitions, the resync helper of DY-15) | Not included (owner decision on 2026-10-06, 9.3). The Streams setting of the head table (DY-3, DY-12) is satisfied as a shape of the write side |

### 1.4 Version number

- The next major version is `2.0.0`. The current `version` file is `1.2.92-SNAPSHOT`.
- The Snapshot of main is published as `2.0.0-SNAPSHOT`. After a squash merge into main, when CI succeeds, `snapshot.yml` publishes the Snapshot (IP 3).
- How to handle a fix needed for 1.x follows chapter 3 of the implementation plan. This document does not cover it.
- The target Java version stays at 11 (the toolchain in `build.gradle.kts`). The API is written without `record`, `sealed`, and `var`.

## 2. Public API

The packages get new names so that they can coexist with the old API (chapter 7, 9.5). The old packages are removed in the stage that deletes the old API.

| Package | Contents |
|---|---|
| `com.github.j5ik2o.event.store.adapter.java.core` | Value types, envelopes, serializer, errors, configuration, types of the four operations |
| `com.github.j5ik2o.event.store.adapter.java.memory` | Memory implementation |
| `com.github.j5ik2o.event.store.adapter.java.dynamodb` | DynamoDB implementation |

The declarations below omit `import` and Javadoc. As in the current code, the `@Nonnull` annotation comes from jsr305. The "Rule" column of each element lists the rule numbers that the element satisfies.

### 2.1 Aggregate ID (T-1, T-11, T-12)

```java
public final class AggregateId {
  public static AggregateId of(String typeName, String value);   // T-1, T-11, T-12
  public String typeName();
  public String value();
  public String asString();   // typeName + "-" + value。ライブラリが組み立てる（T-1）
  // equals / hashCode / toString は asString() に基づく。
}
```

| Item | Contents | Rule |
|---|---|---|
| Construction | `of` builds `typeName + "-" + value` from the type name and the value. It does not use the user's `asString()` or `toString()` | T-1 |
| Type name contains `-` | `of` throws `ContractViolationException` (rule T-11). The value may contain `-` | T-11 |
| Over 1024 bytes | Judged by the number of UTF-8 bytes. It is the total of the type name, the value, and the separator. It is not counted in characters (`String.length()`) | T-12 |
| Empty type name, empty value | Allowed. The specification does not forbid them, and since the aid string contains `-` it never becomes empty (coordinator's answer, 2026-10-06). The conformance data has no such case, so in the implementation PR write unit tests for an empty type name (`-value`), an empty value (`typeName-`), and both empty (`-`) | T-11, T-12 |

The current `AggregateId` is an interface, and users implemented `asString()`. The new API makes it a final class. A user's domain ID calls `AggregateId.of` with the type name and the value. `buildAid` in the conformance data uses a test ID that returns `user_string`. The runner extracts the type name and the value from the test ID, passes them to `AggregateId.of`, and checks that the result does not depend on `user_string` (chapter 5).

### 2.2 Event envelope (T-2, T-3, T-4, T-5, T-13)

```java
public final class EventEnvelope<P> {
  public static <P> Builder<P> builder();
  public AggregateId aggregateId();
  public long seqNr();
  public Instant occurredAt();
  public String manifest();      // 省略時は ""（T-2）
  public P payload();

  public static final class Builder<P> {
    public Builder<P> aggregateId(AggregateId v);
    public Builder<P> seqNr(long v);
    public Builder<P> occurredAt(Instant v);
    public Builder<P> manifest(String v);
    public Builder<P> payload(P v);
    public EventEnvelope<P> build();   // 必須要素の欠落（null・seqNr 未設定）と値域の違反は ContractViolationException
  }
}
```

| Item | Contents | Rule |
|---|---|---|
| Required | Everything except `manifest` is required. If `aggregateId`, `occurredAt`, or `payload` is `null`, or `seqNr` has not been set, `build()` throws `ContractViolationException` (`rule()` is `"T-2"`. If the missing element is `seqNr`, `seqNr()` is empty. Shared contract T-2, E-3, P-42). In the implementation PR, write a unit test for the omission of each element. When `manifest` is omitted, it is the empty string | T-2 |
| Immutable, extensible | A final class and a builder. Existing builder calls do not break when elements are added | T-5 |
| Time | `Instant` (nanosecond precision). It is not replaced by the store's time. A read returns the same value | T-3 |
| Range of time | The range in which the nanoseconds since the epoch fit in a signed 64 bits. Outside the range is a contract violation | T-13 |
| No interpretation | The library does not interpret `manifest` or `payload` | T-4 |

**Note on the T-13 check.** Do not use `long` arithmetic (for example `getEpochSecond() * 1_000_000_000L + getNano()`) to judge the range. Java integer arithmetic does not report overflow. For example, a time with seconds `9_223_372_036` and nanos `854_775_808` exceeds the upper limit by 1 nanosecond, but the result of the `long` expression wraps around to `Long.MIN_VALUE` and looks as if it were in range. Near the valid minimum as well, multiplying only the seconds by 1e9 first can overflow below the lower limit.

- The judgment is made by comparing `Instant`s. The boundaries are `Instant.ofEpochSecond(0, Long.MIN_VALUE)` and `Instant.ofEpochSecond(0, Long.MAX_VALUE)`. If `occurredAt.isBefore(lower limit) || occurredAt.isAfter(upper limit)`, it is a contract violation (T-13).
- The conversion to epoch nanoseconds is done only after the judgment has passed. If the seconds are 0 or more, compute `seconds * 1e9 + nanos`; if negative, compute `(seconds + 1) * 1e9 + (nanos - 1e9)`, guarded with `Math.multiplyExact` and `Math.addExact` (an overflow becomes an exception and is never silently wrapped around).
- In the implementation PR, write unit tests for four boundary values: the maximum (accepted), the maximum + 1 nanosecond (contract violation), the minimum (accepted), and the minimum − 1 nanosecond (contract violation).

The check is placed in the envelope's `build()`. A rule violation during the construction of an envelope from invalid input can also be caught as a failure of the operation (chapter 5). The store operations also re-check the envelope they receive (this is not a defense against a wrongly built envelope; it is so that memory and DynamoDB pass the same check. The check lives in one place in the core).

### 2.3 Snapshot envelope (T-10)

```java
public final class SnapshotEnvelope<A> {
  public static <A> Builder<A> builder();
  public A aggregate();       // 必須（T-10）
  public long seqNr();        // 必須
  public String manifest();   // 省略時は ""

  public static final class Builder<A> {
    public Builder<A> aggregate(A v);
    public Builder<A> seqNr(long v);           // T-9 の一般値域（0 も有効）
    public Builder<A> manifest(String v);
    public SnapshotEnvelope<A> build();   // aggregate が null、または seqNr 未設定なら ContractViolationException（rule は "T-10"。P-42）。単体試験を書く
  }
}

public final class SnapshotReadResult<A> {
  public Optional<SnapshotEnvelope<A>> snapshot();   // なくてもよい（R-2・R-3）
  public long headSeqNr();                           // 読み取り時点のヘッド（R-2）
}
```

- The head's `seq_nr` is not included in `SnapshotEnvelope`. It is returned separately through `SnapshotReadResult.headSeqNr()` (T-10, ADR-0002).
- Writes and reads use the same `SnapshotEnvelope` (T-10).
- The return value of `getLatestSnapshotById` is `Optional<SnapshotReadResult<A>>`. The outer empty means there is no head (the aggregate does not exist) (R-1). The inner empty means there is a head but no snapshot (R-3).

### 2.4 Payload and serializer (T-6, T-7, T-8)

The shape is as follows (owner decision on 2026-10-06, 9.2).

```java
public interface PayloadSerializer<T> {
  byte[] serialize(T value) throws SerializationException;          // T-6, T-7
  T deserialize(byte[] bytes) throws SerializationException;        // T-6, T-7（復元の失敗も同じ分類）
}

public final class JsonPayloadSerializer {
  public static <T> PayloadSerializer<T> of(Class<T> type);                  // T-8（既定は JSON）
  public static <T> PayloadSerializer<T> of(ObjectMapper mapper, Class<T> type);
  public static <T> PayloadSerializer<T> of(ObjectMapper mapper, TypeReference<T> type);
}
```

| Item | Contents | Rule |
|---|---|---|
| Requirement on domain types | Does not require library interfaces such as `Aggregate` or `Event`. It is enough that the type can be serialized with a `PayloadSerializer` | T-6 |
| Metadata | The serializer serializes only the payload (the aggregate state). It does not embed the aid, seq_nr, time, or manifest | T-7 |
| Default | JSON (Jackson. The current dependency is kept) | T-8 |
| `Class<T>` | Currently `Class<E>` and `Class<A>` are passed for each read operation. The new API binds the type when the serializer is created and does not pass it to operations | Chapter 9 |

The current `EventSerializer`, `SnapshotSerializer`, `DefaultEventSerializer`, and `DefaultSnapshotSerializer` are replaced and deleted (section 2.10). Both serialization failures and restoration failures are represented by the new `SerializationException` (category `SERIALIZATION`). The current `DeserializationException` is replaced and deleted.

### 2.5 Integer and time types (T-9, T-13, 1.5)

| Value | Java type | Check | Rule |
|---|---|---|---|
| `seq_nr` | `long` | 0 or more and 2^53−1 or less. Outside the range is a contract violation. A `long` can represent negative numbers, so a check is needed | T-9 |
| Event `seq_nr` = 0 | `long` | Valid under T-9. A contract violation for events (rule W-6) | W-6 |
| `occurred_at` | `java.time.Instant` | The range in which the nanoseconds since the epoch fit in a signed 64 bits | T-3, T-13 |
| Stored time | `long` (epoch nanoseconds) | Converted from `Instant` with integer arithmetic. Does not go through floating point | T-13 |

`Instant` has nanosecond precision. The Java conformance run executes the cases whose `time_precision` in the conformance data is `nanoseconds`. It does not execute the `milliseconds` cases (they are reported as not applicable, with the reason; chapter 5).

The check lives in exactly one place in the core. `Validation` is package-private, so the conformance runner calls it from a class placed under the same package name as `core` in `src/test/java` (chapter 5).

```java
final class Validation {   // package-private。`core` パッケージの内部で、公開 API に含めない
  private Validation() {}

  enum Context { VALUE, EVENT }   // package-private

  static void checkSeqNr(long seqNr, Context context);
  static void checkOccurredAt(Instant occurredAt);
  static void checkAidString(String typeName, String value);
}
```

For `validateSeqNr` in the conformance data, with `context=value` the check goes through `Validation.checkSeqNr(seqNr, Validation.Context.VALUE)`, and with `context=event` it goes through `EventEnvelope.builder()...build()` (`build()` calls with `Validation.Context.EVENT`). `validateOccurredAt` goes through the construction of the envelope and `persistEvent`. The runner must not merely compute the same formula itself (chapter 5).

### 2.6 The four operations and the asynchronous form

The operation names follow the current Java conventions (`getLatestSnapshotById`, `getEventsByIdSinceSequenceNumber`) while moving closer to the names of the shared contract. A write takes no expected-value argument. The expected value for the check is determined from `event.seqNr()` (W-3, W-4, W-8).

The synchronous and asynchronous forms are separate interfaces (owner decision on 2026-10-06, 9.1).

```java
public interface EventStore<P, A> {
  void persistEvent(EventEnvelope<P> event);                                       // 3.1, W-3〜W-8, H-1〜H-4
  void persistEventAndSnapshot(EventEnvelope<P> event, SnapshotEnvelope<A> snapshot); // 3.2, W-9
  Optional<SnapshotReadResult<A>> getLatestSnapshotById(AggregateId aggregateId);  // 3.4, R-1〜R-3, R-8
  List<EventEnvelope<P>> getEventsByIdSinceSeqNr(AggregateId aggregateId, long seqNr); // 3.5, R-4〜R-6
}

public interface AsyncEventStore<P, A> {
  CompletableFuture<Void> persistEvent(EventEnvelope<P> event);
  CompletableFuture<Void> persistEventAndSnapshot(EventEnvelope<P> event, SnapshotEnvelope<A> snapshot);
  CompletableFuture<Optional<SnapshotReadResult<A>>> getLatestSnapshotById(AggregateId aggregateId);
  CompletableFuture<List<EventEnvelope<P>>> getEventsByIdSinceSeqNr(AggregateId aggregateId, long seqNr);
}
```

| Operation | Behavior | Rule |
|---|---|---|
| `persistEvent` | Appends one event. `seq_nr=1` is a new creation (it can be created without a snapshot). `seq_nr>1` is an update, and commits only when the head's `seq_nr` is `event.seqNr()−1` | W-3, W-4, W-8, H-1 to H-4 |
| `persistEventAndSnapshot` | Writes the event and the snapshot in the same commit. `snapshot.seqNr() != event.seqNr()` is a contract violation | W-9, H-1 |
| Append failures | `seq_nr=0` is a contract violation. A second event with the same `seq_nr` is an optimistic lock failure. A `seq_nr` at or below the head is an optimistic lock failure. A `seq_nr` of head+2 or more is a gap contract violation. A `seq_nr` of 2 or more for an aggregate with no head is regarded as head 0 and is a gap contract violation | W-6, W-7, W-8 |
| `getLatestSnapshotById` | Empty if there is no head. Otherwise returns a `SnapshotReadResult`. It is not an error | R-1 to R-3 |
| `getEventsByIdSinceSeqNr` | Returns all event envelopes with `seqNr` or more in ascending order. If there is a page limit, it reads to the end | R-4 to R-6 |
| Atomicity declaration | Memory reads the latest snapshot and the head atomically (MEM-8). DynamoDB does not read atomically (DY-9, R-8) | R-8 |

- The paging of the current `getEventsByIdSinceSequenceNumber` is already implemented in both the synchronous and asynchronous implementations. The new implementation also keeps reading to the end (R-5, DY-11).
- Asynchronous errors: a failure is passed to `completeExceptionally` as the categorized exception itself. To extract the categorized exception from the `CompletionException` or `ExecutionException` that `join()` wraps, `EventStoreExceptions.unwrap(Throwable)` is provided (E-1). The runner uses it as well.
- Asynchronous memory implementation: a non-blocking guarantee is not needed, and it returns an already-completed `CompletableFuture` on the calling thread. The mutual exclusion (chapter 3) is shared with the synchronous implementation.

### 2.7 Error categories (E-1, E-2, E-3)

The five categories are distinguished both by type and by a distinguishable value (E-1). All five exceptions are made unchecked (owner decision on 2026-10-06, 9.4).

```java
public enum ErrorCategory {
  OPTIMISTIC_LOCK, CONTRACT_VIOLATION, SERIALIZATION, CONFIGURATION, STORAGE
}

public abstract class EventStoreException extends RuntimeException {
  public abstract ErrorCategory category();   // E-1
}

public final class OptimisticLockException extends EventStoreException { ... }
public final class ContractViolationException extends EventStoreException {
  public String rule();                 // 例: "W-9"。規則番号（E-3）
  public OptionalLong seqNr();          // 関係する seq_nr。ない場合（seqNr の欠落・T-11 など）は空（E-3）
}
public final class SerializationException extends EventStoreException { ... }   // 直列化・復元の両方
public final class ConfigurationException extends EventStoreException { ... }
public final class StorageException extends EventStoreException { ... }
```

| Category | When it occurs | Rule |
|---|---|---|
| Optimistic lock | Creating an already existing aggregate, a duplicate `seq_nr`, appending at or below the head | Chapter 4, W-3, W-7, W-8, D-6 |
| Contract violation | T-2, T-10 (missing required elements), W-6, W-9, T-9, T-11, T-12, T-13, a gap under W-8, a size excess under D-7 | Chapter 4 |
| Serialization | Failure to serialize or restore a payload | Chapter 4 |
| Configuration | An invalid configuration value at creation (such as a retention count of 0), a mismatch with the configuration recorded in the storage | Chapter 4, S-1, MEM-3, P-40 |
| Storage | A communication or storage failure, missing data that was read, reaching the retry limit for reading the configuration | Chapter 4, DY-8 |

Rules for messages:

- Optimistic lock (E-2): the only things that may be included are the aid string, the `seq_nr` that was about to be appended, and (if known) the head's `seq_nr`. Connection strings, credentials, and the SDK's raw error text are not included. The specification does not say whether to attach the SDK's exception as the exception's `getCause()`. Even if it is attached, it is not included in `getMessage()`. This is written in chapter 10.
- Contract violation (E-3): the message includes the violated rule number and the related `seq_nr`. For W-9, it includes both the event's `seq_nr` and the differing snapshot `seq_nr`. The head's number is not required.
- Size excess under D-7: the only requirement is that the category is contract violation. The rule number and message conditions are not needed. An implementation may include the rule number `D-7`.
- Callers distinguish by the value of `category()` or by the exception class. They do not infer the category from the message string (CR).

### 2.8 Configuration (S-1, MEM-3, DY-8)

The configuration is divided into two.

- **Storage configuration** (the retention policy). The storage holds it immutably. Memory passes it through `MemoryStorage.create(RetentionPolicy)`, and DynamoDB passes it through `DynamoDbTableConfig` (MEM-3, MEM-D11).
- **Per-store configuration** (serializers and the notification target). Passed through `EventStoreConfig`.

```java
public enum RetentionMode { DELETE, TTL }

public final class RetentionPolicy {
  public static RetentionPolicy none();                              // 履歴を持たない（保持件数「なし」）
  public static RetentionPolicy delete(int keepCount);               // keepCount < 1 は ConfigurationException（S-1）
  public static RetentionPolicy ttl(int keepCount, long graceSeconds);  // DynamoDB だけ（MEM-12）。graceSeconds は秒。負は ConfigurationException
}

public interface RetentionFailureListener {
  void onRetentionFailure(RetentionFailure failure);   // S-4・MEM-11
}

public final class RetentionFailure {
  public AggregateId aggregateId();
  public RetentionMode mode();
  public Throwable cause();
}

public final class EventStoreConfig<P, A> {
  public static <P, A> Builder<P, A> builder();

  public static final class Builder<P, A> {
    public Builder<P, A> payloadSerializer(PayloadSerializer<P> v);                 // T-6〜T-8。必須
    public Builder<P, A> snapshotSerializer(PayloadSerializer<A> v);                // T-6〜T-8。必須
    public Builder<P, A> retentionFailureListener(RetentionFailureListener v);      // S-4・MEM-11。省略可
    public EventStoreConfig<P, A> build();   // 必須の欠落は ConfigurationException
  }
}

public final class EventStoreExceptions {
  public static Throwable unwrap(Throwable t);   // CompletionException・ExecutionException を外して分類済みの例外を返す（E-1）
}
```

The grace period of `RetentionPolicy.ttl` is expressed as an integer number of seconds (`long`). Values finer than a second cannot be represented in the type. 0 is valid (the schema of the conformance data also has a minimum of 0; the expiry equals the marking time). A negative value is a `ConfigurationException` ("an invalid configuration value at creation" in chapter 4 of the shared contract). No upper limit is set (coordinator's answer, 2026-10-06). Because there is no upper limit, computing the expiry (epoch seconds of the marking time + grace seconds, 4.6) with `long` addition can overflow and become a negative value (already expired). The expiry is computed with `BigInteger` and written as a decimal string into an N attribute (DynamoDB's N can represent up to 38 digits). In the implementation PR, write a unit test for the boundary where the grace seconds are `Long.MAX_VALUE`.

Memory:

```java
public final class MemoryStorage {
  public static MemoryStorage create();                              // RetentionPolicy.none() の新しい独立した保存先（MEM-2）
  public static MemoryStorage create(RetentionPolicy retentionPolicy);   // MEM-3 の検査をここで行う
}
public final class MemoryEventStore {
  public static <P, A> EventStore<P, A> create(MemoryStorage storage, EventStoreConfig<P, A> config);
  public static <P, A> AsyncEventStore<P, A> createAsync(MemoryStorage storage, EventStoreConfig<P, A> config);
}
```

- Only the instances given the same `MemoryStorage` object share records, configuration, and mutual exclusion. Having the same name does not make them share (MEM-2).
- `MemoryStorage.create` checks the storage configuration at creation. A retention count of 0 and the TTL mode are a `ConfigurationException` (MEM-3, MEM-12). No configuration item that requests a change feed is provided in the public API (MEM-13). If the conformance data has such a scene, the runner treats it as a capability that is not provided. This is written in chapter 10.
- The storage holds the configuration immutably, and shared instances use that configuration (MEM-3, MEM-D11). `MemoryEventStore.create` does not receive a retention configuration. For this reason, no different configuration can be brought into a shared storage, and no mismatch occurs.
- When a retention failure occurs in a shared storage, it is reported to the `RetentionFailureListener` of the store that performed that write.
- Because memory's `createAsync` involves no communication, it returns an `AsyncEventStore` as it is. As in the synchronous case, a creation failure (`ConfigurationException`) is thrown by `MemoryStorage.create`.

DynamoDB:

```java
public final class DynamoDbTableConfig {
  public static Builder builder();

  public static final class Builder {
    public Builder journalTableName(String v);        // DY-8。必須
    public Builder snapshotTableName(String v);       // 必須
    public Builder headTableName(String v);           // 必須
    public Builder snapshotAidIndexName(String v);    // snapshot の GSI 名（D-3）。必須
    public Builder retentionPolicy(RetentionPolicy v);   // S-1。保存先の設定。省略時は none()
    public Builder configurationReadRetryLimit(int v);   // DY-8 の再要求の上限。初回は数えない。既定 10。0 は再要求なし。負は ConfigurationException
    public Builder clock(Clock v);                    // TTL の印付け時刻。省略時は Clock.systemUTC()（S-3）
    public DynamoDbTableConfig build();               // 必須の欠落・不正な値は ConfigurationException
  }
}
public final class DynamoDbEventStore {
  public static <P, A> EventStore<P, A> create(
      DynamoDbClient client, DynamoDbTableConfig tables, EventStoreConfig<P, A> config);   // 生成の失敗は例外（ConfigurationException・StorageException）
  public static <P, A> CompletableFuture<AsyncEventStore<P, A>> createAsync(
      DynamoDbAsyncClient client, DynamoDbTableConfig tables, EventStoreConfig<P, A> config);   // 生成の失敗は future の例外での完了
}
```

- Creating tables and indexes is outside the library (`dynamodb.md` chapter 2). The table names and index names are given in the configuration (DY-8).
- `createAsync` needs communication to read the configuration items (DY-8) at creation. To avoid hiding the communication, the return value is `CompletableFuture<AsyncEventStore<P, A>>`. A creation failure is returned as a future completed with a categorized exception. The caller extracts the exception with `EventStoreExceptions.unwrap`.
- The retry limit for reading the configuration is `DynamoDbTableConfig.Builder.configurationReadRetryLimit`. The default is 10 times. The first request is not counted. The exponential backoff starts with an initial wait of 50 milliseconds, doubles each time, and is capped at 1 second. The default value and the wait times are design decisions; the specification only says that "there is a configured limit". `store.retry_limit` in the conformance data maps to this value.
- The current `shardCount`, `KeyResolver`, `withDeleteTtl`, and `journalAidIndexName` are abolished (DY-16).
- The `Clock` (the TTL marking time) and the wait time for retrying the configuration read can be injected from tests (chapter 5). `Clock` is a public configuration item passed through `DynamoDbTableConfig.Builder.clock`. The injection of the wait time (`Sleeper`) is a package-internal type and is not part of the public API.

Values that cause a configuration error:

| Value | Result | Rule |
|---|---|---|
| `RetentionPolicy.delete(0)`, a negative number | `ConfigurationException` | S-1 |
| A negative grace period in `RetentionPolicy.ttl` | `ConfigurationException` | "An invalid configuration value at creation" in chapter 4 (coordinator's answer) |
| Passing `RetentionPolicy.ttl` to `MemoryStorage.create` | `ConfigurationException` | MEM-3, MEM-12 |
| A negative `configurationReadRetryLimit` | `ConfigurationException` | Design decision |
| A mismatch with the configuration recorded in the storage (`store_id`, `layout_version`) | `ConfigurationException` | P-40, DY-8 |
| Only some of the configuration items of the three tables exist | `ConfigurationException` | P-40 |
| Missing required configuration (serializers, table names) | `ConfigurationException` | "An invalid configuration value at creation" in chapter 4 (coordinator's answer) |

### 2.9 How retention failures are reported (S-4, MEM-11)

- A failure of the retention processing does not change the result of the write. The write returns as a success (S-4, MEM-11).
- There are two notification paths: the SLF4J log (WARN; slf4j-api is a current dependency) and the `RetentionFailureListener` (optional). The callback receives the final failure exactly once.
- `RetentionFailure` has the aid string, the cause exception, and the retention mode. `getMessage()` must not include credentials or the SDK's raw error text (following E-2; the specification does not define the content of the retention failure notification, see chapter 10).
- Memory reports after releasing the mutual exclusion (MEM-11). Even if an exception occurs inside the callback, the result of the write does not change.
- The thread on which notifications and callbacks run: for the synchronous implementation, the calling thread. For the asynchronous memory implementation, also the calling thread. For the asynchronous DynamoDB implementation, the thread that processes the SDK's asynchronous responses (the completion executor), called before the future completes. The notification target must be implemented without blocking.
- `observe.notifications` (category `retention-failure`) in the conformance data is received and checked by the runner through a `RetentionFailureListener`. The log strings are not parsed.

### 2.10 Correspondence table with the current public API

| Current | New API | Treatment |
|---|---|---|
| `EventStore<AID, A, E>` (synchronous) | `EventStore<P, A>` | Replaced. The old version is deleted in the PR of chapter 7 |
| `EventStoreAsync<AID, A, E>` | `AsyncEventStore<P, A>` | Replaced |
| `EventStore.ofDynamoDB(...)`, `EventStoreAsync.ofDynamoDB(...)` | `DynamoDbEventStore.create`, `createAsync` (returns `CompletableFuture<AsyncEventStore>`), `MemoryEventStore.create`, `createAsync` | Replaced. The arguments change to the three tables and the GSI name. The memory implementation is new |
| `EventStoreOptions` (`withKeepSnapshotCount`, `withDeleteTtl`, `withKeyResolver`, `withEventSerializer`, `withSnapshotSerializer`) | `EventStoreConfig` (serializers and notification target) and `RetentionPolicy` (the retention policy; held by `MemoryStorage` and `DynamoDbTableConfig`) | Replaced. Mutation is dropped and a builder is used |
| `Aggregate<This, AID>` (`getVersion`, `withVersion`, `getSequenceNumber`) | None. The library's types are not required of domain types | Deleted (T-6). The check uses the head's `seq_nr` (H-2) |
| `Event<AID>` (`getId`, `getAggregateId`, `getSequenceNumber`, `getOccurredAt`, `isCreated`) | `EventEnvelope<P>` | Replaced. `isCreated` is determined from `seqNr == 1` (W-3) |
| `AggregateId` (interface) | `AggregateId` (final class) | Replaced (T-1) |
| `AggregateAndEvent` | None | Deleted. A write passes the event envelope and the snapshot envelope separately |
| `EventSerializer`, `SnapshotSerializer`, `DefaultEventSerializer`, `DefaultSnapshotSerializer` | `PayloadSerializer<T>`, `JsonPayloadSerializer` | Replaced (T-6 to T-8) |
| `KeyResolver`, `DefaultKeyResolver` | None | Deleted (DY-16. The aid string is the PK as it is) |
| `persistEvent(E event, long version)` | `persistEvent(EventEnvelope<P>)` | The `version` argument is deleted (H-2) |
| `persistEventAndSnapshot(E event, A aggregate)` | `persistEventAndSnapshot(EventEnvelope<P>, SnapshotEnvelope<A>)` | Replaced (W-9) |
| `Optional<A> getLatestSnapshotById(Class<A>, AID)` | `Optional<SnapshotReadResult<A>> getLatestSnapshotById(AggregateId)` | The return value becomes a pair of the envelope and the head number (R-2). `Class<A>` is deleted |
| `List<E> getEventsByIdSinceSequenceNumber(Class<E>, AID, long)` | `List<EventEnvelope<P>> getEventsByIdSinceSeqNr(AggregateId, long)` | Renamed. Returns envelopes (R-6) |
| `EventStoreBaseException`, `EventStoreBaseRuntimeException`, and the checked/unchecked pairs (`EventStoreReadException`, `EventStoreWriteException`, `SerializationException`, `DeserializationException`, `OptimisticLockException` and each `...RuntimeException`) | `EventStoreException` and five child classes | Replaced (E-1). Contract violation and configuration are added. Consolidated into unchecked exceptions (9.4) |
| `internal.EventStoreSupport`, `EventStoreForDynamoDB`, `EventStoreAsyncForDynamoDB` | Internal implementation in the `dynamodb` package | Rebuilt. The old versions are deleted in the PR of chapter 7 |

### 2.11 Types and operations used when kotlin and scala wrap this

In stage 3, kotlin and scala reference this new Java API through the Snapshot and rebuild their wrapper layers. This table is a "proposal of what they use". Whether kotlin and scala expose the envelope types as they are is a decision of stage 3 (chapter 10 of the implementation plan). The design on the kotlin and scala side is not done in this document.

| Category | Types and operations used |
|---|---|
| Values, envelopes | `AggregateId`, `EventEnvelope<P>`, `SnapshotEnvelope<A>`, `SnapshotReadResult<A>` |
| Serializer | `PayloadSerializer<T>`, `JsonPayloadSerializer`. The premise is to pass an `ObjectMapper` with Jackson's Kotlin and Scala modules registered to `JsonPayloadSerializer.of(ObjectMapper, TypeReference<T>)` |
| Factory functions | `DynamoDbEventStore.create`, `createAsync`, `MemoryEventStore.create`, `createAsync`, `MemoryStorage.create` |
| Operations | `AsyncEventStore` (`CompletableFuture`, easy to bridge to kotlin coroutines and scala `Future`). The synchronous `EventStore` can also be used |
| Configuration | `EventStoreConfig`, `RetentionPolicy`, `RetentionMode`, `DynamoDbTableConfig`, `MemoryStorage` |
| Errors | `EventStoreException`, `ErrorCategory`, `EventStoreExceptions.unwrap`. `rule()` and `seqNr()` of `ContractViolationException` |
| Notification | `RetentionFailureListener`, `RetentionFailure` |

- The thread on which notifications and callbacks run is as in 2.9. The notification target does not block.
- Dependencies that appear in the types of the public API are made `api` in the build (9.6). Without `api`, kotlin and scala cannot reference types such as `DynamoDbClient` and `ObjectMapper`.

## 3. Memory implementation policy

The implementation lives in the `memory` package. MEM-1 to MEM-13 are satisfied as follows.

| Rule | Policy |
|---|---|
| MEM-1 | Held only in the heap of a single-process JVM. Not persisted. No clear-all API is provided (`MemoryStorage` has no `clear`) |
| MEM-2 | `MemoryStorage.create()` and `create(RetentionPolicy)` create an empty, independent storage. A store receives a reference to a `MemoryStorage`. Only the stores given the same object share records, configuration, and mutual exclusion. Nothing is shared by name or through a static dictionary |
| MEM-3 | `MemoryStorage.create` checks the storage configuration. A retention count of 0 and the TTL mode become a `ConfigurationException`. No configuration item that requests a change feed is provided. The configuration (the retention policy) is passed through `MemoryStorage.create(RetentionPolicy)` and `MemoryStorage` holds it immutably. Shared instances are not given a different configuration (MEM-D11, 2.8) |
| MEM-4 | `MemoryStorage` holds a single `ReentrantLock`. Writes and reads to the same storage use this lock. It protects against calls from multiple threads. The intermediate state before a commit cannot be read from outside the lock |
| MEM-5 | The aid uses `AggregateId.asString()` as the key of a `HashMap<String, ...>` and is compared by exact match. It is not identified by a hash value alone. It is not selected by prefix match. The aid and `seq_nr` are separate keys. T-9, T-11, T-12, and T-13 go through the core's checks |
| MEM-6 | For each aggregate, the metadata (`seq_nr`, `occurredAt`, `manifest`) and the serialized `byte[]` (the payload) are held separately. They are serialized with the `PayloadSerializer` at write time and held, and restored at retrieval. Even if the user changes the object after the write, or changes the retrieved result, the stored value does not change. No requirement beyond T-6 is placed on the payload |
| MEM-7 | Before taking the lock, the input checks (envelope, W-6, W-9, T-9, and others) and the payload serialization, which can fail, are completed. Inside the lock, only the following are done: reading the head, checking (W-3, W-7, W-8), preparing the changes, publishing them together (commit), and the retention processing. A failure during preparation does not change the records. An event-only `seq_nr=1` can also create a new aggregate (W-3) |
| MEM-8 | `getLatestSnapshotById` reads the head's `seq_nr` and the latest snapshot's envelope inside the same lock. It is recommended to do the restoration (`deserialize`) after releasing the lock. A restoration failure is returned in the serialization category |
| MEM-9 | `getEventsByIdSinceSeqNr` makes a copy of all the event `byte[]` and metadata inside the same lock. The restoration is after releasing the lock |
| MEM-10 | If there is no retention count, only the current snapshot is held. With a retention count n, the newest n are kept and the older ones are removed in order of age. It is done after the commit, inside the same lock. Leftovers are cleaned up even on an event-only append. The journal and the head are not removed |
| MEM-11 | A retention failure does not change the success of the write. After releasing the lock, it is reported through the log and the `RetentionFailureListener` of the store that performed the write. It is retried in the retention processing of the next append |
| MEM-12 | A request for the TTL mode is a `ConfigurationException` |
| MEM-13 | No change feed is provided |

**Scope of mutual exclusion.** One lock per `MemoryStorage` protects all of the head, journal, snapshots, and history. Different storages have different locks. The lock is not split finely per aggregate (in the first version, correctness takes priority; performance improvement is outside the specification).

**How to commit.** Inside the lock, the new head, the journal row, the snapshot, and the history changes are first built in working variables. After all of the preparation succeeds, they are published to the storage in one step. If any part of the preparation fails, nothing is published (H-1, MEM-7).

**Isolation of inputs and retrieved results.** Only immutable values (`long`, `Instant`, `String`) and the serialized `byte[]` are stored. The `byte[]` is copied at storing and at retrieval. References to domain objects are not held.

**Asynchronous.** The memory implementation of `AsyncEventStore` calls the same internal implementation and returns an already-completed `CompletableFuture` (2.6).

**When retention runs.** As in MEM-7 and MEM-10, the retention processing is done after the commit, inside the same lock, and the failure is reported after releasing the lock.

**Retention history (MEM-10).** The history is held in descending order of the snapshot's `seq_nr`. As with DynamoDB's sparse GSI, the newest n are kept (S-2). The "marked" state of the history is a concept of DynamoDB's TTL mode and does not exist in memory (MEM-12). The inspection hook for memory's internal history always returns `marked` as an empty set and compares with the expected value by exact match (the cases of the delete mode in the conformance data specify `marked: []`). The cases with `requires=["ttl"]` target only DynamoDB in the actual data, and there is no applicable case for memory.

**Hooks for tests.** The hooks for retention failures, inspection of the internal history, and failures just before the commit are placed as package-internal types of the `memory` package (not public). The runner is placed under the same package name in `src/test/java` and accesses those hooks (chapter 5).

## 4. DynamoDB implementation policy

### 4.1 SDK and versions

- Keep `software.amazon.awssdk:dynamodb:2.55.11` of the AWS SDK for Java v2 (the current version in `build.gradle.kts`). Use both the synchronous `DynamoDbClient` and the asynchronous `DynamoDbAsyncClient`.
- If the version is changed, the reason is given in the implementation PR, not in chapter 9.
- The current `build.gradle.kts` has jackson, vavr, and slf4j-api. Whether the new implementation uses vavr is decided in the implementation PR. If it is not used, it is removed from the dependencies.
- Dependencies that appear in the types of the public API (`DynamoDbClient` and `DynamoDbAsyncClient` of `software.amazon.awssdk:dynamodb`, and `ObjectMapper` of `jackson-databind`; `TypeReference` is in `jackson-core`) are made `api`. Dependencies that do not appear in the public API types (`slf4j-api`, `jackson-datatype-jsr310`, `vavr`) stay `implementation`. `jsr305` keeps its current treatment (9.6).
- The subscriber-side feature that reads Streams is not included in the first major version (9.3). The write only has to satisfy the Streams setting of the head table (DY-3, DY-12).

### 4.2 Three tables and configuration items (DY-2 to DY-8, DY-16 to DY-19)

| Table | Primary key | GSI | Streams | Notes |
|---|---|---|---|---|
| journal | PK `aid`(S), SK `seq_nr`(N) | None | Disabled | |
| snapshot | PK `aid`(S), SK `skey`(N) | `(aid, active_history_seq_nr(N))`, KEYS_ONLY (D-3) | Disabled | TTL is on the attribute `ttl`. Enabled only in TTL mode (DY-2) |
| head | PK `aid`(S), no SK | None | Enabled, NEW_IMAGE (DY-3, DY-12, D-4) | |

- The PK is the aid string itself. Neither logical shards nor hashes are used (DY-16). The SK of the journal is `seq_nr`, and the `skey` of the snapshot is 0 for the current one and its `seq_nr` for a history entry (DY-17).
- The three tables are in the same region. Reads are strongly consistent. Only the GSI read of the retention processing is eventually consistent (DY-18).
- The only operations used are `GetItem`, `BatchGetItem`, `Query`, `TransactWriteItems`, `BatchWriteItem`, and `UpdateItem`, narrowed down by aid. `Scan` is not used in ordinary operations (DY-19).
- Creating the tables is outside the library (users and the test environment). The library has no operation for creation.

**Configuration items (DY-8).** One item in each of the three tables. The aid is `__config__`. The journal has `seq_nr=0`, the snapshot has `skey=0`, and the head has no SK. The only attributes are `store_id`(S) and `layout_version`(N, initial version 1). The configuration item of the snapshot does not have `active_history_seq_nr`.

Procedure at creation:

1. Read the three configuration items with one `BatchGetItem` (`ConsistentRead=true`). Accumulate the `Responses`.
2. If there are `UnprocessedKeys`, re-request only those keys, with exponential backoff and still strongly consistent. Do not conclude "does not exist" until there are no unprocessed keys left. If the re-request limit (`configurationReadRetryLimit`; default 10 times; the first request is not counted) is reached, it is a storage error (`StorageException`), not a configuration error.
3. If none of the three exists, send one `TransactWriteItems` with a new `store_id` (a random value). Attach `attribute_not_exists(aid)` to each Put.
   - If `CancellationReasons` contains `ConditionalCheckFailed`, discard the response, re-read the three items with strong consistency, and go to step 4 (P-19).
   - Read `TransactionConflict` as a concurrent creation, and perform the same re-read exactly once (DY-8, P-44).
   - Throttling, communication failures, and anything else become a `StorageException` without a re-read.
   - The re-read uses the same `BatchGetItem` as steps 1 and 2. It is strongly consistent, reads `UnprocessedKeys` to the end, and has the same re-request limit.
   - If the re-read also finds none of the three configuration items, do not repeat further and give up with a `StorageException` (DY-8, P-44). The conformance data has no such scene, so write unit tests in the implementation PR (the case where the configuration items are found by the re-read after a `TransactionConflict`, and the case where none of the three exist and it ends in a `StorageException`).
4. If all three exist, the `store_id` matches, and the `layout_version` is the same as its own version, continue.
5. Otherwise (only some exist, a `store_id` mismatch, or a different `layout_version`), it is a `ConfigurationException` (P-40).

The required IAM permissions are `dynamodb:BatchGetItem` and `dynamodb:PutItem` on the three tables. The configuration items are created only with conditional Puts.

### 4.3 Item attributes (chapter 5)

| Item | Attributes |
|---|---|
| Journal | `aid`(S), `seq_nr`(N), `occurred_at`(N, epoch nanoseconds), `manifest`(S), `payload`(B) |
| Snapshot (current) | `aid`, `skey`(N, 0), `seq_nr`(N), `manifest`(S), `payload`(B), `last_updated_at`(N, `occurred_at` in milliseconds). It has neither `active_history_seq_nr` nor `ttl` |
| Snapshot (history) | The same attributes as the current one. `active_history_seq_nr`(N) only while it is not marked. When the TTL mark is applied, it has `ttl`(N, epoch seconds) and `active_history_seq_nr` is removed. An item with no expiry does not have the `ttl` attribute itself |
| Head | `aid`(S), `type_name`(S), `seq_nr`(N), `events`(L). The element of `events` is an M (`seq_nr`, `occurred_at`, `manifest`, `payload`), and the number of elements is 1 |

`occurred_at` is converted from `Instant` to epoch nanoseconds with integer arithmetic (2.5). An N attribute is given a decimal string through `AttributeValue.n(String)`. It does not go through floating point. `last_updated_at` is obtained with `Instant.toEpochMilli()` (rounding toward negative infinity). The specification does not define the direction of rounding (chapter 10, Q11).

### 4.4 Writes (D-5, D-6, D-7, W-8)

One write is made into one `TransactWriteItems`.

| Action | Condition |
|---|---|
| Put journal | `attribute_not_exists(aid)` |
| New creation (`seq_nr=1`): Put head | `attribute_not_exists(aid)` |
| Update (`seq_nr>1`): Update head | `seq_nr = :prev` (`:prev` is `event.seqNr()−1`). Overwrites `seq_nr` and `events` |
| If there is a snapshot, Put current snapshot | No condition |
| If a retention count is set, Put history snapshot | No condition |

At most 4 actions. The order is journal, head, current-snapshot, history-snapshot.

**Classification of failures.**

| Failure | Category | Rule |
|---|---|---|
| The head's condition fails on a new creation | Optimistic lock | W-3 |
| The head's condition fails on an update | Compare with the old head's `seq_nr`. `event.seqNr() <= old head` is an optimistic lock failure. `event.seqNr() >= old head + 2` is a gap contract violation (W-8) | W-8, D-5 |
| The journal's condition fails | Optimistic lock | W-7 |
| `TransactionConflict` | Optimistic lock | D-6 |
| Throttling, communication failure, other | Storage | Chapter 4 |

When cancellation reasons are attached to multiple items, if any item has a `TransactionConflict`, it is an optimistic lock failure (D-6). If not, look at the head's condition failure, the journal's condition failure, and others in that order, and classify by the first one that matches (`dynamodb.md` 6.2, P-43). The conformance data has no scene in which reasons are attached to multiple items, so write unit tests in the implementation PR (for example, when the head's condition failure indicates a gap and another item returns a `TransactionConflict`, the result is an optimistic lock failure).

**D-5.** Attach `ReturnValuesOnConditionCheckFailure=ALL_OLD` to the head's Put and Update. Compare with the old `seq_nr` of the `item()` in `TransactionCanceledException.cancellationReasons()`. No additional read is made. If the old item is not returned, it is regarded as head 0.

**D-7 (item size).** Before writing, estimate the size of each item, and if it exceeds the limit of 409600 bytes, it is a contract violation (category only). Because the payload is carried in both the journal and the head, both are estimated. The estimate includes the attribute names, values, and containers. The estimate follows [AWS's size definition](https://docs.aws.amazon.com/amazondynamodb/latest/developerguide/CapacityUnitCalculations.html). In the implementation PR, write boundary tests (exactly 409600 bytes and +1).

**Checks before sending and classification after sending.** Contract violations that can be judged from the input alone (T-9, T-11 to T-13, W-6, W-9, D-7) are checked before sending. A write whose check fails sends nothing to the storage. The gap of W-8 depends on the head's value, so it is not judged before sending. The transaction is sent and the failure is classified by the old head on failure (D-5). No additional read is made. What H-1 requires is that an append that did not commit appears nowhere.

### 4.5 Reads (DY-9, DY-10, DY-11)

- `getLatestSnapshotById`: reads the head and the current snapshot (`skey=0`) with one `BatchGetItem` (strongly consistent). `UnprocessedKeys` are re-requested until they are read to the end (DY-9). The re-request is made only for the remaining keys, still strongly consistent, with exponential backoff, and the limit and the wait times are the same as for the configuration read (4.2) (`configurationReadRetryLimit`; default 10 times; the first request is not counted). When the limit is reached, it does not conclude that the unprocessed keys "do not exist" and throws a `StorageException` (a design decision. DY-9 does not set a limit, but without a limit the call might never end). If there is no head, it is empty (R-1). If there is one, it returns the pair of the snapshot (none if absent) and the head's `seq_nr` (DY-10, R-2, R-3).
- Reading the two items is not atomic (R-8). `TransactGetItems` is not used (P-25). This property is written in the Javadoc.
- `getEventsByIdSinceSeqNr`: runs a `Query` on the journal with `aid = :aid AND seq_nr >= :seq_nr` and `ConsistentRead=true`. Ascending order. While a `LastEvaluatedKey` is returned, it reads to the end (DY-11, R-4, R-5). The proposal is to implement the synchronous form with `queryPaginator` and the asynchronous form with recursion of continuations (the current code has the same structure).
- If the data that was read is not in the expected shape, such as a missing payload or manifest, it is a `StorageException` (the storage category of chapter 4).

### 4.6 Snapshot retention (chapter 8, D-3, D-9, P-18, P-24, S-3)

Done only when a retention count n is set. Done only after the commit of a write that wrote a history entry (D-9).

1. `Query` the sparse GSI with `aid = :aid` and `ScanIndexForward=false`, and read to the end (KEYS_ONLY).
2. Add the history entry just written (do not duplicate it if it is already visible in the GSI, because of eventual consistency). Keep the first n in descending order, and the older ones are the targets (S-2).
3. Delete mode (`DELETE`): `BatchWriteItem` in batches of 25 (P-18). For `UnprocessedItems`, resend only the remaining items with exponential backoff. The limit and the wait times of the resending are the same as for the configuration read (4.2): 10 times without counting the first request, an initial wait of 50 milliseconds, doubling, and a cap of 1 second. When the limit is reached, the remainder is not deleted, a retention failure is reported in step 6, and the success of the write is returned. The remaining history entries become targets again in the next retention processing (a design decision. The specification only says to resend and does not set a limit. Because retention is completed within the call, without a limit the call might never end).
4. TTL mode (`TTL`): `UpdateItem` one at a time. `SET #ttl = :expires REMOVE active_history_seq_nr`, with the condition `attribute_exists(active_history_seq_nr)`. `#ttl` is passed through `ExpressionAttributeNames`. `:expires` is "the marking time (epoch seconds) + grace seconds". If a later update fails its condition, it is skipped as already marked.
5. Marked history entries are not counted in the count. The expiry is not postponed (S-3). The approach of counting first and then selecting the excess is not used (P-24).
6. A failure does not change the result of the write. It is reported through the log and the `RetentionFailureListener` (S-4).

**When it runs.** DynamoDB completes the retention processing within the call after the commit of the write, and then returns the result of the write (the coordinator's decision). It can be tested deterministically. The latency of the write increases by the retention `Query` and the deletes. Memory, as in MEM-7 and MEM-10, does it inside the mutual exclusion and reports after releasing it (chapter 3).

### 4.7 Where to put the SDK injection mechanisms (IP 6)

The library itself only uses the `DynamoDbClient` and `DynamoDbAsyncClient` that the user passes in. The injection mechanisms are put in **the clients that the tests create**. No injection mechanism is put in the library's public API or its production code.

| Purpose | Where |
|---|---|
| Observing requests (`observe.requests`) and replacing responses (`replace-response`) | An `ExecutionInterceptor` (`beforeExecution`, `modifyRequest`, `modifyResponse`, `afterExecution`) registered with `ClientOverrideConfiguration.addExecutionInterceptor(...)`. The official mechanism: [ExecutionInterceptor](https://docs.aws.amazon.com/java/api/latest/software/amazon/awssdk/core/interceptor/ExecutionInterceptor.html) |
| Returning a failure or response without calling the target (`replace-request`) | A test class that wraps the SDK's HTTP client layer (`SdkHttpClient`, `SdkAsyncHttpClient`). It skips the sending and returns a DynamoDB JSON-format error response or response body. The SDK builds the exception (`code`, `cancellationReasons`) |
| Disabling the SDK's automatic retries | Disable the retry setting at client creation in the test client. The names in the API are to be confirmed in SDK 2.55.11 (**unconfirmed**. To be confirmed in the implementation PR) |

An `ExecutionInterceptor` can observe and modify a request, but it cannot skip the sending. For this reason, the HTTP client layer is used for `replace-request`. This proposal is to be verified in the implementation PR against the pinned version (it is not written as confirmed). Because only the requests of the retention processing are to be failed, the injection is distinguished by the operation name (the GSI name of `Query`, `BatchWriteItem`, `UpdateItem`) and by the specification of the phase.

**Mapping requests to phase names.** The `ExecutionInterceptor` decides the phase name of `observe.requests` from `SdkExecutionAttribute.OPERATION_NAME` and the content of the request.

| Request | Phase name |
|---|---|
| `BatchGetItem` that reads the `__config__` keys | `configuration-read` |
| `TransactWriteItems` that Puts `__config__` | `configuration-create` |
| Any other `TransactWriteItems` | `commit` |
| `Query` on the journal | `read-events` |
| `BatchGetItem` of the head and the current snapshot | `read-snapshot` |
| `Query` that specifies a GSI name | `retention-query` |
| `BatchWriteItem` | `retention-delete` |
| `UpdateItem` that uses `#ttl` | `retention-mark` |
| A read (`GetItem`, `BatchGetItem`, `Query`) sent after `TransactWriteItems` within a write operation | `classify-condition-failure-read` |

`classify-condition-failure-read` is the phase, defined by `conformance/README.md`, of the additional read made solely to classify a condition failure. Because of D-5, the writes of this design make no additional read, so it is checked that the requests of this phase number 0. The requests that the runner itself sends (seeding and attribute inspection) are sent with a separate client that has no interceptor attached, and are not mixed into the record.

**`unprocessed_first_n`.** In `modifyRequest`, remove the first n items of the `BatchWriteItem` from the request and remember them. The rest is actually sent. In `modifyResponse`, build the response by putting the removed n items into `UnprocessedItems`. The removed n items do not commit (`replace-request`). The library resends the `UnprocessedItems`, so the resent request is actually processed.

**Fault registry.** A fault registry is kept per scene.

- The runner tells the registry the number of the current operation (0 at store creation).
- A hook consumes, in array order, the faults whose operation number and phase match, and counts the number of firings.
- When `repeat` is `count`, it applies up to the specified count. When it is `until-operation-finishes`, it fails all the target requests during that operation, including retries, and removes the fault when the operation ends. It is not carried over to the next operation.
- At the end of an operation (for store creation, the end of the creation), if there is a fault that fired 0 times, the scene is made a failure. A fault that cannot be injected is made "unverified".

## 5. Runner for the conformance test data

### 5.1 Location

Created in `src/test/java/com/github/j5ik2o/event/store/adapter/java/conformance/`. It reads the JSON data in `conformance/` from the repository, read-only. Each case becomes one test through JUnit 5 dynamic tests (`@TestFactory`). It runs for each of memory and DynamoDB (`backends`).

### 5.2 How to read the data

| Item | Policy |
|---|---|
| JSON | Jackson's `ObjectMapper`. Enable `DeserializationFeature.USE_BIG_INTEGER_FOR_INTS` and `USE_BIG_DECIMAL_FOR_FLOATS`, and enable `JsonParser.Feature.STRICT_DUPLICATE_DETECTION`. Duplicate keys, NaN, and Infinity are load failures. String values and attribute names are not rewritten |
| Version | Check the top-level `format` and `version` |
| Arbitrary-precision integers | Values such as −1 and 2^53 of `seq_nr` are read as `BigInteger`. They are not decoded directly into `long`. The handling of values that do not fit in `long` is in chapter 10 |
| `epoch_nanoseconds` | Computed from the decimal string with `BigInteger`. Does not go through floating point |
| `occurred_at` of the envelope | Convert the UTC ISO 8601 with 9 fractional-second digits to `Instant` |
| Time precision | For the success cases of `precision_policy = native-time-type`, the expected value is the value obtained by converting the input to `Instant`. `expect.value` is the reference value before rounding. The runner reports the converted value and the actual value. The cases whose `representation.time_precision` is `milliseconds` are not applicable. The cases of `nanoseconds` and the cases without a mark are executed. `representation.signed_seq_nr = true` is executed, because it can be represented with `long` |
| Operations of the value table | `buildAid`, `validateOccurredAt`, `validateSeqNr`, and `fnv1a64` do not require public API functions of the same name; the runner maps them |
| `buildAid` | Prepare a test ID that returns `user_string`, and check that `AggregateId.of` builds it from the type name and the value |
| `validateSeqNr` | `context=value` calls the core's `Validation.checkSeqNr(seqNr, Validation.Context.VALUE)` (the general range of T-9; 0 is also valid). `context=event` goes through `EventEnvelope.builder()...build()` (it calls the same check with `Validation.Context.EVENT` and makes 0 a contract violation under W-6). The class of the conformance runner that makes this call is placed under the same package name as `core` in `src/test/java` and calls `Validation`. The runner does not compute the same formula itself |
| `validateOccurredAt` | An aggregate with the type name `ConformanceTime` and the case ID as its value. Run `persistEvent` for numbers 1 through `event_seq_nr−1` with the time `1970-01-01T00:00:00.123000000Z`, and then write number `event_seq_nr` with the input time (the payload is an empty object and the manifest is the empty string). For success cases, read number 1 back and compare. For out-of-range cases, compare the contract violation at number 7 |
| `fnv1a64` | The 4 cases (K-1) are reported as "not applicable" with a reason. The reason is that the first major version has no storage that uses hashes (DY-16; acceptance condition 1 of chapter 5 of the implementation plan, decision of 2026-10-06). They are counted neither as success nor as failure. They are executed when a storage that uses hashes is delivered in stage 5 |
| Payload comparison | Compare the JSON values serialized and restored with the default JSON serializer. Key order and whitespace are ignored. The order of arrays, null, booleans, strings, and numbers are preserved. Booleans and numbers are not treated as identical. No Unicode normalization |
| generators | `target` (a JSON Pointer rooted at the case), `character`, `byte_length`. `~0` and `~1` are decoded. The schema check is made before expansion, and operations use the values after expansion. The reference implementation is `materialize` in `tools/conformance/data.py` |
| Size | 400KB is 409600 bytes, and 1MB is 1048576 bytes |

### 5.3 Procedure for running a scene

1. Check whether `backends` contains the storage to run. If not, it is not applicable. `requires=["ttl"]` appears only in DynamoDB scenes in the actual data. There is no applicable case for memory (MEM-12).
2. Each scene runs on an independent storage. Memory uses a new `MemoryStorage`. For DynamoDB, the runner assigns and creates the names of the three tables and the GSI for each scene (for example, `{a safe string made from the case ID}-journal`). The three tables are in the same region. Items of other scenes are not reused.
3. Map the `store` settings (`retention_count`, `retention_mode`, `ttl_grace_seconds`) to a `RetentionPolicy` and pass it to `MemoryStorage.create(RetentionPolicy)` and `DynamoDbTableConfig.Builder.retentionPolicy`. The serializers and the notification target are passed to `EventStoreConfig`.
4. If there is `seed.items`, insert them directly with test permissions before creating the store.
5. If there is `initialization`, check the creation result. For the creation-failure cases (those that have `initialization` and those with `faults.operation=0`), the runner runs the creation as "operation number 0" and catches it. For the synchronous form, it catches the exception that `create` throws. For the asynchronous form, it extracts the exceptional completion of the future returned by `createAsync` with `EventStoreExceptions.unwrap` and maps it to the five categories. The creation-failure cases have no sequence of operations. Faults before creation are registered first.
6. Expand `generators`, and build `fixtures.events` and `fixtures.snapshots` as envelopes just before each operation. A rule violation during the construction of an envelope from invalid input is also caught as a failure of that operation. The library's checks are not substituted by the runner's pre-checks.
7. Run `steps` in array order, without running them in parallel.
8. Check the `expect` and `observe` of each operation. For scenes with retention failures or delays, observe after the retention and inspection hooks complete. A hook must not change the success or failure of the write. `expect` is one of success, none, snapshot (the pair of `head_seq_nr` and the envelope), events (including order), and error.
9. `retry_limit` of the configuration check is the upper limit of the number of re-requests (the first request is not counted). It is mapped to `DynamoDbTableConfig.Builder.configurationReadRetryLimit`. If the implementation's limit is a wait time, reproduce reaching the limit with the same sequence of unprocessed responses. The exponential backoff may use a clock hook to shorten the real time.

### 5.4 Where to put the hooks

| Hook | Where |
|---|---|
| Deterministic execution of retention | As in 4.6, the design finishes retention by the completion of the write. The runner observes after the return of the write. Memory, as in MEM-7 and MEM-10, performs retention inside the mutual exclusion and is likewise observed after the return. Even in the asynchronous form, retention finishes before the `CompletableFuture` completes |
| Internal history (`observe.history`) | DynamoDB: the runner directly reads the snapshot table and the GSI with test permissions. Memory: a package-internal inspection type of `memory`. Compare active (history entries without a mark) and marked (pairs of `seq_nr` and expiry) by exact match. Absent is a history entry that must not exist. The current snapshot and the configuration item are not counted. Only the history of that aggregate is looked at |
| Failure notification (`observe.notifications`) | A test implementation of `RetentionFailureListener`. Category `retention-failure`. An empty array means no notification. If there are multiple identical final failures, they may be normalized into one |
| SDK requests (`observe.requests`) | Compare the requests recorded by the `ExecutionInterceptor` of 4.7 after turning them into a structure in which the expressions and the bindings of attribute names and values are parsed (whitespace, the order of clauses, and the order of AND are not compared). Targets: `update.set`, `update.remove`, `condition`, `key_condition.all`, the TTL `#ttl` and `expression_attribute_names`, `expires` (epoch seconds), `initial_batch_sizes` (the number of delete batches excluding resends), `no_requests_in_phases`, `request_count`, `minimum_request_count`. The elements of `requests` are matched, in array order, to the actual separate requests. Paging, re-requesting unprocessed keys, and splitting delete batches are checked over the whole sequence of requests of a phase |
| Attributes (`observe.items`, `seed.items`) | The runner reads them with `GetItem`. `table` is the configured table name of journal / snapshot / head. `attributes` are an exact match of S/N/B/L/M. N is compared by treating the decimal string as an integer. An M inside an L is `nested_attributes`. `binary_json` compares the restored result of B as JSON. `generated-store-id` in `bindings` binds the first actual `store_id` and checks that all three items have the same value. The checks of the set of attributes, their types, and the number of list elements are made over the whole actual item before exclusion |
| Clock | The `java.time.Clock` that `DynamoDbEventStore` receives (2.8). `clock.epoch_seconds` and the operation's `clock_epoch_seconds` are made a fixed clock. v1 uses a clock in the year 2100 |
| Layout (`dynamodb/layout.json`) | Compare the actual three tables with `DescribeTable` and `DescribeTimeToLive`. The table names and GSI names are bound to the configured values. The runner does not create tables from `layout.json`. On the test side, write the `CreateTable` definitions separately from `layout.json` (the same as the user-facing definitions that appear in the README and `DATABASE_SCHEMA`). `layout.json` is used only as the expected value. Creating from `layout.json` and then comparing with `layout.json` would make the check merely verify itself |

### 5.5 Fault injection (12 phases)

The enumeration of `phase` in `conformance/schema/common.schema.json` has 12 kinds (the "13 phases" in the instruction was the coordinator's mistake). The injection point is shown for all 12 kinds.

For `operation` of `faults`, 0 is store creation and 1 or more is a 1-based operation number. If a registered fault does not fire, the scene is made a failure. If it cannot be injected, it is reported as "unverified" (not counted as a success).

| phase | Injection in DynamoDB | Injection in memory |
|---|---|---|
| `serialize-event` | Make it fail with a test implementation that wraps the `PayloadSerializer` of the event payload | The same (it fails in the preparation phase before the lock) |
| `serialize-snapshot` | Wrap the `PayloadSerializer` of the aggregate state | The same |
| `deserialize-event` | Wrap `PayloadSerializer.deserialize` at read time | The same |
| `deserialize-snapshot` | The same (aggregate state) | The same |
| `commit` | `replace-request` at the HTTP layer of `TransactWriteItems` (nothing commits). `TransactionCanceledException` is a `replace-request` | Make it fail with a package-internal hook just before the commit (before publication). No changes are left in the head, journal, or snapshot |
| `read-events` | `replace-request` / `replace-response` of the response to the journal's `Query` | Make the retrieval of records inside the lock fail (a package-internal hook) |
| `read-snapshot` | Replace the response of `BatchGetItem`. `read-interleave` is here (5.6) | Make the retrieval of the head and the envelope inside the lock fail |
| `retention-query` | `replace-request` on the `Query` of the history GSI. `history_pages` of `sdk-response` is returned by the response plan | Map to the logical-history hook (`conformance/README.md`). Among the `retention-query` faults in the common retention scenes, there are 11 with `kind=sdk-response` (`history_pages`) and 1 with `storage-error`. The hook returns the sequence of pages of `history_pages` as retention candidates and does not add the history entry just written, following `omit_just_written_history`. `storage-error` makes the retrieval of candidates fail (the hook of MEM-11) |
| `retention-delete` | `replace-request` on `BatchWriteItem`. `unprocessed_first_n` is also expressed with `replace-request` | Make the deletion of the logical history after the commit fail |
| `retention-mark` | `replace-request` on the TTL `UpdateItem` | Only DynamoDB scenes in the actual data. There is no applicable case for memory |
| `configuration-read` | `BatchGetItem` of the configuration items. Follows the response plan of `responses` and `unprocessed_keys` | Only DynamoDB scenes in the actual data. There is no applicable case for memory |
| `configuration-create` | `TransactWriteItems` of the configuration items. For a creation race with `install_items`, commit the items that another runner wrote first | Only DynamoDB scenes in the actual data. There is no applicable case for memory |

Handling for each `kind`:

- `serialization-error`: make the corresponding phase of the serializer fail.
- `storage-error`: the final failure of the storage or the retention hook. `details.scope=final-retention-failure` makes the whole retention fail after candidate selection and before deletion.
- `sdk-error`: disable the SDK's automatic retries and build the SDK exception from `details.code` and `cancellation_reasons` (return a JSON error response at the HTTP layer).
- `sdk-response`: implement the response plan (`responses`, `unprocessed_keys`, `history_pages`, `omit_just_written_history`, `unprocessed_first_n`) with the `ExecutionInterceptor` and the HTTP layer. `history_pages` returns the sequence of pages of history `seq_nr` of the GSI response as it is, and does not automatically add the history entry just written. For each page, `LastEvaluatedKey` and `ExclusiveStartKey` are matched. `unprocessed_first_n`, as in 4.7, makes the first n items of the `BatchWriteItem` into `UnprocessedItems` and actually processes the rest. The way of counting fault firings follows the registry of 4.7. The physical results of deletes, TTL updates, and writes are actually applied and checked.
- `injection`: `replace-request` does not call the target and commits nothing (all write failures and retention failures of v1 are this). `replace-response` replaces the response after sending (the side effects remain).
- `repeat`: `{"mode":"count","count":1}` or `{"mode":"until-operation-finishes"}`.

`cancellation_reasons`: one element for each item of the transaction. For a write, in the order journal, head, current-snapshot, history-snapshot (only the actions that exist). For configuration creation, `configuration:journal`, `configuration:snapshot`, `configuration:head`. The `code` of an item that did not fail is the string `None`. The runner matches the target names against the actions in the actual request and rearranges them. This order is not enforced as the library's request order. The head's `ConditionalCheckFailed` has `old_head_seq_nr` (`null` means the old item is not returned). The classification of D-5 uses this old item. A condition failure of the journal is W-7, `TransactionConflict` is an optimistic lock failure of D-6, and throttling of anything other than the head is a storage error.

### 5.6 `read-interleave` (the non-atomic response of DY-9)

Capture the old head just before sending. Commit the append of `interleaved_operation`. Send the original `BatchGetItem`, and replace the head in the response with the old head. No real-time race is used. The implementation is the `ExecutionInterceptor` of 4.7 (`modifyResponse`). The `read-interleave` scenes exist only for DynamoDB in the actual data. In memory, the head and the snapshot are read with the same lock (MEM-8). The alternative check for memory is a concurrency test that checks that the pair is always consistent even when a read overlaps with another thread appending to the same aggregate (MEM-4 and MEM-8 in chapter 3).

### 5.7 Reporting and CI

- The shape of the report (`build/reports/conformance/report.json`, and a human-readable summary output to the standard output and the CI summary):
  - The data version and the result of the check against `manifest.json`.
  - The language (java), the implementation version, and the storage (memory / dynamodb-local).
  - The result for each case ID and rule number. The result takes one of five values: `passed`, `failed`, `not-applicable`, `unverified`, and `unrepresentable`. The 4 cases of `fnv1a64` (K-1) are output as `not-applicable` with a reason. If one case has multiple rules, it is mapped to all of the rules.
  - The number of the failed operation, and the expected and actual values.
  - Record unrepresentable items, choices, optional capabilities, deleted rules, and caller recommendations together with reasons.
- If anything differs from the expectation midway, it is not reported as a success (IP 5). Cases that do not satisfy the conditions, results that skipped required cases, and results where a fault could not be injected are not counted as successes.
- Verification of distribution: the current `ci.yml` checks `python3 tools/conformance/manifest.py verify` and the SHA-256 of `manifest.json` (for v1.0.0, `61c26614dbbfba88eebce72cc1d2b0220218839e74dcfb64c19268f7ee2302ce`). This is kept. The manifest is not regenerated to hide a difference. If a setting is needed to copy `conformance/` with identical content and to stop line-ending conversion with `.gitattributes`, it is confirmed in the runner's PR.
- Running in CI: add the conformance run to the test matrix of the existing `ci.yml` (JDK 11, 17, 19). The test job has `timeout-minutes: 10`. Whether running the conformance on each JDK fits within it is unconfirmed. It is measured in the runner's PR. If it is exceeded, either of two proposals is submitted in that PR as a change to `ci.yml`: running the memory conformance on all JDKs and the DynamoDB conformance on JDK 11 only, or splitting the job and setting a limit on that job. DynamoDB Local is started on the test side (Testcontainers) or as a CI service container (chapter 6). The report file is saved as an artifact.

## 6. Test environment

### 6.1 DynamoDB Local

- Unify on DynamoDB Local 3.3.1. The image is pinned by digest.
  - `amazon/dynamodb-local@sha256:ff89bd48ff32cd8d9be5fee8873b65b8854dc408f1afe881be6eb00247bc0dab`
  - The record in the hub's `tools/spikes/dynamodb-emulators/` checks the same digest against the tags `latest` and `3.3.1`.
- Startup example (the container port is 8000):

```java
GenericContainer<?> dynamo =
    new GenericContainer<>(
            DockerImageName.parse(
                "amazon/dynamodb-local@sha256:ff89bd48ff32cd8d9be5fee8873b65b8854dc408f1afe881be6eb00247bc0dab"))
        .withExposedPorts(8000)
        .withCommand("-jar", "DynamoDBLocal.jar", "-inMemory");
```

- Specify the endpoint, region, and dummy credentials explicitly for the clients. Specify the endpoint explicitly for `DynamoDbClient` and `DynamoDbAsyncClient`. The first major version builds no subscriber side that reads Streams (9.3). If a test uses a Streams client, specify the endpoint explicitly for that client as well. The region of a Streams ARN becomes `ddblocal`, so the region is not inferred from the ARN.
- Create the three tables in the same region. Change the table names for each scene (5.3). `-sharedDb` is not attached. The tests pin the region and credentials to a single pair, so with `-inMemory` alone the synchronous and asynchronous clients see the same data (confirmed in the implementation: `DynamoDbFaultFoundationTest.localUsesPinnedVersionDynamicEndpointAndRealSyncAndAsyncClients`).
- To reduce startup time, use one container for the whole test run and create only the tables for each scene.

### 6.2 Migration from the current tests

- The tests start and use a DynamoDB Local pinned by digest with `GenericContainer` (`DynamoDbLocalExtension`). They used LocalStack in the past, but as in 4.1 of the implementation plan, they were moved to DynamoDB Local within stage 2.
- The current tests do not match the new API, so they are written almost from scratch (IP 4.2). The tests of the old API are deleted when the old API is deleted in the PR of chapter 7.
- The LocalStack dependency and the dependencies that are no longer used have already been removed from `build.gradle.kts`.

### 6.3 Mixed Testcontainers versions

Previously, `junit-jupiter:1.21.4` and `localstack:1.21.4` were mixed with `org.testcontainers:testcontainers:2.0.5`. The current `build.gradle.kts` uses only `testcontainers:2.0.5` and starts DynamoDB Local with `GenericContainer`, so the LocalStack and `junit-jupiter` modules became unnecessary and were removed.

## 7. How to bring this into main

Follow chapter 3 of the implementation plan. Each PR is squash-merged into main. The Snapshot of main is published as `2.0.0-SNAPSHOT`. The worker goes as far as creating the PR, and the coordinator does the merge (IP 9). One PR corresponds to one group of rules.

### 7.1 Sequence of PRs

| Order | Scope of the PR | Corresponding rule groups |
|---|---|---|
| 1 | **Preparation for the next major version**: set `version` to `2.0.0-SNAPSHOT`. Align the Testcontainers versions. The behavior of the code does not change | IP-D1, IP 4.1 |
| 2 | **Foundation of the runner**: loading (`format`, `version`, arbitrary-precision integers, expansion of generators), the check of `manifest`, the report (5 values), and the handling of unimplemented cases (7.3). No scenes are run | CR, IP 5 |
| 3 | **Core**: value types, envelopes, validation, `PayloadSerializer`, error categories, and the interfaces of the four operations. Placed in the new packages. Of the value table, run the cases that can be run with the core alone (`buildAid`, `validateSeqNr`). `fnv1a64` is reported as not applicable | T, E, H, W groups |
| 4 | **Memory**: `MemoryStorage`, `MemoryEventStore` (synchronous and asynchronous), the internal hooks of memory, and `validateOccurredAt`. Run the common scenes on memory. Tests for sharing, concurrency, and reference isolation. **The first point at which kotlin and scala may reference the Snapshot** | MEM-1 to MEM-13, common W, R, S groups |
| 5 | **Foundation of the DynamoDB tests**: starting DynamoDB Local, the `ExecutionInterceptor`, replacing requests at the HTTP layer, the fault registry, and the parsing of the request structure (4.7) | IP 6, CR |
| 6 | **DynamoDB configuration and layout**: the three tables, the configuration items, and the re-requests of `BatchGetItem`. Configuration scenes and the check against `layout.json` | DY-8, DY-16 to DY-19, D-1 to D-4, P-19, P-40 |
| 7 | **DynamoDB writes and reads**: 4.4 and 4.5. Run the common scenes on DynamoDB. **The second milestone (DynamoDB is complete)** | D-5 to D-7, DY-9 to DY-11, W, R groups |
| 8 | **DynamoDB retention**: 4.6 | D-9, S group, P-18, P-24 |
| 9 | **Deleting the old API and removing the dependency on LocalStack**: migrate the users of the new API (the current tests and samples), and delete the old API, the old packages, and the old tests | 7.2 |
| 10 | **User documentation and migration guidance**: README, `DATABASE_SCHEMA`, `MIGRATION_GUIDE` | Acceptance conditions 5 and 6 of IP 5, IP-D8 |
| 11 | **Checking the acceptance conditions and the manual release of the major version**: check the acceptance conditions (IP 5), obtain the owner's approval, and trigger `bump-version.yml` with `level=major`. Run read/write tests against other languages | Step 4 of IP 3, IP 5, ADR-0001 |

The change feed helper is not included (9.3). For this reason, no PR for the change feed is added.

The points at which kotlin and scala may reference the Snapshot are, as guidelines, after number 4, where memory is in (the first), and after number 7, where DynamoDB is complete (retention is number 8) (the second).

### 7.2 Coexistence with the old API and the point of deletion

- The new API is placed in the new packages (`core`, `memory`, `dynamodb`) and sits alongside the old API (directly under `com.github.j5ik2o.event.store.adapter.java`, and `internal`) (9.5). Since the packages differ, they do not collide.
- During PRs 3 to 8, the old API and the new API coexist in the same main.
- No new features are added to the old API. The old API is not called from inside the new API.
- The deletion of the old API is PR 9. The old packages are deleted. The users of the old API (the current tests) are moved to the new API in the same PR. No permanent compatibility layer is built. No path that automatically reads old data (fallback) is built either (IP-D8).
- The tests of the old API remain during PRs 3 to 8 and pass the CI of main.

### 7.3 How to keep main's CI passing in each PR

- **A mechanism so that unimplemented cases do not break CI.** The Snapshot is published only after main's CI passes. For this reason, the following mechanism is placed in the runner.
  - A "list of required case IDs" for each storage is placed in the test resources.
  - If a case in the list does not pass, CI fails.
  - Cases not in the list are reported as `unverified` and do not break CI. They are not counted as successes either.
  - The list is widened with each PR. At the time of PR 11, it is checked that all the cases except the not-applicable ones (`fnv1a64`, `milliseconds`) are in the list.
- While the tests of the old API pass, `./gradlew test` succeeds. In PR 9, the old API and the old tests are deleted together.
- The manifest check in the current `ci.yml` is kept in every PR.
- Whether the conformance run fits within `timeout-minutes: 10` is measured in the runner's PR, as in 5.7.
- **The danger of a version bump.** If `bump-version.yml` (manually triggered) is run with `auto`, `patch`, or `minor`, it creates the next version (such as 1.2.92) from the latest tag `v1.2.91`, writes it back to `version`, and overwrites `2.0.0-SNAPSHOT`. For this reason, `bump-version.yml` is not triggered until the major version is released. When releasing, it is triggered in PR 11 with `level=major`. The fix to the workflow was completed in #691. This PR (the design document) does not change the workflow.

## 8. Migration guidance

### 8.1 Migrating the API for 1.x users

The `MIGRATION_GUIDE` (an existing document) is updated in the PR number 10 of 7.1. What it covers:

- The correspondence table of 2.10 (what was removed and what replaced it).
- Remove the implementations of `Aggregate` and `Event` from the domain types. Wrap events in `EventEnvelope` and snapshots in `SnapshotEnvelope`.
- Optimistic locking by `version` changes to the head's `seq_nr`. The `version` argument of `persistEvent(event, version)` goes away. The expected value for the check is determined from `event.seqNr()`.
- The return value of `getLatestSnapshotById` becomes a pair of the snapshot's envelope and the head number. The starting point of restoration is `snapshot.seqNr() + 1` if there is an envelope and 1 if there is none (R-4). `headSeqNr` is not used as the starting point. Pass this starting point to `getEventsByIdSinceSeqNr`. Because the read of the two items is not atomic (R-8), the snapshot may be newer than the head. In this case, processing is needed so that already-obtained numbers are not applied again.
- Replacing the serializer. From the form that passes `Class<T>` to the form that binds it at creation.
- The change in error categories. Contract violation and configuration are new. Exceptions are distinguished by `category()`.

### 8.2 Handling of old data (IP-D8)

- Automatic migration from the old layout (the two tables `journal` and `snapshot`, with a sharded PK) to the new layout (three tables and configuration items) is **not provided**. Neither a migration tool nor automatic loading of the old layout is built (IP-D8: for java, only a procedure document).
- What the procedure document says: create the three new tables. Rewrite the old events with the new API in `seq_nr` order (if the aid changes, work on the user's side is needed). Do not mix old and new in the same table. The user deletes the old tables after confirming the rewrite.
- The serialization format of the events in the old data (currently the whole domain event is made into JSON, including the metadata) is not compatible with serializing only the payload. The procedure document states that the conversion that extracts the payload from the old JSON is the user's work. The details of the procedure document are written in the PR number 10 of 7.1.

## 9. Points that needed a decision (decided)

**This chapter records the decisions and their reasons. The "Decision" line is what was decided. No item is undecided.**

### 9.1 How to separate the synchronous and asynchronous APIs

| Option | Contents |
|---|---|
| A | Publish the synchronous `EventStore<P, A>` and the asynchronous `AsyncEventStore<P, A>` as separate interfaces (the proposal of 2.6) |
| B | Center on asynchronous, and provide the synchronous form as an adapter to the asynchronous one (calling `.join()` internally) |
| C | Give one interface both the synchronous and the asynchronous methods |

| Option | Advantages | Disadvantages |
|---|---|---|
| A | Close to the current structure (`EventStore` / `EventStoreAsync`), so 1.x users can move over easily. Synchronous users need not be aware of `CompletableFuture`. DynamoDB's clients are also separate for synchronous and asynchronous, so the mapping is straightforward. kotlin and scala can wrap only `AsyncEventStore` | The same rules are kept in two implementations. The implementation and the tests are doubled. A design that separates the shared internal logic is needed |
| B | One implementation. The rule checks are in one place | The synchronous adapter blocks the thread. The synchronous DynamoDB client cannot be used. The handling of exception wrapping increases |
| C | One type | It requires implementers to do both. There is a side that users do not use. Differences between memory and DynamoDB appear |

Recommendation: A. The reasons are that the burden of migration is small, it matches the structure of DynamoDB's SDK, and the core of checks and categorization can be cut out as a shared component.

**Decision: A (2026-10-06, the owner).** The synchronous and asynchronous forms are separate interfaces. Reason: as recommended. The burden of migration is small, it matches the structure of DynamoDB's SDK, and the core of checks and categorization can be cut out as a shared component.

### 9.2 The serializer API

| Option | Contents |
|---|---|
| A | Pass to the configuration a `PayloadSerializer<T>` (`serialize(T)`, `deserialize(byte[])`) whose type is bound at creation (the proposal of 2.4) |
| B | Keep the current form that passes `Class<T>` (or `TypeReference<T>`) for each operation |
| C | Use a form that passes the manifest, such as `deserialize(String manifest, byte[] bytes)` |

| Option | Advantages | Disadvantages |
|---|---|---|
| A | The signatures of the operations are simple. The type is decided at creation. It matches T-6 and T-7. The problems of generics erasure do not occur | One store handles only one event type. A polymorphic event hierarchy is handled inside the user's serializer |
| B | Close to the current form | The type has to be specified repeatedly for each operation. A wrong type can be passed. A generic type such as `List<E>` cannot be passed. Type erasure problems arise in the wrappers of kotlin and scala |
| C | The type can be selected by the manifest, which is strong for polymorphism | The relationship with T-4 (the library does not interpret the manifest) cannot be decided from the specification (chapter 10). It is unclear whether the specification allows passing the manifest to the serializer at all |

Recommendation: A. The reason is that a mix-up of types can be prevented at compile time and the API of the operations becomes small. C will be reconsidered after the question in chapter 10 is resolved.

**Decision: A (2026-10-06, the owner).** The serializer is in a form that decides the type when the store is created, and the form that passes `Class<T>` on every call is dropped. Reason: as recommended. A mix-up of types can be prevented at compile time, and the API of the operations becomes small.

### 9.3 Handling of the change feed (common to all languages)

Whether the first major version includes the function that assembles head transitions from the records of the head table's Streams and the helper for resync (DY-15).

| Option | Contents |
|---|---|
| A | Not included. Deferred to a later version. The Streams setting of the head table (DY-3, DY-12) is satisfied from the start as a shape of the write side |
| B | Include only the function that assembles head transitions (DY-13) |
| C | Include both the assembling function and the resync helper of DY-15 |

| Option | Advantages | Disadvantages |
|---|---|---|
| A | The scope is small and fits within "core, memory, DynamoDB" of IP-D3. The delay of 2.0.0 is small | Users of the change feed have to assemble it themselves |
| B | Reduces assembly mistakes by users. The skipping of `aid=__config__` can be shared | The public API for Streams clients and record types increases. The tests increase |
| C | Gives users of the change feed complete help | It uses `Scan`, which adds exceptions to DY-19. The scope is large. The specification may not define the shape of the DY-15 helper (chapter 10) |

Recommendation: A. The reason is that it keeps within the scope of IP-D3 and minimizes the promises of the public API. If demand on the subscriber side is confirmed, it can be added in a later version.

**Decision: A (2026-10-06, the owner; common to all languages).** The first major version includes neither the function that assembles head transitions nor the resync helper (DY-15). Reason: it keeps within the scope of IP-D3 and minimizes the promises of the public API. If demand on the subscriber side is confirmed, it will be added in a later version.

### 9.4 Whether to make exceptions unchecked (a point found during the design)

| Option | Contents |
|---|---|
| A | Make all five categories unchecked exceptions (`RuntimeException`) (the proposal of 2.7) |
| B | Keep pairs of a checked exception and an unchecked exception, as now |

| Option | Advantages | Disadvantages |
|---|---|---|
| A | Only five types are needed. The same types can be used in the asynchronous form too. Calls are simple | The compiler does not force handling. The code of 1.x users who wrote `throws` will change |
| B | Close to the current form | The number of types doubles. It does not go well with lambdas and `CompletableFuture`. Distinguishing the five categories (E-1) becomes complicated |

Recommendation: A. The reason is that the number of types is small and it is consistent with the asynchronous form.

**Decision: A (2026-10-06, the owner).** All five categories are made unchecked exceptions. Reason: as recommended. The number of types is small and it is consistent with the asynchronous form.

### 9.5 Where to put the new API (the coordinator's decision)

| Option | Contents |
|---|---|
| A | Put it in the new packages (`core`, `memory`, `dynamodb`) alongside the old API. The old packages are deleted in the stage that deletes the old API (number 9 in 7.1) |
| B | Rewrite the old packages directly |

| Option | Advantages | Disadvantages |
|---|---|---|
| A | Main's CI can pass in every PR while the tests of the old API remain. The period of coexistence is clear | While they coexist, the same type names appear in two packages |
| B | One package | The users of the old API break and the PRs cannot be split small |

**Decision: A (the coordinator).** Place the new API in new packages alongside the old, and delete the old packages in the stage that deletes the old API. Reason: main's CI can keep passing in every PR (chapter 7).

### 9.6 Whether to make the dependencies exposed by the public API `api` in the build (the coordinator's decision)

| Option | Contents |
|---|---|
| A | Make the dependencies that appear in the types of the public API `api` |
| B | Leave all of them as `implementation` |

| Option | Advantages | Disadvantages |
|---|---|---|
| A | kotlin and scala can reference types such as `DynamoDbClient` and `ObjectMapper` | These dependencies are put on the users' compile classpath |
| B | The users' classpath is small | kotlin and scala cannot reference the types of the public API |

**Decision: A (the coordinator).** The dependencies that appear in the types of the public API are made `api`. Reason: so that kotlin and scala can reference the types. The targets are as in 4.1.

## 10. Open questions

The specification is not interpreted arbitrarily to fill gaps. The items marked "answered" reflect the answers from the coordinator's review (first round), the specification decisions of 2026-10-06 (P-42 to P-44; P-45 is the procedure for the rs migration and is not relevant), and the coordinator's answers. Q7 was confirmed in the implementation and marked resolved. None of the questions in this chapter (Q1 to Q16) remains unresolved. The implementation-time items marked "unconfirmed" in other chapters are not covered by this chapter.

| No. | Question | Basis / status |
|---|---|---|
| Q1 | The number of phases for fault injection | Answered: 12 kinds (`conformance/schema/common.schema.json`). 5.5 shows the injection point for all 12 kinds |
| Q2 | "S-3 of memory" and "R-8" in `conformance/coverage.json` record the memory profile as "not agreed". The current profile was agreed on 2026-10-05 | Answered: only the record on the data side needs updating, and there is no effect on the implementation. The data is not changed |
| Q3 | When the type name or the value of `AggregateId` is the empty string | Answered: allowed. T-11 and T-12 do not forbid empty, and since the aid string contains `-` it never becomes empty (the coordinator's answer, 2026-10-06. 2.1) |
| Q4 | The category when required configuration (serializers, table names, and so on) is missing | Answered: configuration error. The configuration category in chapter 4 of the shared contract lists "an invalid configuration value at creation", and a missing value falls under it (the coordinator's answer, 2026-10-06. 2.8) |
| Q5 | The handling when a store is created on a shared `MemoryStorage` with a configuration different from the storage's | Answered: the storage's configuration is used, as in MEM-D11. The retention policy is passed through `MemoryStorage.create`, so no mismatch occurs (2.8) |
| Q6 | The handling of `seq_nr` that does not fit in `long` | Answered: the conformance data has no value beyond `long` (the maximum is 9007199254740992). It is read as `BigInteger` and converted exactly to `long`. If a value beyond `long` comes in the future, it is reported as `unrepresentable` |
| Q7 | The mapping of `observe.requests` when the `ExecutionInterceptor`'s classification changes order relative to other layers such as the SDK's retries and encryption | Answered (confirmed in the implementation): the mapping does not depend on the order in which the `ExecutionInterceptor` is called. `src/test/java/com/github/j5ik2o/event/store/adapter/java/dynamodbtest/DynamoDbRequestRecorder.java` issues an identifier for each request in `beforeExecution` and carries it in the HTTP header `x-eswa-test-request-id` in `modifyHttpRequest`. The HTTP client side (`FaultHttpClient`) looks up the request from this header and counts the number of send attempts (`httpAttempts`) and the sent content through `replacement` and `transmitted`. Regardless of the order of retries and other layers, the requests actually sent are tied together by the identifier. It was also confirmed that SDK retries do not occur. In the conformance tests, `dynamodb-events-over-one-megabyte` (`minimum_request_count`), `dynamodb-latest-unprocessed`, and `dynamodb-snapshot-ahead-of-head` (`request_count`) in `conformance/dynamodb/read.json` are run from `ConformanceRunnerTest.conformance()` through `DynamoDbCaseRunner.run` and check the counts of `observe.requests` (`DynamoDbCaseRunnerTest` only confirms that a verification fails on versions in which these are altered). The supporting unit test is `DynamoDbFaultFoundationTest.sdkDoesNotRetryRetryableFailuresInEitherTransport` (`httpAttempts` is 1 for both synchronous and asynchronous). |
| Q8 | Whether E-2's "do not include the SDK's raw error text" forbids attaching the SDK's exception as the exception's `getCause()` | Answered: the target of E-2 is the message. Having a cause exception is not forbidden. However, the SDK's text does not appear in `getMessage()` or `toString()` |
| Q9 | The format of the "rule number" in the messages of contract violations and retention failure notifications | Answered: the form `"W-6"` or `"T-13"` is enough |
| Q10 | Whether `manifest` may be passed to the `PayloadSerializer` (option C of 9.2) | Answered: T-4 does not forbid passing it (it is enough not to interpret it). However, since A was chosen in 9.2, it is not passed in this document |
| Q11 | The direction of rounding of `last_updated_at` (milliseconds) | Answered: the direction of rounding is unspecified. The design uses `Instant.toEpochMilli()` (the floor function) (4.3) |
| Q12 | Whether java may report `fnv1a64` as "not applicable" | Answered: not applicable is appropriate. The first major version has no storage that uses hashes (acceptance condition 1 of chapter 5 of the implementation plan) |
| Q13 | The problem of fitting `occurred_at` (epoch nanoseconds) into the significant digits (38 digits) of DynamoDB's N attribute | Answered: N has 38 digits and a signed 64 bits is 19 digits, so it fits |
| Q14 | The category for the omission of the required elements of T-2 and T-10 | Answered: make it a contract violation (shared contract T-2, T-10, E-3, P-42. Owner decision of 2026-10-06). `build()` throws `ContractViolationException` (2.2, 2.3) |
| Q15 | The handling when `TransactionConflict` is returned while creating the configuration items, and giving up when the configuration items are still missing after a re-read | Answered: `TransactionConflict` is also re-read like a condition failure, and if the configuration items are still missing after the re-read, it is a storage error (DY-8, P-44. Owner decision of 2026-10-06. 4.2) |
| Q16 | The category when the TTL grace period is a negative value | Answered: 0 is valid, negative is a configuration error ("an invalid configuration value at creation" in chapter 4), and no upper limit is set (the coordinator's answer, 2026-10-06. 2.8) |
