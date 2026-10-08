package com.github.j5ik2o.event.store.adapter.java.dynamodb;

import static org.junit.jupiter.api.Assertions.*;

import com.github.j5ik2o.event.store.adapter.java.core.*;
import java.time.Instant;
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

class DynamoDbEventReadTest {
  private static final AggregateId AID = AggregateId.of("Stored", "value-with-hyphen");
  private static final long MAX_SEQ = (1L << 53) - 1;
  private static final DynamoDbTableConfig TABLES = DynamoDbTableConfigTest.names().build();
  private static final PayloadSerializer<byte[]> BYTES =
      new PayloadSerializer<byte[]>() {
        public byte[] serialize(byte[] value) {
          return value;
        }

        public byte[] deserialize(byte[] value) {
          return value;
        }
      };

  private static Map<String, AttributeValue> item(long seq, long nanos) {
    return new LinkedHashMap<>(
        Map.of(
            "aid", AttributeValue.fromS(AID.asString()),
            "seq_nr", AttributeValue.fromN(Long.toString(seq)),
            "occurred_at", AttributeValue.fromN(Long.toString(nanos)),
            "manifest", AttributeValue.fromS("書式é"),
            "payload",
                AttributeValue.fromB(SdkBytes.fromByteArray(new byte[] {0, 1, (byte) 255}))));
  }

  @Test
  void restoresStoredMetadataAndBinaryThroughSerializerAtIntegerTimeBoundaries() {
    AtomicInteger calls = new AtomicInteger();
    PayloadSerializer<byte[]> serializer =
        new PayloadSerializer<byte[]>() {
          public byte[] serialize(byte[] value) {
            throw new AssertionError("read serialized");
          }

          public byte[] deserialize(byte[] bytes) {
            calls.incrementAndGet();
            assertArrayEquals(new byte[] {0, 1, (byte) 255}, bytes);
            return new byte[] {7};
          }
        };
    for (long nanos :
        new long[] {
          Long.MIN_VALUE, Long.MIN_VALUE + 1, -1, 0, 1, Long.MAX_VALUE - 1, Long.MAX_VALUE
        }) {
      EventEnvelope<byte[]> result = DynamoDbEventRead.restore(item(MAX_SEQ, nanos), serializer);
      assertEquals(AID, result.aggregateId());
      assertEquals(MAX_SEQ, result.seqNr());
      assertEquals(Instant.ofEpochSecond(0, nanos), result.occurredAt());
      assertEquals("書式é", result.manifest());
      assertArrayEquals(new byte[] {7}, result.payload());
    }
    assertEquals(7, calls.get());
    Map<String, AttributeValue> empty = item(1, 0);
    empty.put("aid", AttributeValue.fromS("-"));
    empty.put("manifest", AttributeValue.fromS(""));
    empty.put("payload", AttributeValue.fromB(SdkBytes.fromByteArray(new byte[0])));
    EventEnvelope<byte[]> restored = DynamoDbEventRead.restore(empty, BYTES);
    assertEquals(AggregateId.of("", ""), restored.aggregateId());
    assertEquals("", restored.manifest());
    assertArrayEquals(new byte[0], restored.payload());
  }

  @Test
  void exactIntegralNumberRepresentationsAreAccepted() {
    Map<String, AttributeValue> stored = item(1, -1);
    stored.put("seq_nr", AttributeValue.fromN("1.00E0"));
    stored.put("occurred_at", AttributeValue.fromN("-1.0"));
    EventEnvelope<byte[]> result = DynamoDbEventRead.restore(stored, BYTES);
    assertEquals(1, result.seqNr());
    assertEquals(Instant.ofEpochSecond(0, -1), result.occurredAt());
  }

