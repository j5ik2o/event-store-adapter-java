# 次のメジャー版（2.0.0）の設計文書（java）

- 対象リポジトリ: j5ik2o/event-store-adapter-java
- 状態: 設計案。**レビュー前。オーナーの決定は未了。**
- 規範: ハブ j5ik2o/event-store-adapter の共通契約（`docs/spec/core-contract.md`）、DynamoDB プロファイル、メモリのプロファイル、実装計画（`docs/plan/implementation-plan.md`）、決定記録（`docs/adr/`）。
- 規則番号の出典: T・H・W・R・E・S は共通契約、D・DY・P は DynamoDB プロファイル、MEM はメモリのプロファイル。「IP」は実装計画の節。「CR」は `conformance/README.md`。
- この文書は仕様を変えない。規則も足さない。仕様の読み方に迷った点は 10 章に書く。
- この文書の API と実装の案は「推奨案」である。仕様上の必須条件ではない。選択肢が分かれる点は 9 章に書き、オーナーが決める。
- この文書を作る作業ではコードを書いていない。実装は 7 章の PR 列で行う。

## 1. 目的と範囲

### 1.1 目的

共通契約に合わせて、このリポジトリを書き直す。現行の 1.x は、集約の `version` とスナップショットで楽観ロックを行う。新契約は、集約ヘッドの `seq_nr` で行う（H-2・W-8）。現行 API は新契約の封筒・エラー分類・ヘッドの形と合わない。このため API を作り直す。

### 1.2 最初のメジャーに含めるもの

| 含める | 根拠 |
|---|---|
| 中核（値型・封筒・検査・シリアライザ・エラー分類・4つの操作） | IP-D3、共通契約 1〜4 章 |
| メモリ実装（新規。現行にない） | IP-D3・IP-D4・IP-D5、MEM-1〜MEM-13 |
| DynamoDB 実装（3テーブルと設定項目への作り直し） | IP-D3、DY-2〜DY-19（DY-1 は削除済み） |
| 適合テストデータの実行器（メモリと DynamoDB の両方） | IP 5 の受け入れ条件 1 |
| README・DATABASE_SCHEMA・移行ガイドの更新 | IP 5 の受け入れ条件 5・6 |

### 1.3 外すもの

| 外す | 理由 |
|---|---|
| メモリの TTL 方式 | MEM-12。要求は設定エラー |
| メモリの変更フィード | MEM-13。要求は設定エラー |
| 永続化するメモリ・全消去 | MEM-1 |
| 旧データの自動読み込み・移行ツール | IP-D8。java は手順書だけ |
| 旧 API の永続的な互換層 | 7 章。共存は移行中だけ |
| kotlin・scala の包む層の設計 | 段階 3 の別作業。2.9 章で使う型と操作だけ示す |
| 変更フィードの補助関数（ヘッド遷移の組み立て・DY-15 の再同期補助） | 含めるかどうかは 9 章でオーナーが決める。決まるまで範囲外 |

### 1.4 版の番号

- 次のメジャーは `2.0.0`。現行の `version` ファイルは `1.2.92-SNAPSHOT`。
- main の Snapshot は `2.0.0-SNAPSHOT` で公開する。main への squash マージ後に CI が成功すると、`snapshot.yml` が Snapshot を公開する（IP 3）。
- 1.x の修正が要る場合の扱いは、実装計画 3 章に従う。この文書では扱わない。
- Java の対象版は 11 のまま（`build.gradle.kts` の toolchain）。API は `record`・`sealed`・`var` を使わずに書く。

## 2. 公開 API

パッケージは、旧 API と共存するため新しい名前にする（7 章）。

| パッケージ | 内容 |
|---|---|
| `com.github.j5ik2o.event.store.adapter.java.core` | 値型・封筒・シリアライザ・エラー・設定・4つの操作の型 |
| `com.github.j5ik2o.event.store.adapter.java.memory` | メモリ実装 |
| `com.github.j5ik2o.event.store.adapter.java.dynamodb` | DynamoDB 実装 |

以下の宣言は `import` と Javadoc を省いている。注釈の `@Nonnull` は現行と同じく jsr305 を使う。各要素の「規則」欄は、その要素が満たす規則番号。

### 2.1 集約 ID（T-1・T-11・T-12）

```java
public final class AggregateId {
  public static AggregateId of(String typeName, String value);   // T-1, T-11, T-12
  public String typeName();
  public String value();
  public String asString();   // typeName + "-" + value。ライブラリが組み立てる（T-1）
  // equals / hashCode / toString は asString() に基づく。
}
```

| 項目 | 内容 | 規則 |
|---|---|---|
| 組み立て | `of` が型名と値から `typeName + "-" + value` を作る。利用者の `asString()`・`toString()` は使わない | T-1 |
| 型名に `-` を含む | `of` が `ContractViolationException`（規則 T-11）を投げる。値は `-` を含んでよい | T-11 |
| 1024 バイト超 | UTF-8 のバイト数で判定する。型名・値・区切りの合計。文字数（`String.length()`）では数えない | T-12 |
| 空の型名・空の値 | 仕様は何も定めない。10 章に書く | — |

現行の `AggregateId` はインターフェイスで、利用者が `asString()` を実装していた。新 API は final クラスにする。利用者のドメイン ID は、型名と値を渡して `AggregateId.of` を呼ぶ。適合データの `buildAid` は `user_string` を返す試験用 ID を使う。実行器は試験用 ID から型名と値を取り出して `AggregateId.of` に渡し、結果が `user_string` に依存しないことを確かめる（5 章）。

### 2.2 イベント封筒（T-2・T-3・T-4・T-5・T-13）

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
    public EventEnvelope<P> build();   // 必須要素の欠落は ContractViolationException
  }
}
```

| 項目 | 内容 | 規則 |
|---|---|---|
| 必須 | `manifest` 以外は必須。欠ければ `build()` が契約違反。`manifest` の省略時は空文字列 | T-2 |
| 不変・拡張 | final クラスとビルダー。要素が増えてもビルダーの呼び出しは壊れない | T-5 |
| 時刻 | `Instant`（ナノ秒精度）。ストア側の時刻で置き換えない。読み取りで同じ値を返す | T-3 |
| 時刻の値域 | エポックからのナノ秒が符号付き 64bit に収まる範囲。範囲外は契約違反 | T-13 |
| 解釈しない | ライブラリは `manifest`・`payload` を解釈しない | T-4 |

**T-13 の検査の注意。** `Instant.getEpochSecond() * 1_000_000_000L + getNano()` を `Math.multiplyExact` で計算すると、有効な最小値付近で途中の乗算があふれる。例えば最小値は秒が負で、秒だけを先に 1e9 倍すると `Long.MIN_VALUE` を下回る場合がある。有効な時刻を誤って拒まないため、`BigInteger` で計算するか、`(秒 + 1) * 1e9 + (ナノ - 1e9)` の形で計算する。実装 PR で、境界値の単体試験を書く。

検査の置き場所は、封筒の `build()` とする。無効な入力の封筒構築中の規則違反も、操作の失敗として捕捉できる（5 章）。ストアの操作も、受け取った封筒を再検査する（封筒が不正に作られた場合の防御ではなく、メモリ・DynamoDB のどちらでも同じ検査を通すため。検査は中核の 1 か所に置く）。

### 2.3 スナップショット封筒（T-10）

```java
public final class SnapshotEnvelope<A> {
  public static <A> Builder<A> builder();
  public A aggregate();       // 必須
  public long seqNr();        // 必須
  public String manifest();   // 省略時は ""

  public static final class Builder<A> {
    public Builder<A> aggregate(A v);
    public Builder<A> seqNr(long v);           // T-9 の一般値域（0 も有効）
    public Builder<A> manifest(String v);
    public SnapshotEnvelope<A> build();   // aggregate の欠落は ContractViolationException（T-10）
  }
}

