package com.github.j5ik2o.event.store.adapter.java.dynamodb;

import static org.junit.jupiter.api.Assertions.*;

import com.github.j5ik2o.event.store.adapter.java.core.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;

class DynamoDbSnapshotReadTest {
  private static final AggregateId ID = AggregateId.of("Stored", "value-with-hyphen");
  private static final long MAX = (1L << 53) - 1;
  private static final PayloadSerializer<byte[]> BYTES =
      new PayloadSerializer<byte[]>() {
        public byte[] serialize(byte[] value) {
          return value;
        }

        public byte[] deserialize(byte[] value) {
          return value;
        }
      };

  private static Map<String, AttributeValue> key(String table) {
    return table.equals("head")
        ? Map.of("aid", AttributeValue.fromS(ID.asString()))
        : Map.of("aid", AttributeValue.fromS(ID.asString()), "skey", AttributeValue.fromN("0"));
  }

  private static Map<String, AttributeValue> item(String table, long seq) {
    Map<String, AttributeValue> item = new LinkedHashMap<>(key(table));
    item.put("seq_nr", AttributeValue.fromN(Long.toString(seq)));
    if (table.equals("snapshot")) {
      item.put("manifest", AttributeValue.fromS("書式é"));
      item.put(
          "payload", AttributeValue.fromB(SdkBytes.fromByteArray(new byte[] {0, 1, (byte) 255})));
    }
    return item;
  }

  private static BatchGetItemResponse both(long head, long snapshot) {
    return BatchGetItemResponse.builder()
        .responses(
            Map.of(
                "head",
                List.of(item("head", head)),
                "snapshot",
                List.of(item("snapshot", snapshot))))
        .build();
  }

  private static BatchGetItemResponse partial(String pending, long head, long snapshot) {
    String found = pending.equals("head") ? "snapshot" : "head";
    return BatchGetItemResponse.builder()
        .responses(Map.of(found, List.of(item(found, found.equals("head") ? head : snapshot))))
        .unprocessedKeys(Map.of(pending, KeysAndAttributes.builder().keys(key(pending)).build()))
        .build();
  }

  @Test
  void publicFactoriesRestoreSnapshotsOnBothReturnedStores() {
    Stub s = new Stub(BYTES);
    s.reply = request -> both(2, 1);
    EventStore<byte[], byte[]> sync = DynamoDbEventStore.create(s.sync, s.tables, s.config);
    AsyncEventStore<byte[], byte[]> async =
        DynamoDbEventStore.createAsync(s.async, s.tables, s.config).join();
    for (Optional<SnapshotReadResult<byte[]>> result :
        List.of(sync.getLatestSnapshotById(ID), async.getLatestSnapshotById(ID).join())) {
      SnapshotReadResult<byte[]> actual = result.orElseThrow();
      assertEquals(2, actual.headSeqNr());
      assertEquals(1, actual.snapshot().orElseThrow().seqNr());
      assertEquals("書式é", actual.snapshot().orElseThrow().manifest());
      assertArrayEquals(new byte[] {0, 1, (byte) 255}, actual.snapshot().orElseThrow().aggregate());
    }
    assertEquals(2, s.requests.size());
    s.requests.forEach(DynamoDbSnapshotReadTest::assertInitial);
  }

  @Test
  void restoresIndependentNumbersAndActualBinaryUsingOnlySnapshotSerializer() {
    for (boolean async : List.of(false, true)) {
      AtomicInteger calls = new AtomicInteger();
      Stub s =
          new Stub(
              new PayloadSerializer<byte[]>() {
                public byte[] serialize(byte[] value) {
                  throw new AssertionError("read serialized");
                }

                public byte[] deserialize(byte[] value) {
                  calls.incrementAndGet();
                  assertArrayEquals(new byte[] {0, 1, (byte) 255}, value);
                  return new byte[] {7};
                }
              });
      for (long snapshot : new long[] {0, 1, MAX}) {
        s.reply = request -> both(2, snapshot);
        SnapshotReadResult<byte[]> result = s.read(async).orElseThrow();
        assertEquals(2, result.headSeqNr());
        assertEquals(snapshot, result.snapshot().orElseThrow().seqNr());
        assertEquals("書式é", result.snapshot().orElseThrow().manifest());
        assertArrayEquals(new byte[] {7}, result.snapshot().orElseThrow().aggregate());
      }
      assertEquals(3, calls.get());
      for (BatchGetItemRequest request : s.requests) assertInitial(request);
    }
  }