  @TestFactory
  Stream<DynamicTest> invalidStoredAttributesFailAsStorageOnBothPublicPaths() {
    Map<String, Map<String, AttributeValue>> invalid = new LinkedHashMap<>();
    for (String attribute : List.of("aid", "seq_nr", "occurred_at", "manifest", "payload")) {
      Map<String, AttributeValue> missing = item(1, 0);
      missing.remove(attribute);
      invalid.put("missing-" + attribute, missing);
      Map<String, AttributeValue> wrong = item(1, 0);
      wrong.put(attribute, AttributeValue.fromBool(true));
      invalid.put("type-" + attribute, wrong);
    }
    for (String number :
        List.of("0", "-1", "1.5", "9007199254740992", "9223372036854775808", "invalid")) {
      Map<String, AttributeValue> stored = item(1, 0);
      stored.put("seq_nr", AttributeValue.fromN(number));
      invalid.put("seq-" + number, stored);
    }
    for (String number : List.of("0.5", "9223372036854775808", "-9223372036854775809", "NaN")) {
      Map<String, AttributeValue> stored = item(1, 0);
      stored.put("occurred_at", AttributeValue.fromN(number));
      invalid.put("time-" + number, stored);
    }
    for (String aid : List.of("missing-separator".replace("-", ""), "A-" + "x".repeat(1023))) {
      Map<String, AttributeValue> stored = item(1, 0);
      stored.put("aid", AttributeValue.fromS(aid));
      invalid.put("aid-" + aid.length(), stored);
    }
    return invalid.entrySet().stream()
        .map(
            entry ->
                DynamicTest.dynamicTest(
                    entry.getKey(),
                    () -> {
                      for (boolean async : List.of(false, true)) {
                        Stub stub = new Stub(BYTES);
                        stub.reply =
                            request -> QueryResponse.builder().items(entry.getValue()).build();
                        Throwable failure = stub.failure(async);
                        assertInstanceOf(StorageException.class, failure);
                        assertNotNull(failure.getCause());
                        assertEquals(1, stub.requests.size());
                      }
                    }));
  }

  @Test
  void followsCompleteCursorsAcrossEmptyPageAndStopsAtFinalResponse() {
    Map<String, AttributeValue> first =
        Map.of(
            "aid",
            AttributeValue.fromS(AID.asString()),
            "seq_nr",
            AttributeValue.fromN("1"),
            "extra",
            AttributeValue.fromS("cursor"));
    Map<String, AttributeValue> second =
        Map.of("aid", AttributeValue.fromS(AID.asString()), "seq_nr", AttributeValue.fromN("2"));
    List<QueryResponse> pages =
        List.of(
            QueryResponse.builder().items(item(1, -1)).lastEvaluatedKey(first).build(),
            QueryResponse.builder().lastEvaluatedKey(second).build(),
            QueryResponse.builder().items(item(2, 0), item(3, 1)).build());
    for (boolean async : List.of(false, true)) {
      Stub stub = new Stub(BYTES);
      AtomicInteger page = new AtomicInteger();
      stub.reply = request -> pages.get(page.getAndIncrement());
      List<EventEnvelope<byte[]>> result =
          stub.read(async, AggregateId.of("Input", "different"), 0);
      assertEquals(
          List.of(1L, 2L, 3L),
          List.of(result.get(0).seqNr(), result.get(1).seqNr(), result.get(2).seqNr()));
      assertEquals(AID, result.get(0).aggregateId());
      assertEquals(3, stub.requests.size());
      assertTrue(stub.requests.get(0).exclusiveStartKey().isEmpty());
      assertEquals(first, stub.requests.get(1).exclusiveStartKey());
      assertEquals(second, stub.requests.get(2).exclusiveStartKey());
      for (QueryRequest request : stub.requests) {
        assertEquals("journal", request.tableName());
        assertNull(request.indexName());
        assertEquals("aid = :aid AND seq_nr >= :seq_nr", request.keyConditionExpression());
        assertEquals(
            AttributeValue.fromS("Input-different"),
            request.expressionAttributeValues().get(":aid"));
        assertEquals(AttributeValue.fromN("0"), request.expressionAttributeValues().get(":seq_nr"));
        assertTrue(request.consistentRead());
        assertTrue(request.scanIndexForward());
      }
    }
  }

  @Test
  void inputValidationPrecedesQueryAndAllowsZeroAndMaximumStart() {
    for (boolean async : List.of(false, true)) {
      Stub stub = new Stub(BYTES);
      for (long seq : new long[] {-1, MAX_SEQ + 1}) {
        Throwable failure = assertThrows(RuntimeException.class, () -> stub.read(async, AID, seq));
        ContractViolationException violation =
            (ContractViolationException) EventStoreExceptions.unwrap(failure);
        assertEquals("T-9", violation.rule());
        assertEquals(seq, violation.seqNr().getAsLong());
      }
      assertThrows(RuntimeException.class, () -> stub.read(async, null, 0));
      assertTrue(stub.requests.isEmpty());
      assertTrue(stub.read(async, AID, 0).isEmpty());
      assertTrue(stub.read(async, AID, MAX_SEQ).isEmpty());
      assertEquals(
          AttributeValue.fromN(Long.toString(MAX_SEQ)),
          stub.requests.get(1).expressionAttributeValues().get(":seq_nr"));
    }
  }