public final class SnapshotReadResult<A> {
  public Optional<SnapshotEnvelope<A>> snapshot();   // なくてもよい（R-2・R-3）
  public long headSeqNr();                           // 読み取り時点のヘッド（R-2）
}
```

- ヘッドの `seq_nr` は `SnapshotEnvelope` に含めない。`SnapshotReadResult.headSeqNr()` で別に返す（T-10、ADR-0002）。
- 書き込みと読み取りで同じ `SnapshotEnvelope` を使う（T-10）。
- `getLatestSnapshotById` の戻り値は `Optional<SnapshotReadResult<A>>`。外側の空は、ヘッドがない（集約が存在しない）ことを表す（R-1）。内側の空は、ヘッドはあるがスナップショットがないことを表す（R-3）。

### 2.4 payload とシリアライザ（T-6・T-7・T-8）

推奨案は次の形である。**オーナー未決定。9 章で選択肢を比べる。**

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

| 項目 | 内容 | 規則 |
|---|---|---|
| ドメイン型への要求 | `Aggregate`・`Event` などライブラリのインターフェイスを求めない。`PayloadSerializer` で直列化できれば足りる | T-6 |
| メタデータ | シリアライザは payload（集約状態）だけを直列化する。aid・seq_nr・時刻・manifest を埋め込まない | T-7 |
| 既定 | JSON（Jackson。現行の依存を維持する） | T-8 |
| `Class<T>` | 現行は読み取り操作ごとに `Class<E>`・`Class<A>` を渡す。新 API は、シリアライザの生成時に型を束縛し、操作では渡さない | 9 章 |

現行の `EventSerializer`・`SnapshotSerializer`・`DefaultEventSerializer`・`DefaultSnapshotSerializer` は置き換えて削除する（2.10 章）。直列化と復元の失敗は、どちらも新しい `SerializationException`（分類 `SERIALIZATION`）で表す。現行の `DeserializationException` は置き換えて削除する。

### 2.5 整数と時刻の型（T-9・T-13・1.5）

| 値 | Java の型 | 検査 | 規則 |
|---|---|---|---|
| `seq_nr` | `long` | 0 以上 2^53−1 以下。範囲外は契約違反。`long` は負数を表せるため、検査が要る | T-9 |
| イベントの `seq_nr` = 0 | `long` | T-9 では有効。イベントでは契約違反（規則 W-6） | W-6 |
| `occurred_at` | `java.time.Instant` | エポックからのナノ秒が符号付き 64bit に収まる範囲 | T-3・T-13 |
| 保存する時刻 | `long`（エポックナノ秒） | `Instant` から整数演算で変換。浮動小数点を通さない | T-13 |

`Instant` はナノ秒精度を持つ。Java の適合実行は、適合データの `time_precision` が `nanoseconds` のケースを実行する。`milliseconds` のケースは実行しない（対象外として理由を報告する。5 章）。

検査は中核に 1 か所だけ置く。

```java
public final class Validation {   // `core` パッケージ内部（`internal` 扱い。公開 API には含めない。`checkSeqNr` は試験から呼べるようにする）
  static void checkSeqNr(long seqNr, Context context);      // context=VALUE | EVENT
  static void checkOccurredAt(Instant occurredAt);
  static void checkAidString(String typeName, String value);
}
```

適合データの `validateSeqNr` は、`context=value` では `Validation.checkSeqNr(seqNr, Context.VALUE)` を、`context=event` では `EventEnvelope.builder()...build()` を通して確かめる（`build()` は `Context.EVENT` で呼ぶ）。`validateOccurredAt` は封筒の構築と `persistEvent` を通す。同じ数式を実行器で計算するだけにしない（5 章）。

### 2.6 4 つの操作と非同期の表現

操作名は現行の Java の慣習（`getLatestSnapshotById`・`getEventsByIdSinceSequenceNumber`）を踏まえ、共通契約の名前に寄せる。書き込みは期待値の引数を取らない。照合の期待値は `event.seqNr()` から決まる（W-3・W-4・W-8）。

推奨案は、同期用と非同期用を別のインターフェイスにする形である。**オーナー未決定。9 章で比べる。**

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

| 操作 | 振る舞い | 規則 |
|---|---|---|
| `persistEvent` | 1 件のイベントを追記する。`seq_nr=1` は新規作成（スナップショットなしでも作れる）。`seq_nr>1` は更新で、ヘッドの `seq_nr` が `event.seqNr()−1` のときだけ確定する | W-3・W-4・W-8・H-1〜H-4 |
| `persistEventAndSnapshot` | イベントとスナップショットを同じ確定で書く。`snapshot.seqNr() != event.seqNr()` は契約違反 | W-9・H-1 |
| 追記の失敗 | `seq_nr=0` は契約違反。同じ `seq_nr` の 2 件目は楽観ロック。ヘッド以下は楽観ロック。ヘッド+2 以上は飛び番の契約違反。ヘッドのない集約への `seq_nr` 2 以上は、ヘッド 0 とみなして飛び番の契約違反 | W-6・W-7・W-8 |
| `getLatestSnapshotById` | ヘッドがなければ空。あれば `SnapshotReadResult` を返す。エラーにしない | R-1〜R-3 |
| `getEventsByIdSinceSeqNr` | `seqNr` 以上のイベント封筒を昇順に全件返す。ページ上限があれば読み切る | R-4〜R-6 |
| 原子性の宣言 | メモリは最新スナップショットとヘッドを原子的に読む（MEM-8）。DynamoDB は原子的に読まない（DY-9・R-8） | R-8 |

- 現行の `getEventsByIdSinceSequenceNumber` のページ送りは、同期実装・非同期実装とも既に実装されている。新実装も読み切りを維持する（R-5・DY-11）。
- 非同期のエラー: 失敗は `completeExceptionally` に、分類済みの例外をそのまま渡す。`join()` が包む `CompletionException`・`ExecutionException` から分類済みの例外を取り出すため、`EventStoreExceptions.unwrap(Throwable)` を提供する（E-1）。実行器もこれを使う。
- 非同期のメモリ実装: ブロックしない保証は不要とし、呼び出しスレッドで完了済みの `CompletableFuture` を返す案を推奨する。排他制御（3 章）は同期実装と共有する。9 章に含める。

### 2.7 エラー分類（E-1・E-2・E-3）

5 分類を、型と判別可能な値の両方で区別する（E-1）。例外は非検査例外にする案を推奨する（9 章）。

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
  public OptionalLong seqNr();          // 関係する seq_nr
}
public final class SerializationException extends EventStoreException { ... }   // 直列化・復元の両方
public final class ConfigurationException extends EventStoreException { ... }
public final class StorageException extends EventStoreException { ... }
```

| 分類 | 発生する場面 | 規則 |
|---|---|---|
| 楽観ロック | 既存集約への新規作成、`seq_nr` の重複、ヘッド以下での追記 | 4 章・W-3・W-7・W-8・D-6 |
| 契約違反 | W-6・W-9・T-9・T-11・T-12・T-13、W-8 の飛び番、D-7 のサイズ超過 | 4 章 |
| 直列化 | payload の直列化・復元の失敗 | 4 章 |
| 設定 | 生成時の不正な設定値（保持件数 0 など）、保存先に記録された設定との食い違い | 4 章・S-1・MEM-3・P-40 |
| 保存先 | 通信・保存先の失敗、読み取ったデータの欠損、設定読み取りの再要求の上限到達 | 4 章・DY-8 |

メッセージの規則:

- 楽観ロック（E-2）: 含めてよいのは、aid 文字列・追記しようとした `seq_nr`・（分かれば）ヘッドの `seq_nr` だけ。接続文字列・資格情報・SDK の生のエラー文は含めない。例外の `getCause()` に SDK の例外を付けるかどうかは仕様が定めない。付けた場合も `getMessage()` には含めない。10 章に書く。
- 契約違反（E-3）: 違反した規則番号と関係する `seq_nr` をメッセージに含める。W-9 は、イベントの `seq_nr` と異なるスナップショットの `seq_nr` の両方を含める。ヘッドの番号は必須にしない。
- D-7 のサイズ超過: 分類が契約違反であることだけが必須。規則番号とメッセージの条件は要らない。実装は、規則番号 `D-7` を入れてよい。
- 呼び出し側は `category()` の値か、例外のクラスで区別する。メッセージ文字列から分類を推測しない（CR）。

### 2.8 設定（S-1・MEM-3・DY-8）

```java
public enum RetentionMode { DELETE, TTL }

public final class RetentionPolicy {
  public static RetentionPolicy none();                          // 履歴を持たない（保持件数「なし」）
  public static RetentionPolicy delete(int keepCount);           // keepCount < 1 は ConfigurationException（S-1）
  public static RetentionPolicy ttl(int keepCount, Duration grace);  // DynamoDB だけ（MEM-12）
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
    public Builder<P, A> retentionPolicy(RetentionPolicy v);                        // S-1。省略時は none()
    public Builder<P, A> retentionFailureListener(RetentionFailureListener v);      // S-4・MEM-11。省略可
    public EventStoreConfig<P, A> build();   // 必須の欠落・不正な値は ConfigurationException
  }
}

public final class EventStoreExceptions {
  public static Throwable unwrap(Throwable t);   // CompletionException・ExecutionException を外して分類済みの例外を返す（E-1）
}
```

メモリ:

```java
public final class MemoryStorage {
  public static MemoryStorage create();                // 新しい独立した保存先（MEM-2）
}
public final class MemoryEventStore {
  public static <P, A> EventStore<P, A> create(MemoryStorage storage, EventStoreConfig<P, A> config);
  public static <P, A> AsyncEventStore<P, A> createAsync(MemoryStorage storage, EventStoreConfig<P, A> config);
}
```

- `MemoryStorage` を同じオブジェクトで渡したインスタンスだけが、記録・設定・排他制御を共有する。名前が同じだけでは共有しない（MEM-2）。
- `MemoryEventStore.create` は生成時に設定を検査する。保持件数 0・TTL 方式・変更フィードの要求は `ConfigurationException`（MEM-3・MEM-12・MEM-13）。変更フィードを要求する設定項目は、公開 API には設けない。適合データがその場面を持つ場合は、実行器が未提供の能力として扱う。10 章に書く。
- 設定は保存先が不変で持ち、共有するインスタンスはその設定を使う（MEM-3）。共有する `MemoryStorage` に違う設定で 2 つ目のストアを生成した場合の扱いは、仕様が定めない。9.6 と 10 章に書く。

DynamoDB:

```java
public final class DynamoDbTableConfig {
  public static Builder builder();

  public static final class Builder {
    public Builder journalTableName(String v);        // DY-8。必須
    public Builder snapshotTableName(String v);       // 必須
    public Builder headTableName(String v);           // 必須
    public Builder snapshotAidIndexName(String v);    // snapshot の GSI 名（D-3）。必須
    public Builder clock(Clock v);                    // TTL の印付け時刻。省略時は Clock.systemUTC()（S-3）
    public DynamoDbTableConfig build();               // 必須の欠落は ConfigurationException
  }
}
public final class DynamoDbEventStore {
  public static <P, A> EventStore<P, A> create(
      DynamoDbClient client, DynamoDbTableConfig tables, EventStoreConfig<P, A> config);
  public static <P, A> AsyncEventStore<P, A> createAsync(
      DynamoDbAsyncClient client, DynamoDbTableConfig tables, EventStoreConfig<P, A> config);
}
```

