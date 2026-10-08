package com.github.j5ik2o.event.store.adapter.java.dynamodb;

import static org.junit.jupiter.api.Assertions.*;

import com.github.j5ik2o.event.store.adapter.java.core.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;

class DynamoDbEventStoreTest {
  private static final DynamoDbTableConfig TABLES = DynamoDbTableConfigTest.names().build();

  private static EventStoreConfig<String, String> config() {
    return EventStoreConfig.<String, String>builder()
        .payloadSerializer(JsonPayloadSerializer.of(String.class))
        .snapshotSerializer(JsonPayloadSerializer.of(String.class))
        .build();
  }

  private static BatchGetItemResponse initialized() {
    Map<String, List<Map<String, AttributeValue>>> items = new LinkedHashMap<>();
    for (String table : List.of("journal", "snapshot", "head")) {
      Map<String, AttributeValue> item = new LinkedHashMap<>();
      item.put("aid", AttributeValue.fromS("__config__"));
      if (!table.equals("head"))
        item.put(table.equals("journal") ? "seq_nr" : "skey", AttributeValue.fromN("0"));
      item.put("store_id", AttributeValue.fromS("unit-store"));
      item.put("layout_version", AttributeValue.fromN("1"));
      items.put(table, List.of(item));
    }
    return BatchGetItemResponse.builder().responses(items).build();
  }

  @Test
  void synchronousSdkFailureIsClassifiedWithoutAnotherReadOrWrite() {
    AtomicInteger reads = new AtomicInteger();
    AtomicInteger writes = new AtomicInteger();
    SdkClientException cause = SdkClientException.create("SDK transport detail");
    DynamoDbClient client =
        new DynamoDbClient() {
          public BatchGetItemResponse batchGetItem(BatchGetItemRequest request) {
            reads.incrementAndGet();
            return initialized();
          }

          public TransactWriteItemsResponse transactWriteItems(TransactWriteItemsRequest request) {
            writes.incrementAndGet();
            throw cause;
          }

          public String serviceName() {
            return "dynamodb";
          }

          public void close() {
            fail("Borrowed client must remain open");
          }
        };
    EventStore<String, String> store = DynamoDbEventStore.create(client, TABLES, config());
    StorageException failure =
        assertThrows(
            StorageException.class, () -> store.persistEvent(DynamoDbEventWriteTest.event(1, 1)));
    assertSame(cause, failure.getCause());
    assertFalse(failure.getMessage().contains("SDK transport detail"));
    StorageException snapshotFailure =
        assertThrows(
            StorageException.class,
            () ->
                store.persistEventAndSnapshot(
                    DynamoDbEventWriteTest.event(1, 1), DynamoDbEventWriteTest.snapshot(1)));
    assertSame(cause, snapshotFailure.getCause());
    assertEquals(1, reads.get());
    assertEquals(2, writes.get());
  }

  @Test
  void asynchronousSdkThrowAndWrappedFutureFailureBothReturnClassifiedFailedFutures() {
    for (boolean immediate : List.of(false, true)) {
      AtomicInteger reads = new AtomicInteger();
      AtomicInteger writes = new AtomicInteger();
      SdkClientException cause = SdkClientException.create("SDK transport detail");
      DynamoDbAsyncClient client =
          new DynamoDbAsyncClient() {
            public CompletableFuture<BatchGetItemResponse> batchGetItem(
                BatchGetItemRequest request) {
              reads.incrementAndGet();
              return CompletableFuture.completedFuture(initialized());
            }

            public CompletableFuture<TransactWriteItemsResponse> transactWriteItems(
                TransactWriteItemsRequest request) {
              writes.incrementAndGet();
              if (immediate) throw cause;
              return CompletableFuture.failedFuture(new CompletionException(cause));
            }

            public String serviceName() {
              return "dynamodb";
            }

            public void close() {
              fail("Borrowed client must remain open");
            }
          };
      AsyncEventStore<String, String> store =
          DynamoDbEventStore.createAsync(client, TABLES, config()).join();
      CompletableFuture<Void> result =
          assertDoesNotThrow(() -> store.persistEvent(DynamoDbEventWriteTest.event(1, 1)));
      Throwable callbackFailure = result.handle((ignored, error) -> error).join();
      assertInstanceOf(StorageException.class, callbackFailure);
      Throwable failure =
          EventStoreExceptions.unwrap(assertThrows(CompletionException.class, result::join));
      assertSame(callbackFailure, failure);
      assertSame(cause, failure.getCause());
      CompletableFuture<Void> snapshotResult =
          assertDoesNotThrow(
              () ->
                  store.persistEventAndSnapshot(
                      DynamoDbEventWriteTest.event(1, 1), DynamoDbEventWriteTest.snapshot(1)));
      Throwable snapshotCallbackFailure = snapshotResult.handle((ignored, error) -> error).join();
      assertInstanceOf(StorageException.class, snapshotCallbackFailure);
      assertSame(cause, snapshotCallbackFailure.getCause());
      assertEquals(1, reads.get());
      assertEquals(2, writes.get());
    }
  }