  @Test
  void missingHeadIsOuterEmptyAndMissingCurrentIsInnerEmptyAfterAllKeysFinish() {
    for (boolean async : List.of(false, true)) {
      Stub s = new Stub(BYTES);
      assertTrue(s.read(async).isEmpty());
      s.reply =
          request ->
              BatchGetItemResponse.builder()
                  .responses(Map.of("snapshot", List.of(item("snapshot", 1))))
                  .build();
      assertTrue(s.read(async).isEmpty());
      s.reply =
          request ->
              BatchGetItemResponse.builder()
                  .responses(Map.of("head", List.of(item("head", MAX))))
                  .build();
      SnapshotReadResult<byte[]> headOnly = s.read(async).orElseThrow();
      assertEquals(MAX, headOnly.headSeqNr());
      assertTrue(headOnly.snapshot().isEmpty());
      AtomicInteger count = new AtomicInteger();
      s.reply =
          request ->
              count.getAndIncrement() == 0
                  ? partial("head", 2, 1)
                  : BatchGetItemResponse.builder().build();
      assertTrue(s.read(async).isEmpty());
      count.set(0);
      s.reply =
          request ->
              count.getAndIncrement() == 0
                  ? partial("snapshot", 2, 1)
                  : BatchGetItemResponse.builder().build();
      assertTrue(s.read(async).orElseThrow().snapshot().isEmpty());
    }
  }

  @Test
  void retriesOnlyRemainingKeyWithStrongConsistencyAndKeepsAlreadyRetrievedItem() {
    for (String pending : List.of("head", "snapshot")) {
      for (boolean async : List.of(false, true)) {
        Stub s = new Stub(BYTES);
        AtomicInteger count = new AtomicInteger();
        s.reply =
            request ->
                count.getAndIncrement() == 0
                    ? partial(pending, 2, 1)
                    : BatchGetItemResponse.builder()
                        .responses(
                            Map.of(pending, List.of(item(pending, pending.equals("head") ? 2 : 1))))
                        .build();
        SnapshotReadResult<byte[]> result = s.read(async).orElseThrow();
        assertEquals(2, result.headSeqNr());
        assertEquals(1, result.snapshot().orElseThrow().seqNr());
        assertEquals(2, s.requests.size());
        assertInitial(s.requests.get(0));
        assertEquals(Set.of(pending), s.requests.get(1).requestItems().keySet());
        assertEquals(List.of(key(pending)), s.requests.get(1).requestItems().get(pending).keys());
        assertTrue(s.requests.get(1).requestItems().get(pending).consistentRead());
        assertEquals(List.of(50L), s.waits);
      }
    }
  }

  @Test
  void defaultRetryLimitExcludesInitialRequestAndCapsBackoffWhileZeroDoesNotWait() {
    for (boolean async : List.of(false, true)) {
      for (int limit : List.of(0, 10)) {
        Stub s = new Stub(BYTES);
        if (limit == 0)
          s.tables = DynamoDbTableConfigTest.names().configurationReadRetryLimit(0).build();
        s.reply = request -> partial("snapshot", 2, 1);
        assertInstanceOf(StorageException.class, s.failure(async));
        assertEquals(limit + 1, s.requests.size());
        assertEquals(
            limit == 0
                ? List.of()
                : List.of(50L, 100L, 200L, 400L, 800L, 1000L, 1000L, 1000L, 1000L, 1000L),
            s.waits);
      }
    }
  }

  @TestFactory
  Stream<DynamicTest> invalidStoredAttributesFailAsStorageOnBothPublicPaths() {
    Map<String, BatchGetItemResponse> invalid = new LinkedHashMap<>();
    for (String table : List.of("head", "snapshot")) {
      for (String attribute :
          table.equals("head")
              ? List.of("aid", "seq_nr")
              : List.of("aid", "skey", "seq_nr", "manifest", "payload")) {
        for (boolean missing : List.of(true, false)) {
          Map<String, AttributeValue> broken = item(table, 1);
          if (missing) broken.remove(attribute);
          else broken.put(attribute, AttributeValue.fromBool(true));
          invalid.put(
              table + "-" + attribute + "-" + missing,
              both(1, 1).toBuilder()
                  .responses(
                      Map.of(
                          table,
                          List.of(broken),
                          table.equals("head") ? "snapshot" : "head",
                          List.of(item(table.equals("head") ? "snapshot" : "head", 1))))
                  .build());
        }
      }
      for (String number :
          List.of("-1", "1.5", "9007199254740992", "9223372036854775808", "invalid")) {
        Map<String, AttributeValue> broken = item(table, 1);
        broken.put("seq_nr", AttributeValue.fromN(number));
        Map<String, List<Map<String, AttributeValue>>> items =
            new LinkedHashMap<>(both(1, 1).responses());
        items.put(table, List.of(broken));
        invalid.put(table + "-" + number, BatchGetItemResponse.builder().responses(items).build());
      }
    }
    Map<String, AttributeValue> wrongKey = item("snapshot", 1);
    wrongKey.put("skey", AttributeValue.fromN("1"));
    invalid.put(
        "history-key",
        BatchGetItemResponse.builder().responses(Map.of("snapshot", List.of(wrongKey))).build());
    invalid.put(
        "unknown-unprocessed-table",
        BatchGetItemResponse.builder()
            .unprocessedKeys(
                Map.of("journal", KeysAndAttributes.builder().keys(key("head")).build()))
            .build());
    return invalid.entrySet().stream()
        .map(
            entry ->
                DynamicTest.dynamicTest(
                    entry.getKey(),
                    () -> {
                      for (boolean async : List.of(false, true)) {
                        Stub s = new Stub(BYTES);
                        s.reply = request -> entry.getValue();
                        assertInstanceOf(StorageException.class, s.failure(async));
                        assertEquals(1, s.requests.size());
                      }
                    }));
  }