- テーブルとインデックスの作成は、ライブラリの外（`dynamodb.md` 2 章）。テーブル名・インデックス名は設定で与える（DY-8）。
- 現行の `shardCount`・`KeyResolver`・`withDeleteTtl`・`journalAidIndexName` は廃止する（DY-16）。
- `Clock`（TTL の印付け時刻）と、設定読み取りの再要求の待ち時間は、試験から差し込めるようにする（5 章）。`Clock` は `DynamoDbTableConfig.Builder.clock` で渡す公開の設定項目とする。待ち時間の差し込み（`Sleeper`）は、パッケージ内部の型にして、公開 API に含めない。

設定エラーになる値:

| 値 | 結果 | 規則 |
|---|---|---|
| `RetentionPolicy.delete(0)`・負数 | `ConfigurationException` | S-1 |
| `RetentionPolicy.ttl` をメモリに渡す | `ConfigurationException` | MEM-3・MEM-12 |
| 保存先に記録された設定（`store_id`・`layout_version`）との食い違い | `ConfigurationException` | P-40・DY-8 |
| 3 テーブルの設定項目が一部だけある | `ConfigurationException` | P-40 |
| 必須の設定（シリアライザ・テーブル名）の欠落 | `ConfigurationException` | 仕様は定めない。10 章 |

### 2.9 保持処理の失敗を知らせる経路（S-4・MEM-11）

- 保持処理の失敗は、書き込みの結果を変えない。書き込みは成功として返る（S-4・MEM-11）。
- 通知の経路は 2 つ。SLF4J のログ（WARN。slf4j-api は現行の依存）と、`RetentionFailureListener`（任意）。コールバックは、最終的な失敗を 1 回だけ受け取る。
- `RetentionFailure` は、aid 文字列・原因の例外・保持方式を持つ。`getMessage()` に資格情報や SDK の生のエラー文を含めない（E-2 に準じる。保持の失敗通知の内容は仕様が定めない。10 章）。
- メモリは、排他制御を解いた後に通知する（MEM-11）。コールバックの中で例外が出ても、書き込みの結果を変えない。
- 適合データの `observe.notifications`（分類 `retention-failure`）は、実行器が `RetentionFailureListener` で受け取って検査する。ログの文字列を解析しない。

### 2.10 現行の公開 API との対応表

| 現行 | 新 API | 扱い |
|---|---|---|
| `EventStore<AID, A, E>`（同期） | `EventStore<P, A>` | 置き換え。7 章の PR で旧版を削除 |
| `EventStoreAsync<AID, A, E>` | `AsyncEventStore<P, A>` | 置き換え |
| `EventStore.ofDynamoDB(...)`・`EventStoreAsync.ofDynamoDB(...)` | `DynamoDbEventStore.create`・`createAsync` | 置き換え。引数を 3 テーブルと GSI 名に変える |
| `EventStoreOptions`（`withKeepSnapshotCount`・`withDeleteTtl`・`withKeyResolver`・`withEventSerializer`・`withSnapshotSerializer`） | `EventStoreConfig` と `RetentionPolicy` | 置き換え。ミューテーションをやめビルダーにする |
| `Aggregate<This, AID>`（`getVersion`・`withVersion`・`getSequenceNumber`） | なし。ドメイン型にライブラリの型を求めない | 削除（T-6）。照合はヘッドの `seq_nr`（H-2） |
| `Event<AID>`（`getId`・`getAggregateId`・`getSequenceNumber`・`getOccurredAt`・`isCreated`） | `EventEnvelope<P>` | 置き換え。`isCreated` は `seqNr == 1` から決まる（W-3） |
| `AggregateId`（インターフェイス） | `AggregateId`（final クラス） | 置き換え（T-1） |
| `AggregateAndEvent` | なし | 削除。書き込みはイベントとスナップショットの封筒を別々に渡す |
| `EventSerializer`・`SnapshotSerializer`・`DefaultEventSerializer`・`DefaultSnapshotSerializer` | `PayloadSerializer<T>`・`JsonPayloadSerializer` | 置き換え（T-6〜T-8） |
| `KeyResolver`・`DefaultKeyResolver` | なし | 削除（DY-16。aid 文字列がそのまま PK） |
| `persistEvent(E event, long version)` | `persistEvent(EventEnvelope<P>)` | `version` 引数を削除（H-2） |
| `persistEventAndSnapshot(E event, A aggregate)` | `persistEventAndSnapshot(EventEnvelope<P>, SnapshotEnvelope<A>)` | 置き換え（W-9） |
| `Optional<A> getLatestSnapshotById(Class<A>, AID)` | `Optional<SnapshotReadResult<A>> getLatestSnapshotById(AggregateId)` | 戻り値を封筒とヘッド番号の組に（R-2）。`Class<A>` を削除 |
| `List<E> getEventsByIdSinceSequenceNumber(Class<E>, AID, long)` | `List<EventEnvelope<P>> getEventsByIdSinceSeqNr(AggregateId, long)` | 名前変更。封筒を返す（R-6） |
| `EventStoreBaseException`・`EventStoreBaseRuntimeException` と、検査・非検査のペア（`EventStoreReadException`・`EventStoreWriteException`・`SerializationException`・`DeserializationException`・`OptimisticLockException` と各 `...RuntimeException`） | `EventStoreException` と 5 つの子クラス | 置き換え（E-1）。契約違反・設定を追加。非検査例外にまとめる案（9 章） |
| `internal.EventStoreSupport`・`EventStoreForDynamoDB`・`EventStoreAsyncForDynamoDB` | `dynamodb` パッケージの内部実装 | 作り直す。旧版は 7 章の PR で削除 |

### 2.11 kotlin・scala が包むときに使う型と操作

段階 3 で、kotlin・scala は、この java の新 API を Snapshot で参照し、包む層を作り直す。包むときに使うものは次のとおり。kotlin・scala 側の設計は、この文書では行わない。

| 区分 | 使う型・操作 |
|---|---|
| 値・封筒 | `AggregateId`・`EventEnvelope<P>`・`SnapshotEnvelope<A>`・`SnapshotReadResult<A>` |
| シリアライザ | `PayloadSerializer<T>`（`JsonPayloadSerializer`） |
| 操作 | `AsyncEventStore`（`CompletableFuture`。kotlin のコルーチン・scala の `Future` へ橋渡ししやすい）。同期の `EventStore` も使える |
| 設定 | `EventStoreConfig`・`RetentionPolicy`・`DynamoDbTableConfig`・`MemoryStorage` |
| エラー | `EventStoreException` と `ErrorCategory`・`EventStoreExceptions.unwrap` |
| 通知 | `RetentionFailureListener` |

## 3. メモリの実装方針

実装は `memory` パッケージに置く。MEM-1〜MEM-13 を次のように満たす。

| 規則 | 方針 |
|---|---|
| MEM-1 | 単一プロセスの JVM ヒープだけに持つ。永続化しない。全消去の API を提供しない（`MemoryStorage` に `clear` を持たせない） |
| MEM-2 | `MemoryStorage.create()` は空の独立した保存先を作る。ストアは `MemoryStorage` の参照を受け取る。同じオブジェクトを渡したストアだけが、記録・設定・排他制御を共有する。名前・static な辞書で共有しない |
| MEM-3 | `create` が設定を検査する。保持件数 0・TTL 方式を `ConfigurationException` にする。変更フィードを要求する設定項目は持たない。設定は `MemoryStorage` が不変で持ち、共有するインスタンスに別の設定を持たせない（9.6） |
| MEM-4 | `MemoryStorage` が 1 つの `ReentrantLock` を持つ。同じ保存先への書き込み・読み取りがこのロックを使う。複数スレッドからの呼び出しを保護する。確定前の途中状態は、ロックの外から読めない |
| MEM-5 | aid は `AggregateId.asString()` を `HashMap<String, ...>` のキーにして、完全一致で比較する。ハッシュ値だけで識別しない。前方一致で選ばない。aid と `seq_nr` は別のキー。T-9・T-11・T-12・T-13 は中核の検査を通す |
| MEM-6 | 集約ごとに、メタデータ（`seq_nr`・`occurredAt`・`manifest`）と、直列化した `byte[]`（payload）を別に持つ。書き込み時に `PayloadSerializer` で直列化して保持し、取得時に復元する。利用者が書き込み後にオブジェクトを変更しても、取得結果を変更しても、保存値は変わらない。payload に T-6 を超える要件を課さない |
| MEM-7 | ロックを取る前に、入力検査（封筒・W-6・W-9・T-9 ほか）と、失敗しうる payload の直列化を済ませる。ロックの中では、ヘッドの読み取り・照合（W-3・W-7・W-8）・変更の準備・まとめた公開（確定）・保持処理だけを行う。準備中の失敗は記録を変えない。イベントだけの `seq_nr=1` も新規作成できる（W-3） |
| MEM-8 | `getLatestSnapshotById` は、同じロックの中でヘッドの `seq_nr` と最新スナップショットの封筒を読む。復元（`deserialize`）はロックを解いた後に行う案を推奨する。復元の失敗は直列化の分類で返す |
| MEM-9 | `getEventsByIdSinceSeqNr` は、同じロックの中でイベントの `byte[]` とメタデータの写しを全件作る。復元はロックを解いた後 |
| MEM-10 | 保持件数がなければ、現在のスナップショットだけを持つ。保持件数 n では、新しい n 件を残して古い順に取り除く。確定の後、同じロックの中で行う。イベントだけの追記でも、取り残しを片付ける。ジャーナルとヘッドは取り除かない |
| MEM-11 | 保持処理の失敗は、書き込みの成功を変えない。ロックを解いた後に、ログと `RetentionFailureListener` で知らせる。次の追記の保持処理で再試行する |
| MEM-12 | TTL 方式の要求は `ConfigurationException` |
| MEM-13 | 変更フィードを提供しない |