  @Test
  void sdkImmediateWrappedAndLaterPageFailuresAreDirectlyClassified() {
    SdkClientException cause = SdkClientException.create("transport detail");
    for (boolean async : List.of(false, true)) {
      for (boolean later : List.of(false, true)) {
        Stub stub = new Stub(BYTES);
        AtomicInteger page = new AtomicInteger();
        stub.reply =
            request -> {
              if (later && page.getAndIncrement() == 0)
                return QueryResponse.builder()
                    .items(item(1, 0))
                    .lastEvaluatedKey(
                        Map.of(
                            "aid",
                            AttributeValue.fromS(AID.asString()),
                            "seq_nr",
                            AttributeValue.fromN("1")))
                    .build();
              throw cause;
            };
        Throwable failure = stub.failure(async);
        assertInstanceOf(StorageException.class, failure);
        assertSame(cause, failure.getCause());
        assertEquals(later ? 2 : 1, stub.requests.size());
      }
    }
    Stub wrapped = new Stub(BYTES);
    wrapped.asyncReply =
        request ->
            CompletableFuture.failedFuture(new CompletionException(new ExecutionException(cause)));
    Throwable failure = wrapped.failure(true);
    assertInstanceOf(StorageException.class, failure);
    assertSame(cause, failure.getCause());
  }

  @Test
  void asynchronousResultWaitsForSdkAndResponseFailureCompletesIt() {
    Stub stub = new Stub(BYTES);
    CompletableFuture<QueryResponse> sdk = new CompletableFuture<>();
    stub.asyncReply = request -> sdk;
    CompletableFuture<List<EventEnvelope<byte[]>>> result =
        stub.asyncStore().getEventsByIdSinceSeqNr(AID, 0);
    assertFalse(result.isDone());
    Map<String, AttributeValue> invalid = item(1, 0);
    invalid.remove("payload");
    sdk.complete(QueryResponse.builder().items(invalid).build());
    assertInstanceOf(StorageException.class, result.handle((value, failure) -> failure).join());
  }

  @Test
  void serializerFailuresAndJavaNullStaySerializationOnBothPaths() {
    for (RuntimeException cause :
        Arrays.asList(
            new SerializationException("classified"), new IllegalStateException("decode"), null)) {
      PayloadSerializer<byte[]> serializer =
          new PayloadSerializer<byte[]>() {
            public byte[] serialize(byte[] value) {
              return value;
            }

            public byte[] deserialize(byte[] value) {
              if (cause != null) throw cause;
              return null;
            }
          };
      for (boolean async : List.of(false, true)) {
        Stub stub = new Stub(serializer);
        stub.reply = request -> QueryResponse.builder().items(item(1, 0)).build();
        Throwable failure = stub.failure(async);
        assertInstanceOf(SerializationException.class, failure);
        if (cause instanceof SerializationException) assertSame(cause, failure);
        else if (cause != null) assertSame(cause, failure.getCause());
      }
    }
  }

  private static final class Stub {
    final List<QueryRequest> requests = new ArrayList<>();
    final EventStoreConfig<byte[], byte[]> config;
    Function<QueryRequest, QueryResponse> reply = request -> QueryResponse.builder().build();
    Function<QueryRequest, CompletableFuture<QueryResponse>> asyncReply;
    final DynamoDbClient sync =
        new DynamoDbClient() {
          public BatchGetItemResponse batchGetItem(BatchGetItemRequest request) {
            return initialized();
          }

          public QueryResponse query(QueryRequest request) {
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
            return CompletableFuture.completedFuture(initialized());
          }

          public CompletableFuture<QueryResponse> query(QueryRequest request) {
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
              .payloadSerializer(serializer)
              .snapshotSerializer(BYTES)
              .build();
    }

    AsyncEventStore<byte[], byte[]> asyncStore() {
      return DynamoDbEventStore.createAsync(async, TABLES, config).join();
    }

    List<EventEnvelope<byte[]>> read(boolean asynchronous, AggregateId id, long seq) {
      return asynchronous
          ? asyncStore().getEventsByIdSinceSeqNr(id, seq).join()
          : DynamoDbEventStore.create(sync, TABLES, config).getEventsByIdSinceSeqNr(id, seq);
    }

    Throwable failure(boolean asynchronous) {
      if (asynchronous)
        return asyncStore().getEventsByIdSinceSeqNr(AID, 0).handle((value, error) -> error).join();
      return assertThrows(RuntimeException.class, () -> read(false, AID, 0));
    }

    private BatchGetItemResponse initialized() {
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