  @Test
  void exactIntegralNumbersEmptyManifestAndBinaryAreValid() {
    for (boolean async : List.of(false, true)) {
      Stub s = new Stub(BYTES);
      Map<String, AttributeValue> current = item("snapshot", 1);
      current.put("skey", AttributeValue.fromN("0.00"));
      current.put("seq_nr", AttributeValue.fromN("1.00E0"));
      current.put("manifest", AttributeValue.fromS(""));
      current.put("payload", AttributeValue.fromB(SdkBytes.fromByteArray(new byte[0])));
      s.reply =
          request ->
              both(MAX, 1).toBuilder()
                  .responses(
                      Map.of("head", List.of(item("head", MAX)), "snapshot", List.of(current)))
                  .build();
      SnapshotReadResult<byte[]> result = s.read(async).orElseThrow();
      assertEquals(MAX, result.headSeqNr());
      assertEquals(1, result.snapshot().orElseThrow().seqNr());
      assertEquals("", result.snapshot().orElseThrow().manifest());
      assertArrayEquals(new byte[0], result.snapshot().orElseThrow().aggregate());
    }
  }

  @Test
  void serializerFailuresAndNullAreSerializationAndAsyncExceptionsAreDirect() {
    for (RuntimeException cause :
        Arrays.asList(
            new SerializationException("classified"), new IllegalStateException("decode"), null)) {
      for (boolean async : List.of(false, true)) {
        Stub s =
            new Stub(
                new PayloadSerializer<byte[]>() {
                  public byte[] serialize(byte[] value) {
                    return value;
                  }

                  public byte[] deserialize(byte[] value) {
                    if (cause != null) throw cause;
                    return null;
                  }
                });
        s.reply = request -> both(2, 1);
        Throwable failure = s.failure(async);
        assertInstanceOf(SerializationException.class, failure);
        if (cause instanceof SerializationException) assertSame(cause, failure);
        else if (cause != null) assertSame(cause, failure.getCause());
      }
    }
  }

  @Test
  void sdkImmediateAndWrappedFutureFailuresAreStorageIncludingRetryFailure() {
    SdkClientException cause = SdkClientException.create("transport detail");
    for (boolean async : List.of(false, true)) {
      for (boolean retry : List.of(false, true)) {
        Stub s = new Stub(BYTES);
        AtomicInteger count = new AtomicInteger();
        s.reply =
            request -> {
              if (retry && count.getAndIncrement() == 0) return partial("head", 2, 1);
              throw cause;
            };
        Throwable failure = s.failure(async);
        assertInstanceOf(StorageException.class, failure);
        assertSame(cause, failure.getCause());
        assertEquals(retry ? 2 : 1, s.requests.size());
      }
    }
    Stub s = new Stub(BYTES);
    s.asyncReply =
        request ->
            CompletableFuture.failedFuture(new CompletionException(new ExecutionException(cause)));
    Throwable failure = s.failure(true);
    assertInstanceOf(StorageException.class, failure);
    assertSame(cause, failure.getCause());
  }