**排他制御の範囲。** 1 つの `MemoryStorage` につき 1 つのロックで、ヘッド・ジャーナル・スナップショット・履歴のすべてを守る。保存先が違えばロックも別になる。ロックを集約ごとに細かく分けない（最初の版では、正しさを優先する。性能の改善は仕様の外）。

**確定の方法。** ロックの中で、新しいヘッド・ジャーナルの行・スナップショット・履歴の変更をまず作業用の変数に作る。準備がすべて成功した後に、それらを 1 回で保存先へ公開する。準備のどこかが失敗した場合は、何も公開しない（H-1・MEM-7）。

**入力と取得結果の隔離。** 保存するのは不変の値（`long`・`Instant`・`String`）と直列化した `byte[]` だけ。`byte[]` は保存時と取得時に複製する。ドメインオブジェクトの参照は保持しない。

**非同期。** `AsyncEventStore` のメモリ実装は、同じ内部実装を呼び、完了済みの `CompletableFuture` を返す（2.6）。

**保持の履歴（MEM-10）。** 履歴は、スナップショットの `seq_nr` の降順で持つ。DynamoDB の疎な GSI と同じく、新しい n 件を残す（S-2）。履歴の「印付き」は DynamoDB の TTL 方式の概念であり、メモリには存在しない（MEM-12）。メモリの内部履歴の検査フックは、`marked` を常に空集合として返し、期待値と完全一致で比べる（適合データの削除方式のケースは `marked: []` を指定する）。対象外にするのは、TTL 方式を要求するケース（`requires=["ttl"]`）だけである。

**テスト用のフック。** 保持の障害・内部履歴の検査・確定直前の障害のためのフックは、`memory` パッケージのパッケージ内部の型（public にしない）として置く。実行器は `src/test/java` の同じパッケージ名の下に置き、そのフックへアクセスする（5 章）。

## 4. DynamoDB の実装方針

### 4.1 SDK と版

- AWS SDK for Java v2 の `software.amazon.awssdk:dynamodb:2.55.11`（`build.gradle.kts` の現行の版）を維持する。同期の `DynamoDbClient` と、非同期の `DynamoDbAsyncClient` の両方を使う。
- 版を変える場合は 9 章ではなく、実装 PR で理由を示す。
- 現行の `build.gradle.kts` には、jackson・vavr・slf4j-api がある。新実装で vavr を使うかどうかは実装 PR で決める。使わなければ依存から外す。
- Streams を読む購読側の機能は、最初のメジャーに含めるか 9 章で決める。含める場合は、Streams のクライアントにも endpoint を明示する（6 章）。

### 4.2 3 テーブルと設定項目（DY-2〜DY-8・DY-16〜DY-19）

| テーブル | 主キー | GSI | Streams | 備考 |
|---|---|---|---|---|
| journal | PK `aid`(S)、SK `seq_nr`(N) | なし | 無効 | |
| snapshot | PK `aid`(S)、SK `skey`(N) | `(aid, active_history_seq_nr(N))`・KEYS_ONLY（D-3） | 無効 | TTL は属性 `ttl`。TTL 方式のときだけ有効（DY-2） |
| head | PK `aid`(S)、SK なし | なし | 有効・NEW_IMAGE（DY-3・DY-12・D-4） | |

- PK は aid 文字列そのもの。論理シャードもハッシュも使わない（DY-16）。journal の SK は `seq_nr`、snapshot の `skey` は現在が 0・履歴がその `seq_nr`（DY-17）。
- 3 テーブルは同じリージョン。読み取りは強整合。保持処理の GSI の読み取りだけが結果整合（DY-18）。
- 使う操作は、aid に絞った `GetItem`・`BatchGetItem`・`Query`・`TransactWriteItems`・`BatchWriteItem`・`UpdateItem` だけ。通常の操作に `Scan` を使わない（DY-19）。
- テーブルの作成は、ライブラリの外（利用者・試験環境）。ライブラリは、作成の操作を持たない。

**設定項目（DY-8）。** 3 テーブルに 1 件ずつ。aid は `__config__`。journal は `seq_nr=0`、snapshot は `skey=0`、head は SK なし。属性は `store_id`(S) と `layout_version`(N、初版 1) だけ。snapshot の設定項目は `active_history_seq_nr` を持たない。

生成時の手順:

1. 3 設定項目を 1 回の `BatchGetItem`（`ConsistentRead=true`）で読む。`Responses` を蓄積する。
2. `UnprocessedKeys` があれば、そのキーだけを、指数バックオフで強整合のまま再要求する。未処理がなくなるまで「存在しない」と判定しない。再要求の上限に達した場合は、設定エラーではなく保存先エラー（`StorageException`）。
3. 3 つともなければ、新しい `store_id`（ランダム値）で 1 回の `TransactWriteItems` を送る。各 Put に `attribute_not_exists(aid)` を付ける。条件が成立しなかった場合は、応答を捨てて 3 件を強整合で読み直し、手順 4 へ（P-19）。
4. 3 つともあり、`store_id` が一致し、`layout_version` が自分の版と同じなら続行する。
5. それ以外（一部だけある・`store_id` の不一致・`layout_version` の違い）は `ConfigurationException`（P-40）。

必要な IAM は、3 テーブルへの `dynamodb:BatchGetItem` と `dynamodb:PutItem`。設定項目は条件付きの Put だけで作る。

### 4.3 項目の属性（5 章）

| 項目 | 属性 |
|---|---|
| ジャーナル | `aid`(S)・`seq_nr`(N)・`occurred_at`(N、エポックナノ秒)・`manifest`(S)・`payload`(B) |
| スナップショット（現在） | `aid`・`skey`(N、0)・`seq_nr`(N)・`manifest`(S)・`payload`(B)・`last_updated_at`(N、`occurred_at` のミリ秒)。`active_history_seq_nr` も `ttl` も持たない |
| スナップショット（履歴） | 現在と同じ属性。印がない間だけ `active_history_seq_nr`(N)。TTL の印を付けると `ttl`(N、エポック秒) を持ち、`active_history_seq_nr` を除く。期限のない項目は `ttl` 属性自体を持たない |
| ヘッド | `aid`(S)・`type_name`(S)・`seq_nr`(N)・`events`(L)。`events` の要素は M（`seq_nr`・`occurred_at`・`manifest`・`payload`）で、要素数 1 |

`occurred_at` は `Instant` から整数演算でエポックナノ秒に変換する（2.5）。N 属性は `AttributeValue.n(String)` に 10 進文字列を渡す。浮動小数点を通さない。`last_updated_at` の丸めの向きは、プロファイルの定めに従う（未確認。実装 PR で `dynamodb.md` 5 章を再確認する）。

### 4.4 書き込み（D-5・D-6・D-7・W-8）

1 回の書き込みを 1 つの `TransactWriteItems` にする。

| アクション | 条件 |
|---|---|
| Put ジャーナル | `attribute_not_exists(aid)` |
| 新規作成（`seq_nr=1`）: Put ヘッド | `attribute_not_exists(aid)` |
| 更新（`seq_nr>1`）: Update ヘッド | `seq_nr = :prev`（`:prev` は `event.seqNr()−1`）。`seq_nr` と `events` を上書き |
| スナップショットがあれば Put 現在スナップショット | 条件なし |
| 保持件数を設定していれば Put 履歴スナップショット | 条件なし |

最大 4 アクション。順序は、journal・head・current-snapshot・history-snapshot とする。

**失敗の分類。**

| 失敗 | 分類 | 規則 |
|---|---|---|
| 新規作成でヘッドの条件が成立しない | 楽観ロック | W-3 |
| 更新でヘッドの条件が成立しない | 旧ヘッドの `seq_nr` と比べる。`event.seqNr() <= 旧ヘッド` は楽観ロック。`event.seqNr() >= 旧ヘッド + 2` は飛び番の契約違反（W-8） | W-8・D-5 |
| ジャーナルの条件が成立しない | 楽観ロック | W-7 |
| `TransactionConflict` | 楽観ロック | D-6 |
| スロットリング・通信失敗・その他 | 保存先 | 4 章 |

**D-5。** ヘッドの Put・Update に `ReturnValuesOnConditionCheckFailure=ALL_OLD` を付ける。`TransactionCanceledException.cancellationReasons()` の `item()` の旧 `seq_nr` と比べる。追加の読み取りをしない。旧項目が返らなければ、ヘッド 0 とみなす。

