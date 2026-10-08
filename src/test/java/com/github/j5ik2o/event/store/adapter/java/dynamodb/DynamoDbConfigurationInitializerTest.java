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

/**
 * Unit inputs exercise validation and interruption; real storage evidence lives in boundary tests.
 */
class DynamoDbConfigurationInitializerTest {
  private static EventStoreConfig<String, String> config() {
    return EventStoreConfig.<String, String>builder()
        .payloadSerializer(JsonPayloadSerializer.of(String.class))
        .snapshotSerializer(JsonPayloadSerializer.of(String.class))
        .build();
  }

  private static Map<String, List<Map<String, AttributeValue>>> configurations() {
    Map<String, List<Map<String, AttributeValue>>> result = new LinkedHashMap<>();
    for (String table : List.of("journal", "snapshot", "head")) {
      Map<String, AttributeValue> item = new LinkedHashMap<>();
      item.put("aid", AttributeValue.fromS("__config__"));
      if (!table.equals("head"))
        item.put(table.equals("journal") ? "seq_nr" : "skey", AttributeValue.fromN("0.0"));
      item.put("store_id", AttributeValue.fromS("unit-store"));
      item.put("layout_version", AttributeValue.fromN("1.00"));
      result.put(table, List.of(item));
    }
    return result;
  }

  private static DynamoDbClient client(BatchGetItemResponse response) {
    return new DynamoDbClient() {
      public BatchGetItemResponse batchGetItem(BatchGetItemRequest request) {
        return response;
      }

      public String serviceName() {
        return "dynamodb";
      }

      public void close() {
        fail("Borrowed client must not be closed");
      }
    };
  }

  private static DynamoDbAsyncClient async(BatchGetItemResponse response) {
    return new DynamoDbAsyncClient() {
      public CompletableFuture<BatchGetItemResponse> batchGetItem(BatchGetItemRequest request) {
        return CompletableFuture.completedFuture(response);
      }

      public String serviceName() {
        return "dynamodb";
      }

      public void close() {
        fail("Borrowed client must not be closed");
      }
    };
  }

  @Test
  void numbersAreComparedNumericallyAndAllUnprovidedOperationsFailExplicitly() {
    BatchGetItemResponse response =
        BatchGetItemResponse.builder().responses(configurations()).build();
    DynamoDbTableConfig tables = DynamoDbTableConfigTest.names().build();
    EventStore<String, String> sync = DynamoDbEventStore.create(client(response), tables, config());
    AsyncEventStore<String, String> async =
        DynamoDbEventStore.createAsync(async(response), tables, config()).join();
    assertThrows(ContractViolationException.class, () -> sync.persistEvent(null));
    assertInstanceOf(
        ContractViolationException.class,
        EventStoreExceptions.unwrap(
            assertThrows(CompletionException.class, () -> async.persistEvent(null).join())));
    assertThrows(
        UnsupportedOperationException.class, () -> sync.persistEventAndSnapshot(null, null));
    assertThrows(UnsupportedOperationException.class, () -> sync.getLatestSnapshotById(null));
    assertThrows(UnsupportedOperationException.class, () -> sync.getEventsByIdSinceSeqNr(null, 0));
    for (CompletableFuture<?> future :
        List.of(
            async.persistEventAndSnapshot(null, null),
            async.getLatestSnapshotById(null),
            async.getEventsByIdSinceSeqNr(null, 0))) {
      assertInstanceOf(
          UnsupportedOperationException.class,
          EventStoreExceptions.unwrap(assertThrows(CompletionException.class, future::join)));
    }
  }

  @Test
  void invalidStoredIdentifiersAndVersionsAreConfigurationFailuresOnBothEntries() {
    for (String attribute : List.of("store_id", "layout_version")) {
      for (AttributeValue value :
          List.of(
              AttributeValue.fromS(""), AttributeValue.fromN("2"), AttributeValue.fromN("1.5"))) {
        Map<String, List<Map<String, AttributeValue>>> items = configurations();
        items.get("snapshot").get(0).put(attribute, value);
        assertConfigurationFailure(BatchGetItemResponse.builder().responses(items).build());
      }
      Map<String, List<Map<String, AttributeValue>>> items = configurations();
      items.get("head").get(0).remove(attribute);
      assertConfigurationFailure(BatchGetItemResponse.builder().responses(items).build());
    }
  }