  @Test
  void inputValidationPrecedesSdkAndAsyncWaitDoesNotBlockOrLoseFoundItem() {
    Stub s = new Stub(BYTES);
    assertThrows(NullPointerException.class, () -> s.syncStore().getLatestSnapshotById(null));
    assertInstanceOf(
        NullPointerException.class,
        s.asyncStore().getLatestSnapshotById(null).handle((value, failure) -> failure).join());
    assertTrue(s.requests.isEmpty());
    CompletableFuture<BatchGetItemResponse> sdk = new CompletableFuture<>();
    CompletableFuture<Void> wait = new CompletableFuture<>();
    s.asyncReply = request -> sdk;
    s.waitReply = delay -> wait;
    CompletableFuture<Optional<SnapshotReadResult<byte[]>>> result =
        s.asyncStore().getLatestSnapshotById(ID);
    assertFalse(result.isDone());
    sdk.complete(partial("snapshot", 2, 1));
    assertFalse(result.isDone());
    assertEquals(1, s.requests.size());
    s.asyncReply =
        request ->
            CompletableFuture.completedFuture(
                BatchGetItemResponse.builder()
                    .responses(Map.of("snapshot", List.of(item("snapshot", 1))))
                    .build());
    wait.complete(null);
    assertEquals(2, result.join().orElseThrow().headSeqNr());
    assertEquals(1, result.join().orElseThrow().snapshot().orElseThrow().seqNr());
    assertEquals(2, s.requests.size());
  }

  @Test
  void interruptionRestoresFlagAndWaitFailuresFinishAsyncResultDirectly() {
    Stub s = new Stub(BYTES);
    s.reply = request -> partial("snapshot", 2, 1);
    Sleeper interrupted =
        new Sleeper() {
          public void sleep(long millis) throws InterruptedException {
            throw new InterruptedException("wait");
          }
        };
    try {
      assertThrows(
          StorageException.class,
          () ->
              DynamoDbEventStore.create(s.sync, s.tables, s.config, interrupted)
                  .getLatestSnapshotById(ID));
      assertTrue(Thread.currentThread().isInterrupted());
      assertEquals(1, s.requests.size());
    } finally {
      Thread.interrupted();
    }
    s.requests.clear();
    IllegalStateException cause = new IllegalStateException("wait failed");
    s.waitReply = delay -> CompletableFuture.failedFuture(cause);
    Throwable failure = s.failure(true);
    assertInstanceOf(StorageException.class, failure);
    assertSame(cause, failure.getCause());
    assertEquals(1, s.requests.size());
  }

  @Test
  void overlappingReadsKeepTheirOwnPartialResponsesEvenWhenCompletedInReverseOrder() {
    Stub s = new Stub(BYTES);
    List<CompletableFuture<BatchGetItemResponse>> sdk = new ArrayList<>();
    s.asyncReply =
        request -> {
          CompletableFuture<BatchGetItemResponse> response = new CompletableFuture<>();
          sdk.add(response);
          return response;
        };
    AsyncEventStore<byte[], byte[]> store = s.asyncStore();
    CompletableFuture<Optional<SnapshotReadResult<byte[]>>> first = store.getLatestSnapshotById(ID);
    CompletableFuture<Optional<SnapshotReadResult<byte[]>>> second =
        store.getLatestSnapshotById(ID);
    sdk.get(1).complete(partial("head", 3, 2));
    sdk.get(0).complete(partial("snapshot", 2, 1));
    assertFalse(first.isDone());
    assertFalse(second.isDone());
    sdk.get(2)
        .complete(
            BatchGetItemResponse.builder()
                .responses(Map.of("head", List.of(item("head", 3))))
                .build());
    sdk.get(3)
        .complete(
            BatchGetItemResponse.builder()
                .responses(Map.of("snapshot", List.of(item("snapshot", 1))))
                .build());
    assertEquals(2, first.join().orElseThrow().headSeqNr());
    assertEquals(1, first.join().orElseThrow().snapshot().orElseThrow().seqNr());
    assertEquals(3, second.join().orElseThrow().headSeqNr());
    assertEquals(2, second.join().orElseThrow().snapshot().orElseThrow().seqNr());
    assertEquals(4, s.requests.size());
  }

  @Test
  void cancellationDuringSdkOrBackoffDoesNotStartAnotherRequestAndStoreRemainsUsable() {
    for (boolean duringWait : List.of(false, true)) {
      Stub s = new Stub(BYTES);
      CompletableFuture<BatchGetItemResponse> sdk = new CompletableFuture<>();
      CompletableFuture<Void> wait = new CompletableFuture<>();
      s.asyncReply = request -> sdk;
      s.waitReply = delay -> wait;
      AsyncEventStore<byte[], byte[]> store = s.asyncStore();
      CompletableFuture<Optional<SnapshotReadResult<byte[]>>> result =
          store.getLatestSnapshotById(ID);
      if (duringWait) sdk.complete(partial("snapshot", 2, 1));
      assertTrue(result.cancel(true));
      sdk.complete(partial("snapshot", 2, 1));
      wait.complete(null);
      assertEquals(1, s.requests.size());
      assertTrue(result.isCancelled());
      s.asyncReply = null;
      s.reply = request -> both(4, 3);
      assertEquals(4, store.getLatestSnapshotById(ID).join().orElseThrow().headSeqNr());
    }
  }