**D-7（項目サイズ）。** 書き込みの前に、各項目のサイズを見積もり、上限 409600 バイトを超えれば契約違反（分類のみ）。payload はジャーナルとヘッドの両方に載るため、両方を見積もる。属性名・値・コンテナの分を含める。見積もりは [AWS のサイズ定義](https://docs.aws.amazon.com/amazondynamodb/latest/developerguide/CapacityUnitCalculations.html) に従う。実装 PR で、境界の試験（409600 バイトちょうどと +1）を書く。

**送信前の検査と送信後の分類。** 入力だけで判定できる契約違反（必須要素の欠落・T-9・T-11〜T-13・W-6・W-9・D-7）は、送信の前に検査する。検査に失敗した書き込みは、保存先へ何も送らない。W-8 の飛び番はヘッドの値に依存するため、送信前には判定しない。トランザクションを送り、失敗時の旧ヘッド（D-5）で分類する。追加の読み取りはしない。H-1 が求めるのは、確定しなかった追記がどこにも現れないことである。

### 4.5 読み取り（DY-9・DY-10・DY-11）

- `getLatestSnapshotById`: ヘッドと現在のスナップショット（`skey=0`）を 1 回の `BatchGetItem`（強整合）で読む。`UnprocessedKeys` は、読み切るまで再要求する（DY-9）。ヘッドがなければ空（R-1）。あれば、スナップショット（なければなし）とヘッドの `seq_nr` の組を返す（DY-10・R-2・R-3）。
- 2 項目の読み取りは原子的ではない（R-8）。`TransactGetItems` は使わない（P-25）。この性質を Javadoc に書く。
- `getEventsByIdSinceSeqNr`: journal を `aid = :aid AND seq_nr >= :seq_nr`、`ConsistentRead=true` で `Query` する。昇順。`LastEvaluatedKey` が返る間は読み切る（DY-11・R-4・R-5）。同期は `queryPaginator`、非同期は継続の再帰で実装する案（現行も同じ構造）。
- payload・manifest の欠損など、読み取ったデータが期待の形でない場合は、`StorageException`（4 章の保存先の分類）。

### 4.6 スナップショットの保持（8 章・D-3・D-9・P-18・P-24・S-3）

保持件数 n を設定したときだけ行う。履歴を書いた書き込みの確定後にだけ行う（D-9）。

1. 疎な GSI を `aid = :aid`・`ScanIndexForward=false` で `Query` し、読み切る（KEYS_ONLY）。
2. 今書いた履歴を加える（GSI に見えていれば重ねない。結果整合のため）。降順の先頭 n 件を残し、それより古いものを対象にする（S-2）。
3. 削除方式（`DELETE`）: `BatchWriteItem` を 25 件ずつ（P-18）。`UnprocessedItems` は再送する。
4. TTL 方式（`TTL`）: 1 件ずつ `UpdateItem` する。`SET #ttl = :expires REMOVE active_history_seq_nr`、条件は `attribute_exists(active_history_seq_nr)`。`#ttl` は `ExpressionAttributeNames` で渡す。`:expires` は「印付けの時刻（エポック秒）+ 猶予秒」。後の更新が条件失敗した場合は、印付け済みとして読み飛ばす。
5. 印付き履歴は件数に数えない。期限を先送りしない（S-3）。件数を数えてから超過分を選ぶ方式は使わない（P-24）。
6. 失敗は書き込みの結果を変えない。ログと `RetentionFailureListener` で通知する（S-4）。

**実行の時機。** 書き込みの確定後、呼び出しスレッドで保持処理を完了してから、書き込みの結果を返す案を推奨する（決定的に試験できる。書き込みの遅延は増える）。9 章に含める。

### 4.7 SDK の差し込みの仕組みを置く場所（IP 6）

ライブラリ本体は、利用者が渡した `DynamoDbClient`・`DynamoDbAsyncClient` を使うだけである。差し込みの仕組みは、**試験側が作るクライアント**に置く。ライブラリの公開 API にもプロダクションコードにも差し込みの仕組みを入れない。

| 目的 | 置き場所 |
|---|---|
| 要求の観測（`observe.requests`）・応答の差し替え（`replace-response`） | `ClientOverrideConfiguration.addExecutionInterceptor(...)` で登録する `ExecutionInterceptor`（`beforeExecution`・`modifyRequest`・`modifyResponse`・`afterExecution`）。公式の仕組み: [ExecutionInterceptor](https://docs.aws.amazon.com/java/api/latest/software/amazon/awssdk/core/interceptor/ExecutionInterceptor.html) |
| 対象を呼ばずに失敗や応答を返す（`replace-request`） | SDK の HTTP クライアント層（`SdkHttpClient`・`SdkAsyncHttpClient`）を包む試験用クラス。送信を省略し、DynamoDB の JSON 形式のエラー応答・応答本体を返す。SDK が例外（`code`・`cancellationReasons`）を組み立てる |
| SDK の自動再試行の無効化 | クライアント生成時の再試行設定を、試験用クライアントで無効にする。API の名前は SDK 2.55.11 で確認する（**未確認**。実装 PR で確認する） |

`ExecutionInterceptor` は、要求を観測して変更できるが、送信を省略できない。このため `replace-request` には HTTP クライアント層を使う。この案は、固定した版での動作を実装 PR で確かめる（確認済みとは書かない）。保持処理の要求だけを失敗させるため、差し込みは、操作名（`Query` の GSI 名・`BatchWriteItem`・`UpdateItem`）と、段階の指定で判別する。

## 5. 適合テストデータの実行器

### 5.1 置き場所

`src/test/java/com/github/j5ik2o/event/store/adapter/java/conformance/` に作る。`conformance/` の JSON データを、リポジトリから読み取り専用で読む。JUnit 5 の動的テスト（`@TestFactory`）で、ケースごとに 1 つのテストにする。メモリと DynamoDB のそれぞれ（`backends`）で実行する。

### 5.2 データの読み方

| 項目 | 方針 |
|---|---|
| JSON | Jackson の `ObjectMapper`。`DeserializationFeature.USE_BIG_INTEGER_FOR_INTS`・`USE_BIG_DECIMAL_FOR_FLOATS` を有効にし、`JsonParser.Feature.STRICT_DUPLICATE_DETECTION` を有効にする。重複キー・NaN・Infinity は読み込みの失敗。文字列値・属性名は書き換えない |
| 版 | 最上位の `format`・`version` を検査する |
| 任意精度整数 | `seq_nr` の −1・2^53 などは `BigInteger` で読む。`long` へ直接デコードしない。`long` に収まらない値の扱いは 10 章 |
| `epoch_nanoseconds` | 10 進文字列を `BigInteger` で計算する。浮動小数点を通さない |
| 封筒の `occurred_at` | 9 桁小数秒の UTC ISO 8601 を `Instant` に変換 |
| 時刻の精度 | `precision_policy = native-time-type` の成功ケースは、入力を `Instant` へ変換した値を期待値にする。`expect.value` は丸め前の参照値。実行器は、変換した値と実際の値を報告する。`representation.time_precision` が `milliseconds` のケースは対象外。`nanoseconds` のケースと印のないケースは実行する。`representation.signed_seq_nr = true` は `long` で表せるので実行する |
| 値の表の操作 | `buildAid`・`validateOccurredAt`・`validateSeqNr`・`fnv1a64` は、公開 API の同名関数を要求せず、実行器が対応付ける |
| `buildAid` | `user_string` を返す試験用 ID を用意し、`AggregateId.of` が型名と値から組み立てることを確かめる |
| `validateSeqNr` | `context=value` は、中核の `Validation.checkSeqNr(seqNr, Context.VALUE)` を呼ぶ（T-9 の一般値域。0 も有効）。`context=event` は、`EventEnvelope.builder()...build()` を通す（同じ検査を `Context.EVENT` で呼び、0 を W-6 の契約違反にする）。適合の実行器のうちこの呼び出しを行うクラスは、`src/test/java` の `core` と同じパッケージ名の下に置き、`Validation` を呼ぶ。実行器で同じ数式を計算しない |
| `validateOccurredAt` | 型名 `ConformanceTime`・値がケース ID の集約。1 番から `event_seq_nr−1` 番までを時刻 `1970-01-01T00:00:00.123000000Z` で `persistEvent` し、次に入力時刻の `event_seq_nr` 番を書く（payload は空オブジェクト、manifest は空文字列）。成功ケースは 1 番を読み戻して比較する。範囲外ケースは 7 番で契約違反を比較する |
| `fnv1a64` | java のプロファイルはハッシュを保存キーに使わない（DY-16）。共有ハッシュ実装がないため、対象外として理由を報告する |
| payload の比較 | 既定の JSON シリアライザで直列化・復元した JSON 値を比較する。キー順・空白は無視。配列の順序・null・真偽値・文字列・数値は保つ。真偽値と数値を同一視しない。Unicode 正規化はしない |
| generators | `target`（ケースを根とする JSON Pointer）・`character`・`byte_length`。`~0`・`~1` を復号する。Schema 検査は展開前、操作は展開後の値で行う。参照実装は `tools/conformance/data.py` の `materialize` |
| サイズ | 400KB は 409600 バイト、1MB は 1048576 バイト |

### 5.3 場面の実行手順

1. `backends` に実行する保存先があるか確かめる。なければ対象外。`requires=["ttl"]` はメモリでは対象外（MEM-12）。
2. 各場面は、独立した保存先で実行する。メモリは新しい `MemoryStorage`。DynamoDB は、場面ごとに実行器が 3 テーブルと GSI の名前を割り当てて作る（例: `{ケース ID から作った安全な文字列}-journal`）。3 テーブルは同じリージョン。他の場面の項目を使い回さない。
3. `store` の設定（`retention_count`・`retention_mode`・`ttl_grace_seconds`）を、`EventStoreConfig`・`RetentionPolicy` へ対応付ける。
4. `seed.items` があれば、ストア生成の前に、テスト用の権限で直接入れる。
5. `initialization` があれば、生成結果を検査する。生成失敗のケースは操作列がない。生成前の障害は先に登録する。
6. `generators` を展開し、`fixtures.events`・`fixtures.snapshots` を、各操作の直前に封筒として構築する。無効な入力による封筒構築中の規則違反も、その操作の失敗として捕捉する。実行器の事前検査でライブラリの検査を代替しない。
7. `steps` を配列の順に、並行実行せずに実行する。
8. 各操作の `expect` と `observe` を検査する。保持の失敗や遅延がある場面は、保持・検査フックの完了後に観測する。フックが書き込みの成功・失敗を変えてはならない。`expect` は success・none・snapshot（`head_seq_nr` と封筒の組）・events（順序込み）・error。
9. 設定照合の `retry_limit` は、再要求の回数の上限（初回は数えない）。実装の上限が待ち時間なら、同じ未処理応答の列で上限到達を再現する。指数バックオフは、時計フックで実時間を短縮してよい。

### 5.4 フックの置き場所

| フック | 置き場所 |
|---|---|
| 保持の決定的実行 | 4.6 のとおり、書き込みの完了までに保持を終える設計にする。実行器は書き込みの戻りの後に観測する。メモリは同じ。非同期でも、`CompletableFuture` の完了前に保持を終える |
| 内部履歴（`observe.history`） | DynamoDB: 実行器が試験用の権限で snapshot テーブルとGSI を直接読む。メモリ: `memory` のパッケージ内部の検査用の型。active（印のない履歴）と marked（`seq_nr` と期限の組）を完全一致で比べる。absent は存在してはならない履歴。現在のスナップショットと設定項目は数えない。その集約の履歴だけを見る |
| 失敗通知（`observe.notifications`） | `RetentionFailureListener` の試験用実装。分類 `retention-failure`。空配列は通知がないこと。同じ最終失敗が複数あれば 1 つに正規化してよい |
| SDK 要求（`observe.requests`） | 4.7 の `ExecutionInterceptor` が記録した要求を、式と属性名・値の束縛を解析した構造にして比べる（空白・節の順序・AND の順序は比べない）。対象: `update.set`・`update.remove`・`condition`、`key_condition.all`、TTL の `#ttl` と `expression_attribute_names`、`expires`（エポック秒）、`initial_batch_sizes`（再送を除く削除バッチ件数）、`no_requests_in_phases`・`request_count`・`minimum_request_count`。`requests` の要素は実際の別々の要求に配列順で対応付ける。ページ送り・未処理キーの再要求・削除バッチの分割は、段階の要求列全体で検査する |
| 属性（`observe.items`・`seed.items`） | 実行器が `GetItem` で読む。`table` は journal / snapshot / head の設定済みテーブル名。`attributes` は S/N/B/L/M の完全一致。N は 10 進文字列を整数として比べる。L の中の M は `nested_attributes`。`binary_json` は B の復元結果を JSON で比べる。`bindings` の `generated-store-id` は最初の実際の `store_id` を束縛し、3 項目で同じ値であることを検査する。属性の集合・型・リストの件数の検査は、除外前の実際の項目全体で行う |
| 時計 | `DynamoDbEventStore` が受け取る `java.time.Clock`（2.8）。`clock.epoch_seconds` と操作の `clock_epoch_seconds` を固定時計にする。v1 は 2100 年の時計 |
| 配置（`dynamodb/layout.json`） | 実際の 3 テーブルを `DescribeTable` と `DescribeTimeToLive` で照合する。テーブル名・GSI 名は設定値へ束縛する |

### 5.5 障害の差し込み（13 段階）

> 指示書は「13 段階」と書くが、`conformance/schema/common.schema.json` の `phase` の列挙は 12 種類である。この文書は、列挙された 12 種類のすべてに差し込み場所を示す。13 番目は創作しない。10 章に書く。

`faults` の `operation` は、0 がストア生成、1 以上が 1 始まりの操作番号。登録した障害が発火しなければ、場面は失敗にする。差し込めない場合は「未検証」と報告する（成功に集計しない）。

| phase | DynamoDB での差し込み | メモリでの差し込み |
|---|---|---|
| `serialize-event` | イベント payload の `PayloadSerializer` を包む試験用実装で失敗させる | 同じ（ロックの前の準備段階で失敗する） |
| `serialize-snapshot` | 集約状態の `PayloadSerializer` を包む | 同じ |
| `deserialize-event` | 読み取り時の `PayloadSerializer.deserialize` を包む | 同じ |
| `deserialize-snapshot` | 同上（集約状態） | 同じ |
| `commit` | `TransactWriteItems` の HTTP 層で `replace-request`（何も確定しない）。`TransactionCanceledException` は `replace-request` | 確定の直前（公開の前）に、パッケージ内部のフックで失敗させる。ヘッド・ジャーナル・スナップショットに変更を残さない |
| `read-events` | journal の `Query` の応答を `replace-request` / `replace-response` | ロックの中の記録の取得で失敗させる（パッケージ内部のフック） |
| `read-snapshot` | `BatchGetItem` の応答を差し替える。`read-interleave` はここ（5.6） | ロックの中のヘッドと封筒の取得で失敗させる |
| `retention-query` | 履歴 GSI の `Query` に `replace-request` | 論理履歴の候補の取得で失敗させる（MEM-11 のフック） |
| `retention-delete` | `BatchWriteItem` に `replace-request`。`unprocessed_first_n` も `replace-request` で表す | 確定後の論理履歴の削除で失敗させる |
| `retention-mark` | TTL の `UpdateItem` に `replace-request` | **差し込めない**（MEM-12 で TTL 方式がない）。メモリで TTL 方式の要求が `ConfigurationException` になることを、代わりの確かめ方にする。報告は「対象外」とし、成功にも未検証にも数えない理由を記録する |
| `configuration-read` | 設定項目の `BatchGetItem`。`responses`・`unprocessed_keys` の応答計画に従う | **差し込めない**（設定は保存先が不変で持つ。DynamoDB 固有の場面のため `backends` にメモリがない）。対象外 |
| `configuration-create` | 設定項目の `TransactWriteItems`。`install_items` がある生成競合は、別の実行器が先に書いた項目を確定させる | **差し込めない**（永続設定の作成競合がない）。対象外 |

`kind` ごとの扱い:

- `serialization-error`: シリアライザの対応段階を失敗させる。
- `storage-error`: 保存先・保持フックの最終失敗。`details.scope=final-retention-failure` は、候補選択後・削除前に保持全体を失敗させる。
- `sdk-error`: SDK の自動再試行を無効にし、`details.code` と `cancellation_reasons` から SDK の例外を組み立てる（HTTP 層で JSON エラー応答を返す）。
- `sdk-response`: 応答計画（`responses`・`unprocessed_keys`・`history_pages`・`omit_just_written_history`・`unprocessed_first_n`）を、`ExecutionInterceptor` と HTTP 層で実装する。`history_pages` は、GSI 応答の履歴 `seq_nr` のページ列をそのまま返し、今書いた履歴を自動追加しない。ページごとに `LastEvaluatedKey` と `ExclusiveStartKey` を対応付ける。`unprocessed_first_n` は、`BatchWriteItem` の先頭 n 件を `UnprocessedItems` にし、残りは実処理する。削除・TTL 更新・書き込みの物理結果は、実際に反映して検査する。
- `injection`: `replace-request` は対象を呼ばず、何も確定しない（v1 の書き込み失敗と保持失敗はすべてこれ）。`replace-response` は、送信後に応答を差し替える（副作用は残る）。
- `repeat`: `{"mode":"count","count":1}` か `{"mode":"until-operation-finishes"}`。

`cancellation_reasons`: トランザクションの各項目に 1 要素。書き込みは journal・head・current-snapshot・history-snapshot の順（存在するアクションだけ）。設定作成は `configuration:journal`・`configuration:snapshot`・`configuration:head`。失敗していない項目の `code` は文字列 `None`。実行器は、対象名を実際の要求内のアクションに照合して並べ直す。この順をライブラリの要求順として強制しない。ヘッドの `ConditionalCheckFailed` は `old_head_seq_nr` を持つ（`null` は旧項目が返らない）。D-5 の分類はこの旧項目を使う。ジャーナルの条件不成立は W-7、`TransactionConflict` は D-6 の楽観ロック、ヘッド以外のスロットリングは保存先エラー。

### 5.6 `read-interleave`（DY-9 の非原子的応答）

送信の直前に旧ヘッドを捕捉する。`interleaved_operation` の追記を確定する。元の `BatchGetItem` を送り、応答の中のヘッドを旧ヘッドへ差し替える。実時間の競争は使わない。実装は 4.7 の `ExecutionInterceptor`（`modifyResponse`）。メモリでは、ヘッドとスナップショットを同じロックで読む（MEM-8）ため、この場面は DynamoDB だけ。メモリでの代わりの確かめ方は、同じ集約へ追記する別スレッドと読み取りが重なっても、組が常に整合することを確かめる並行試験（3 章の MEM-4・MEM-8）。

### 5.7 報告と CI

- 報告の形（`build/reports/conformance/report.json` と、人が読む要約を標準出力・CI のサマリーに出す）:
  - データの版と、`manifest.json` の照合結果。
  - 言語（java）・実装版・保存先（memory / dynamodb-local）。
  - ケース ID と規則番号ごとの結果。結果は `passed`・`failed`・`not-applicable`（対象外）・`unverified`（未検証）の 4 値。1 つのケースが複数の規則を持てば、すべての規則へ対応付ける。
  - 失敗した操作の番号と、期待値・実際の値。
  - 表現不能・選択・任意能力・削除済み規則・呼び出し側の推奨を理由つきで記録する。
- 途中で期待と異なれば、成功と報告しない（IP 5）。条件を満たさないケース・必須ケースを飛ばした結果・障害を差し込めなかった結果を成功に集計しない。
- 配布の検証: 現行の `ci.yml` は、`python3 tools/conformance/manifest.py verify` と、`manifest.json` の SHA-256（v1.0.0 は `61c26614dbbfba88eebce72cc1d2b0220218839e74dcfb64c19268f7ee2302ce`）を照合している。これを維持する。manifest を作り直して差分を隠さない。`conformance/` を同一内容で写し、`.gitattributes` で改行変換を止める設定が要る場合は、実行器の PR で確認する。
- CI での実行: 既存の `ci.yml` の JDK 11 / 25 の試験に、適合の実行を加える。DynamoDB Local は、試験側（Testcontainers）か CI のサービスコンテナで起動する（6 章）。報告のファイルをアーティファクトとして保存する。

## 6. 試験環境

### 6.1 DynamoDB Local

- DynamoDB Local 3.3.1 に統一する。イメージは digest で固定する。
  - `amazon/dynamodb-local@sha256:ff89bd48ff32cd8d9be5fee8873b65b8854dc408f1afe881be6eb00247bc0dab`
  - ハブの `tools/spikes/dynamodb-emulators/` の記録は、同じ digest を `latest` と `3.3.1` のタグに照合している。
- 起動の例（コンテナのポートは 8000）:

```java
GenericContainer<?> dynamo =
    new GenericContainer<>(
            DockerImageName.parse(
                "amazon/dynamodb-local@sha256:ff89bd48ff32cd8d9be5fee8873b65b8854dc408f1afe881be6eb00247bc0dab"))
        .withExposedPorts(8000)
        .withCommand("-jar", "DynamoDBLocal.jar", "-inMemory");
```

- クライアントには、endpoint・リージョン・ダミーの資格情報を明示する。`DynamoDbClient`・`DynamoDbAsyncClient` に加え、Streams のクライアントにも endpoint を明示する。Streams の ARN のリージョンは `ddblocal` になるため、ARN からリージョンを推測しない。
- 3 テーブルは同じリージョンに作る。場面ごとにテーブル名を変える（5.3）。`-sharedDb` の要否は、実装 PR で、リージョン・資格情報の組ごとの分離と合わせて確認する（**未確認**）。
- 起動の時間を減らすため、コンテナは試験の実行全体で 1 つにし、場面ごとにテーブルだけを作る。

### 6.2 今の試験からの移行

- 現行の試験は LocalStack を使う（`build.gradle.kts` の `org.testcontainers:localstack`）。実装計画 4.1 のとおり、段階 2 の中で DynamoDB Local へ移す。
- 現行の試験は新 API に合わないため、ほぼ一から書く（IP 4.2）。旧 API の試験は、7 章の PR で旧 API を消すときに消す。
- LocalStack の依存と、使わなくなる依存は、最後の PR で外す。

### 6.3 Testcontainers の版の混在

現行は `org.testcontainers:testcontainers:2.0.5` に、`junit-jupiter:1.21.4` と `localstack:1.21.4` が混在している。DynamoDB Local は `GenericContainer` で起動できるため、LocalStack モジュールは不要になる。`junit-jupiter` モジュールも、手動のライフサイクル管理にすれば不要にできる。残す場合は、`testcontainers` 本体と同じ版に揃える。2.x 系の成果物名の変更の有無は **未確認**。実装 PR で Maven Central 上の成果物を確認する。

## 7. main への入れ方

実装計画 3 章に従う。各 PR は main に squash マージする。main の Snapshot は `2.0.0-SNAPSHOT` で公開する。worker は PR の作成までを行い、マージはコーディネーターが行う（IP 9）。1 つの PR は 1 つの規則群に対応する。

### 7.1 PR の列

| 順 | PR の範囲 | 対応する規則群 |
|---|---|---|
| 1 | **次のメジャーの下準備**: `version` を `2.0.0-SNAPSHOT` にする。Testcontainers の版を揃える。この文書の内容に合わせて CI の準備をする。コードの振る舞いは変えない | IP-D1・IP 4.1 |
| 2 | **適合テストデータの実行器**: データの読み込み・場面の実行・フック・報告。新 API がまだないため、実行器は旧実装に対して実行せず、報告は「未実装の規則を適合済みと報告しない」 | 5 章 |
| 3 | **中核**: 値型・封筒・検査・`PayloadSerializer`・エラー分類・4 つの操作のインターフェイス。新しいパッケージに置く | T・E・H・W 群 |
| 4 | **メモリ**: `MemoryStorage`・`MemoryEventStore`（同期・非同期）。共有・並行・参照隔離の試験 | MEM-1〜MEM-13、共通の W・R・S 群 |
| 5 | **DynamoDB の設定と配置**: 3 テーブル・設定項目・`BatchGetItem` の再要求 | DY-8・DY-16〜DY-19・D-1〜D-4 |
| 6 | **DynamoDB の書き込み・読み取り**: 4.4・4.5 | D-5〜D-7・DY-9〜DY-11・W・R 群 |
| 7 | **保持と旧 API の削除**: 4.6。新 API の利用側（現行の試験・サンプル）を移行し、旧 API を削除する | D-9・S 群・P-18・P-24 |
| 8 | **利用者の文書と移行の案内**: README・`DATABASE_SCHEMA`・`MIGRATION_GUIDE` | IP 5 の受け入れ条件 5・6、IP-D8 |

変更フィードの補助を含める場合（9 章）は、PR 7 と 8 の間に追加する。

### 7.2 旧 API との共存と削除の時点

- PR 3〜6 の間は、旧 API（`com.github.j5ik2o.event.store.adapter.java` 直下と `internal`）と新 API（`core`・`memory`・`dynamodb`）が、同じ main に共存する。パッケージが違うため衝突しない。
- 旧 API に新しい機能は足さない。旧 API を新 API の内部から呼ばない。
- 旧 API の削除は PR 7。旧 API の利用側（現行の試験）は、同じ PR で新 API に移す。永続的な互換層は作らない。旧データを自動で読む経路（fallback）も作らない（IP-D8）。
- 旧 API の試験は、PR 3〜6 の間は残し、main の CI を通す。

### 7.3 各 PR で main の CI を通し続ける方法

- 各 PR は、その PR までで実装した規則だけを適合として扱う。実行器は、未実装の規則を成功にしない（5.7）。PR 2 の時点では、適合の実行は「未実装・未検証」と報告し、CI の失敗にしない。実装済みの範囲だけを必須にする。必須にする範囲は、PR ごとに実行器の設定（対象のケース ID の一覧）で拡げる。
- 旧 API の試験が通っている間は、`./gradlew test` が成功する。PR 7 で旧 API と旧試験を一緒に消す。
- 現行の `ci.yml` の manifest の照合は、すべての PR で維持する。
- 版上げは、既存の `bump-version.yml`（手動起動）を使う。自動で上げない。

## 8. 移行の案内

### 8.1 1.x の利用者の API の移行

`MIGRATION_GUIDE`（既存の文書）を、PR 8 で更新する。書く内容:

- 2.10 の対応表（何を消し、何に置き換えたか）。
- `Aggregate`・`Event` の実装を、ドメイン型から外す。イベントは `EventEnvelope`、スナップショットは `SnapshotEnvelope` に包む。
- `version` による楽観ロックが、ヘッドの `seq_nr` に変わる。`persistEvent(event, version)` の `version` 引数がなくなる。照合の期待値は `event.seqNr()` から決まる。
- `getLatestSnapshotById` の戻り値が、スナップショットの封筒とヘッド番号の組になる。復元の起点は、封筒があれば `snapshot.seqNr() + 1`、封筒がなければ 1 である（R-4）。`headSeqNr` を起点にしない。`getEventsByIdSinceSeqNr` にこの起点を渡す。2 項目の読み取りは原子的でないため（R-8）、スナップショットがヘッドより新しい場合がある。この場合は取得済みの番号を再適用しない処理が要る。
- シリアライザの置き換え。`Class<T>` を渡す形から、生成時に束縛する形へ。
- エラー分類の変更。契約違反と設定が新しい。例外は `category()` で区別する。

### 8.2 旧データの扱い（IP-D8）

- 旧配置（`journal`・`snapshot` の 2 テーブル。シャード付きの PK）から、新配置（3 テーブルと設定項目）への自動移行は、**提供しない**。移行ツールも、旧配置の自動読み込みも作らない（IP-D8: java は手順書だけ）。
- 手順書に書くこと: 新しい 3 テーブルを作る。旧イベントを、新 API で `seq_nr` 順に書き直す（aid が変わる場合は、利用者側の対応が要る）。旧と新を同じテーブルに混在させない。旧テーブルは、書き直しの確認後に利用者が削除する。
- 旧データのイベントのシリアライズ形式（現行は、ドメインイベント全体を JSON にし、メタデータも含む）は、payload だけの直列化と互換でない。手順書は、旧 JSON から payload を取り出す変換が利用者の作業であることを書く。手順書の詳細は、PR 8 で作る。

## 9. 判断が要る点

**この章の項目は、どれも決めていない。オーナーが決める。「推奨」は設計者の意見である。**

### 9.1 同期と非同期の API の分け方

| 選択肢 | 内容 |
|---|---|
| A | 同期用 `EventStore<P, A>` と非同期用 `AsyncEventStore<P, A>` を、別のインターフェイスとして公開する（2.6 の案） |
| B | 非同期を中心にし、同期は非同期へのアダプタ（`.join()` を内部で呼ぶ）として提供する |
| C | 1 つのインターフェイスに同期と非同期の両方のメソッドを持たせる |

| 選択肢 | 利点 | 欠点 |
|---|---|---|
| A | 現行の構造（`EventStore` / `EventStoreAsync`）に近く、1.x の利用者が移りやすい。同期の利用者が `CompletableFuture` を意識しない。DynamoDB のクライアントも同期と非同期が別で、対応が素直。kotlin・scala は `AsyncEventStore` だけを包める | 同じ規則を 2 つの実装で守る。実装・試験が二重になる。共通の内部ロジックを分ける設計が要る |
| B | 実装が 1 つ。規則の検査が 1 か所 | 同期のアダプタがスレッドをブロックする。同期の DynamoDB クライアントを使えない。例外の包みの扱いが増える |
| C | 型が 1 つ | 実装者に両方を求める。利用者が使わない側もある。メモリ・DynamoDB の差が出る |

推奨: A。理由は、移行の負担が小さく、DynamoDB の SDK の構造と合い、検査・分類の中核を共有部品として切り出せるため。

### 9.2 シリアライザの API

| 選択肢 | 内容 |
|---|---|
| A | 生成時に型を束縛した `PayloadSerializer<T>`（`serialize(T)`・`deserialize(byte[])`）を設定に渡す（2.4 の案） |
| B | 操作ごとに `Class<T>`（または `TypeReference<T>`）を渡す現行の形を維持する |
| C | `deserialize(String manifest, byte[] bytes)` のように、manifest を渡す形にする |

| 選択肢 | 利点 | 欠点 |
|---|---|---|
| A | 操作のシグネチャが単純。型が生成時に決まる。T-6・T-7 に合う。ジェネリクスの消去の問題が出ない | 1 つのストアが 1 つのイベント型だけを扱う。多相のイベント階層は、利用者のシリアライザの中で扱う |
| B | 現行に近い | 型の指定を操作ごとに繰り返す。誤った型を渡せる。`List<E>` のような総称型を渡せない。kotlin・scala の包みで型の消去の問題が出る |
| C | manifest で型を選べ、多相に強い | T-4（ライブラリは manifest を解釈しない）との関係が仕様から決められない（10 章）。manifest をシリアライザに渡すこと自体を仕様が許すかが不明 |

推奨: A。理由は、型の取り違えがコンパイル時に防げ、操作の API が小さくなるため。C は、10 章の疑問が解けてから再検討する。

### 9.3 変更フィードの扱い

head テーブルの Streams のレコードからヘッド遷移を組み立てる関数と、再同期（DY-15）の補助を、最初のメジャーに含めるか。

| 選択肢 | 内容 |
|---|---|
| A | 含めない。後続の版に送る。head テーブルの Streams の設定（DY-3・DY-12）は、書き込み側の形として最初から満たす |
| B | ヘッド遷移の組み立て関数（DY-13）だけ含める |
| C | 組み立て関数と、DY-15 の再同期の補助の両方を含める |

| 選択肢 | 利点 | 欠点 |
|---|---|---|
| A | 範囲が小さく、IP-D3 の「中核・メモリ・DynamoDB」に収まる。2.0.0 の遅れが小さい | 変更フィードを使う利用者は、自前で組み立てる必要がある |
| B | 利用者の組み立ての誤りを減らせる。`aid=__config__` の読み飛ばしを共通化できる | Streams のクライアント・レコード型の公開 API が増える。試験が増える |
| C | 変更フィードの利用者に完全な補助を渡せる | `Scan` を使うため DY-19 の例外が増える。範囲が大きい。DY-15 の補助の形を仕様が定めていない可能性がある（10 章） |

推奨: A。理由は、IP-D3 の範囲を守り、公開 API の約束を最小にできるため。購読側の需要が確認できれば、後続の版で足せる。

### 9.4 例外を非検査にするか（設計の途中で見つけた点）

| 選択肢 | 内容 |
|---|---|
| A | 5 分類をすべて非検査例外（`RuntimeException`）にする（2.7 の案） |
| B | 現行のように、検査例外と非検査例外のペアを持つ |

| 選択肢 | 利点 | 欠点 |
|---|---|---|
| A | 型が 5 つで済む。非同期でも同じ型を使える。呼び出しが簡単 | コンパイラが処理を強制しない。1.x の `throws` を書いた利用者のコードは変わる |
| B | 現行に近い | 型が倍になる。ラムダ・`CompletableFuture` と相性が悪い。5 分類（E-1）の判別が複雑になる |

推奨: A。理由は、型の数が少なく、非同期との整合が取れるため。

### 9.5 保持処理を呼び出しの中で完了するか（設計の途中で見つけた点）

| 選択肢 | 内容 |
|---|---|
| A | 書き込みの確定後、同じ呼び出しの中で保持処理を完了してから結果を返す（4.6・3 章の案） |
| B | 別のスレッド・エグゼキュータで保持処理を行い、書き込みは確定後すぐに結果を返す |

| 選択肢 | 利点 | 欠点 |
|---|---|---|
| A | 実行が決定的で、適合の試験が書きやすい。MEM-7・MEM-10（確定後に同じ排他制御の中で行う）に素直に合う | 書き込みの遅延が増える（DynamoDB は保持の `Query`・削除の分） |
| B | 書き込みが速い | 実行器にフックが要る。メモリの MEM-10 は排他制御の中で行う規定のため、メモリでは使えない。終了の管理（シャットダウン）が要る |

推奨: A。理由は、決定的に試験でき、メモリの規則と同じ振る舞いになるため。

### 9.6 同じ `MemoryStorage` に違う設定のストアを作った場合の扱い（設計の途中で見つけた点）

前提（仕様で決まっている）: 設定は保存先が不変で持ち、共有するインスタンスはその設定を使う（MEM-2・MEM-3、MEM-D11）。インスタンスごとに別の設定を持たせる案は採らない。

仕様が定めていないのは、共有する保存先に、保存先の設定と異なる設定でストアを生成しようとした場合の扱いである（10 章 Q5）。

| 選択肢 | 内容 |
|---|---|
| A | 生成を `ConfigurationException` で拒否する |
| B | 設定の引数を受け取らない生成 API（既存の設定を使う）を用意し、不一致が起こらない形にする |

| 選択肢 | 利点 | 欠点 |
|---|---|---|
| A | 利用者が要求した設定と保存先の設定の不一致を検出できる。設定エラーの分類（4 章）に合う | 保存先の設定を知らない呼び出し側は、生成に失敗する |
| B | 不一致が起こらない | 共有側が自分の設定を指定できない。設定の確認手段が別に要る |

推奨: A。理由は、保存先の設定を変えずに、不一致を設定エラーとして検出できるため。ただし、不一致の扱いは仕様が定めていないため、オーナーが決める。

## 10. 未解決の疑問

仕様を勝手に解釈して埋めない。次の点は、コーディネーターのレビューで確かめる。

| 番号 | 疑問 | 根拠・状況 |
|---|---|---|
| Q1 | 障害の差し込みは「13 段階」と指示書にあるが、スキーマの `phase` は 12 種類 | `conformance/schema/common.schema.json` の `phase` の列挙（`serialize-event` から `configuration-create` まで 12 個）。指示書の付録も 12 個の名前を挙げる。13 番目は存在しないか、データの版の違いか。5.5 は 12 種類に差し込み場所を示した |
| Q2 | `conformance/coverage.json` の「メモリのS-3」「R-8」は、メモリのプロファイルを「未合意」と記録している。現在のプロファイルは 2026-10-05 に合意済み | 確認した箇所: `coverage.json` の該当 2 項目。データ側の記録が古い。データは変更しない。メモリの TTL は MEM-12 で提供しない、R-8 は MEM-8 で原子的に読む。データ側の更新が要るかをコーディネーターに確認したい |
| Q3 | `AggregateId` の型名・値が空文字列のとき | T-11・T-12 は、`-` の有無とバイト数だけを定める。空を許すかは定めがない |
| Q4 | 必須の設定（シリアライザ・テーブル名など）が欠けたときの分類 | S-1・MEM-3 は不正な値を設定エラーとするが、欠落は明記されていない。2.8 は設定エラーとしたが、仕様に根拠がない |
| Q5 | 共有する `MemoryStorage` に、保存先の設定と異なる設定でストアを生成した場合の扱い | MEM-3・MEM-D11 は、保存先が不変の設定を持ち共有インスタンスがそれを使うことを定める。不一致時に拒否するか、設定を渡さない生成にするかは定めがない（9.6） |
| Q6 | `long` に収まらない `seq_nr`（`BigInteger` で読んだ値）の扱い | 適合データのケースが、`long` に収まらない値を持つかは未確認。持つ場合、Java は構築できないため「表現不能」として報告するのか、契約違反として検査するのか。CR の「表現不能」の定義を確認したい |
| Q7 | `ExecutionInterceptor` の分類が、SDK の再試行・暗号化など他の層と順序が変わる場合の、`observe.requests` の対応付け | 固定した SDK 版での動作は、実装 PR で確認する（未確認） |
| Q8 | E-2 の「SDK の生のエラー文を含めない」は、例外の `getCause()` に SDK の例外を付けることを禁じるか | メッセージの規則としては、`getMessage()` だけを対象と読める。原因の例外を付けることが許されるか不明 |
| Q9 | 契約違反・保持失敗の通知のメッセージの「規則番号」の書式 | 例: `W-9` で足りるか。E-3 は規則番号を含めることだけを定める |
| Q10 | `manifest` を `PayloadSerializer` に渡してよいか（9.2 の選択肢 C） | T-4 は「ライブラリは manifest を解釈しない」と定める。シリアライザへ渡すだけなら解釈に当たらないという読みが可能だが、仕様に明記がない |
| Q11 | `last_updated_at`（ミリ秒）の丸めの向き | `dynamodb.md` 5 章の定めを実装 PR で再確認する（未確認） |
| Q12 | `fnv1a64` を java が「対象外」と報告してよいか | DY-16 は、ハッシュを保存キーに使わないとする。CR は、ハッシュを使うプロファイルの共有ハッシュ実装へ対応付けると読める。java はどちらにも当たらないため、対象外とした |
| Q13 | `Instant` の最小・最大の周辺で、`occurred_at` を `DynamoDB` の N 属性の有効桁（38 桁）に載せる際の問題 | エポックナノ秒の符号付き 64bit は 19 桁なので問題ないと考えるが、確認は実装 PR の試験で行う（未確認） |