  private static void assertConfigurationFailure(BatchGetItemResponse response) {
    DynamoDbTableConfig tables = DynamoDbTableConfigTest.names().build();
    assertThrows(
        ConfigurationException.class,
        () -> DynamoDbEventStore.create(client(response), tables, config()));
    assertInstanceOf(
        ConfigurationException.class,
        EventStoreExceptions.unwrap(
            assertThrows(
                CompletionException.class,
                () -> DynamoDbEventStore.createAsync(async(response), tables, config()).join())));
  }

  @Test
  void unexpectedResponseAndUnprocessedKeysAreStorageFailures() {
    Map<String, List<Map<String, AttributeValue>>> wrong = configurations();
    wrong.get("journal").get(0).put("seq_nr", AttributeValue.fromN("7"));
    List<BatchGetItemResponse> responses =
        List.of(
            BatchGetItemResponse.builder().responses(wrong).build(),
            BatchGetItemResponse.builder()
                .unprocessedKeys(
                    Map.of(
                        "unexpected",
                        KeysAndAttributes.builder()
                            .keys(List.of(Map.of("aid", AttributeValue.fromS("__config__"))))
                            .build()))
                .build());
    for (BatchGetItemResponse response : responses) {
      DynamoDbTableConfig tables = DynamoDbTableConfigTest.names().build();
      assertThrows(
          StorageException.class,
          () -> DynamoDbEventStore.create(client(response), tables, config()));
      assertInstanceOf(
          StorageException.class,
          EventStoreExceptions.unwrap(
              assertThrows(
                  CompletionException.class,
                  () -> DynamoDbEventStore.createAsync(async(response), tables, config()).join())));
    }
  }

  @Test
  void interruptedWaitStopsBeforeRetryAndRestoresInterruptFlag() {
    AtomicInteger reads = new AtomicInteger();
    DynamoDbClient client =
        new DynamoDbClient() {
          public BatchGetItemResponse batchGetItem(BatchGetItemRequest request) {
            reads.incrementAndGet();
            return BatchGetItemResponse.builder().unprocessedKeys(request.requestItems()).build();
          }

          public String serviceName() {
            return "dynamodb";
          }

          public void close() {
            fail("Borrowed client must not be closed");
          }
        };
    try {
      Thread.currentThread().interrupt();
      assertThrows(
          StorageException.class,
          () ->
              DynamoDbEventStore.create(client, DynamoDbTableConfigTest.names().build(), config()));
      assertTrue(Thread.currentThread().isInterrupted());
      assertEquals(1, reads.get());
    } finally {
      Thread.interrupted();
    }
  }

  @Test
  void synchronousSdkThrowAtAsyncEntryIsReturnedAsFailedFuture() {
    DynamoDbAsyncClient client =
        new DynamoDbAsyncClient() {
          public CompletableFuture<BatchGetItemResponse> batchGetItem(BatchGetItemRequest request) {
            throw SdkClientException.create("unit transport failure");
          }

          public String serviceName() {
            return "dynamodb";
          }

          public void close() {
            fail("Borrowed client must not be closed");
          }
        };
    CompletableFuture<?> result =
        assertDoesNotThrow(
            () ->
                DynamoDbEventStore.createAsync(
                    client, DynamoDbTableConfigTest.names().build(), config()));
    assertInstanceOf(
        StorageException.class,
        EventStoreExceptions.unwrap(assertThrows(CompletionException.class, result::join)));
  }

  @Test
  void asynchronousSystemWaitCompletes() throws Exception {
    Sleeper.SYSTEM.sleepAsync(1).get(10, TimeUnit.SECONDS);
  }
}