  private static void assertInitial(BatchGetItemRequest request) {
    assertEquals(Set.of("head", "snapshot"), request.requestItems().keySet());
    request
        .requestItems()
        .forEach(
            (table, attributes) -> {
              assertEquals(List.of(key(table)), attributes.keys());
              assertTrue(attributes.consistentRead());
            });
  }

  private static final class Stub {
    final List<BatchGetItemRequest> requests = new ArrayList<>();
    final List<Long> waits = new ArrayList<>();
    final EventStoreConfig<byte[], byte[]> config;
    DynamoDbTableConfig tables = DynamoDbTableConfigTest.names().build();
    Function<BatchGetItemRequest, BatchGetItemResponse> reply =
        request -> BatchGetItemResponse.builder().build();
    Function<BatchGetItemRequest, CompletableFuture<BatchGetItemResponse>> asyncReply;
    Function<Long, CompletableFuture<Void>> waitReply =
        delay -> CompletableFuture.completedFuture(null);
    final Sleeper sleeper =
        new Sleeper() {
          public void sleep(long millis) {
            waits.add(millis);
          }

          public CompletableFuture<Void> sleepAsync(long millis) {
            waits.add(millis);
            return waitReply.apply(millis);
          }
        };
    final DynamoDbClient sync =
        new DynamoDbClient() {
          public BatchGetItemResponse batchGetItem(BatchGetItemRequest request) {
            if (configuration(request)) return initialized();
            requests.add(request);
            return reply.apply(request);
          }

          public String serviceName() {
            return "dynamodb";
          }

          public void close() {
            fail("Borrowed client closed");
          }
        };
    final DynamoDbAsyncClient async =
        new DynamoDbAsyncClient() {
          public CompletableFuture<BatchGetItemResponse> batchGetItem(BatchGetItemRequest request) {
            if (configuration(request)) return CompletableFuture.completedFuture(initialized());
            requests.add(request);
            return asyncReply == null
                ? CompletableFuture.completedFuture(reply.apply(request))
                : asyncReply.apply(request);
          }

          public String serviceName() {
            return "dynamodb";
          }

          public void close() {
            fail("Borrowed client closed");
          }
        };

    Stub(PayloadSerializer<byte[]> serializer) {
      config =
          EventStoreConfig.<byte[], byte[]>builder()
              .payloadSerializer(
                  new PayloadSerializer<byte[]>() {
                    public byte[] serialize(byte[] value) {
                      return value;
                    }

                    public byte[] deserialize(byte[] value) {
                      throw new AssertionError("Event serializer used by snapshot read");
                    }
                  })
              .snapshotSerializer(serializer)
              .build();
    }

    EventStore<byte[], byte[]> syncStore() {
      return DynamoDbEventStore.create(sync, tables, config, sleeper);
    }

    AsyncEventStore<byte[], byte[]> asyncStore() {
      return DynamoDbEventStore.createAsync(async, tables, config, sleeper).join();
    }

    Optional<SnapshotReadResult<byte[]>> read(boolean asynchronous) {
      return asynchronous
          ? asyncStore().getLatestSnapshotById(ID).join()
          : syncStore().getLatestSnapshotById(ID);
    }

    Throwable failure(boolean asynchronous) {
      return asynchronous
          ? asyncStore().getLatestSnapshotById(ID).handle((value, error) -> error).join()
          : assertThrows(RuntimeException.class, () -> read(false));
    }

    private static boolean configuration(BatchGetItemRequest request) {
      return request.requestItems().values().stream()
          .flatMap(a -> a.keys().stream())
          .anyMatch(k -> AttributeValue.fromS("__config__").equals(k.get("aid")));
    }

    private static BatchGetItemResponse initialized() {
      Map<String, List<Map<String, AttributeValue>>> items = new LinkedHashMap<>();
      for (String table : List.of("journal", "snapshot", "head")) {
        Map<String, AttributeValue> item =
            new LinkedHashMap<>(
                Map.of(
                    "aid",
                    AttributeValue.fromS("__config__"),
                    "store_id",
                    AttributeValue.fromS("unit"),
                    "layout_version",
                    AttributeValue.fromN("1")));
        if (!table.equals("head"))
          item.put(table.equals("journal") ? "seq_nr" : "skey", AttributeValue.fromN("0"));
        items.put(table, List.of(item));
      }
      return BatchGetItemResponse.builder().responses(items).build();
    }
  }
}