  @Test
  void asynchronousSuccessWaitsForSdkCompletionAndInputFailureDoesNotCallSdk() {
    CompletableFuture<TransactWriteItemsResponse> sdk = new CompletableFuture<>();
    AtomicInteger writes = new AtomicInteger();
    DynamoDbAsyncClient client =
        new DynamoDbAsyncClient() {
          public CompletableFuture<BatchGetItemResponse> batchGetItem(BatchGetItemRequest request) {
            return CompletableFuture.completedFuture(initialized());
          }

          public CompletableFuture<TransactWriteItemsResponse> transactWriteItems(
              TransactWriteItemsRequest request) {
            writes.incrementAndGet();
            return sdk;
          }

          public String serviceName() {
            return "dynamodb";
          }

          public void close() {
            fail("Borrowed client must remain open");
          }
        };
    AsyncEventStore<String, String> store =
        DynamoDbEventStore.createAsync(client, TABLES, config()).join();
    CompletableFuture<Void> invalid = assertDoesNotThrow(() -> store.persistEvent(null));
    assertInstanceOf(
        ContractViolationException.class, invalid.handle((ignored, error) -> error).join());
    for (CompletableFuture<Void> rejected :
        List.of(
            store.persistEventAndSnapshot(null, DynamoDbEventWriteTest.snapshot(1)),
            store.persistEventAndSnapshot(DynamoDbEventWriteTest.event(1, 1), null),
            store.persistEventAndSnapshot(
                DynamoDbEventWriteTest.event(1, 1), DynamoDbEventWriteTest.snapshot(2)))) {
      assertInstanceOf(
          ContractViolationException.class, rejected.handle((ignored, error) -> error).join());
    }
    assertEquals(0, writes.get());
    CompletableFuture<Void> result = store.persistEvent(DynamoDbEventWriteTest.event(1, 1));
    CompletableFuture<Void> snapshotResult =
        store.persistEventAndSnapshot(
            DynamoDbEventWriteTest.event(1, 1), DynamoDbEventWriteTest.snapshot(1));
    assertFalse(result.isDone());
    assertFalse(snapshotResult.isDone());
    sdk.complete(TransactWriteItemsResponse.builder().build());
    assertNull(result.join());
    assertNull(snapshotResult.join());
    assertEquals(2, writes.get());
  }

  @Test
  void publicGenerationWiresDistinctEventAndSnapshotTypesIntoBothStores() {
    List<TransactWriteItemsRequest> requests = new ArrayList<>();
    EventStoreConfig<String, Integer> config =
        EventStoreConfig.<String, Integer>builder()
            .payloadSerializer(JsonPayloadSerializer.of(String.class))
            .snapshotSerializer(JsonPayloadSerializer.of(Integer.class))
            .build();
    DynamoDbClient client =
        new DynamoDbClient() {
          public BatchGetItemResponse batchGetItem(BatchGetItemRequest request) {
            return initialized();
          }

          public TransactWriteItemsResponse transactWriteItems(TransactWriteItemsRequest request) {
            requests.add(request);
            return TransactWriteItemsResponse.builder().build();
          }

          public String serviceName() {
            return "dynamodb";
          }

          public void close() {
            fail("Borrowed client must remain open");
          }
        };
    DynamoDbAsyncClient asyncClient =
        new DynamoDbAsyncClient() {
          public CompletableFuture<BatchGetItemResponse> batchGetItem(BatchGetItemRequest request) {
            return CompletableFuture.completedFuture(initialized());
          }

          public CompletableFuture<TransactWriteItemsResponse> transactWriteItems(
              TransactWriteItemsRequest request) {
            requests.add(request);
            return CompletableFuture.completedFuture(TransactWriteItemsResponse.builder().build());
          }

          public String serviceName() {
            return "dynamodb";
          }

          public void close() {
            fail("Borrowed client must remain open");
          }
        };
    SnapshotEnvelope<Integer> snapshot =
        SnapshotEnvelope.<Integer>builder()
            .aggregate(42)
            .seqNr(1)
            .manifest("integer-state")
            .build();
    DynamoDbEventStore.create(client, TABLES, config)
        .persistEventAndSnapshot(DynamoDbEventWriteTest.event(1, 1), snapshot);
    DynamoDbEventStore.createAsync(asyncClient, TABLES, config)
        .join()
        .persistEventAndSnapshot(DynamoDbEventWriteTest.event(1, 1), snapshot)
        .join();
    assertEquals(2, requests.size());
    for (TransactWriteItemsRequest request : requests) {
      assertEquals(3, request.transactItems().size());
      Map<String, AttributeValue> event = request.transactItems().get(0).put().item();
      Map<String, AttributeValue> current = request.transactItems().get(2).put().item();
      assertEquals("\"payload\"", event.get("payload").b().asUtf8String());
      assertEquals("42", current.get("payload").b().asUtf8String());
      assertEquals("integer-state", current.get("manifest").s());
    }
  }
}
