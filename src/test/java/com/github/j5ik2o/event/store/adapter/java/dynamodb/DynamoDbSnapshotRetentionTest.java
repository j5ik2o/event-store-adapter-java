package com.github.j5ik2o.event.store.adapter.java.dynamodb;

import static org.junit.jupiter.api.Assertions.*;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.github.j5ik2o.event.store.adapter.java.core.*;
import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.awscore.AwsRequest;
import software.amazon.awssdk.core.SdkResponse;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.core.interceptor.Context;
import software.amazon.awssdk.core.interceptor.ExecutionAttributes;
import software.amazon.awssdk.core.interceptor.ExecutionInterceptor;
import software.amazon.awssdk.core.interceptor.InterceptorContext;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbServiceClientConfiguration;
import software.amazon.awssdk.services.dynamodb.model.*;

class DynamoDbSnapshotRetentionTest {
  private static final AggregateId ID = AggregateId.of("Order", "9");
  private static final PayloadSerializer<String> JSON = JsonPayloadSerializer.of(String.class);

  static EventEnvelope<String> event(long seq) {
    return EventEnvelope.<String>builder()
        .aggregateId(ID)
        .seqNr(seq)
        .occurredAt(Instant.EPOCH)
        .payload("event")
        .build();
  }

  static SnapshotEnvelope<String> snapshot(long seq) {
    return SnapshotEnvelope.<String>builder().seqNr(seq).aggregate("snapshot").build();
  }

  static Map<String, AttributeValue> history(long seq) {
    return Map.of(
        "aid",
        AttributeValue.fromS(ID.asString()),
        "skey",
        AttributeValue.fromN(Long.toString(seq)),
        "active_history_seq_nr",
        AttributeValue.fromN(Long.toString(seq)));
  }

  @Test
  void bothFactoriesOnlyStartRetentionAfterSuccessfulSnapshotCommit() {
    for (boolean async : List.of(false, true)) {
      for (RetentionPolicy policy :
          List.of(RetentionPolicy.none(), RetentionPolicy.delete(1), RetentionPolicy.ttl(1, 60))) {
        Stub s = new Stub(policy);
        s.write(async, false, 1);
        assertTrue(s.queries.isEmpty());
        s.write(async, true, 2);
        assertEquals(policy.mode().orElse(null) == RetentionMode.DELETE ? 1 : 0, s.queries.size());
        s.queries.clear();
        s.commit = request -> CompletableFuture.failedFuture(new IllegalStateException("commit"));
        assertThrows(RuntimeException.class, () -> s.write(async, true, 3));
        assertTrue(s.queries.isEmpty());
        assertTrue(s.deletes.isEmpty());
        assertTrue(s.failures.isEmpty());
      }
    }
  }

  @Test
  void asynchronousFutureWaitsForCommitEveryPageDeleteBackoffAndNotification() throws Exception {
    Stub s = new Stub(RetentionPolicy.delete(1));
    CompletableFuture<TransactWriteItemsResponse> commit = new CompletableFuture<>();
    CompletableFuture<QueryResponse> first = new CompletableFuture<>(),
        second = new CompletableFuture<>();
    CompletableFuture<BatchWriteItemResponse> deletion = new CompletableFuture<>();
    CompletableFuture<Void> wait = new CompletableFuture<>();
    s.commit = request -> commit;
    s.query = request -> s.queries.size() == 1 ? first : second;
    s.delete = request -> deletion;
    s.wait = delay -> wait;
    AsyncEventStore<String, String> store = s.asyncStore();
    CompletableFuture<Void> result = store.persistEventAndSnapshot(event(3), snapshot(3));
    assertFalse(result.isDone());
    assertTrue(s.queries.isEmpty());
    commit.complete(TransactWriteItemsResponse.builder().build());
    assertEquals(1, s.queries.size());
    assertTrue(s.deletes.isEmpty());
    first.complete(QueryResponse.builder().items(history(2)).lastEvaluatedKey(history(2)).build());
    assertFalse(result.isDone());
    assertEquals(history(2), s.queries.get(1).exclusiveStartKey());
    assertTrue(s.deletes.isEmpty());
    second.complete(QueryResponse.builder().items(history(2), history(1)).build());
    assertEquals(List.of(2L, 1L), numbers(s.deletes.get(0)));
    deletion.complete(
        BatchWriteItemResponse.builder().unprocessedItems(s.deletes.get(0).requestItems()).build());
    assertFalse(result.isDone());
    assertEquals(List.of(50L), s.waits);
    assertEquals(1, s.deletes.size());
    IllegalStateException cause = new IllegalStateException("delete");
    s.delete = request -> CompletableFuture.failedFuture(cause);
    s.listener =
        failure -> {
          assertFalse(result.isDone());
          assertSame(cause, failure.cause());
        };
    wait.complete(null);
    assertNull(result.get(5, TimeUnit.SECONDS));
    assertEquals(2, s.deletes.size());
    assertEquals(1, s.failures.size());
  }

  @Test
  void bothPathsReadAllPagesDeduplicateAndKeepCommittedHistoryBeforeDeleting() {
    for (boolean async : List.of(false, true)) {
      Stub s = new Stub(RetentionPolicy.delete(2));
      s.query =
          request ->
              CompletableFuture.completedFuture(
                  s.queries.size() == 1
                      ? QueryResponse.builder()
                          .items(history(4), history(3))
                          .lastEvaluatedKey(history(3))
                          .build()
                      : QueryResponse.builder().items(history(3), history(2), history(1)).build());
      s.write(async, true, 5);
      assertEquals(2, s.queries.size());
      for (QueryRequest query : s.queries) {
        assertEquals("snapshot", query.tableName());
        assertEquals("history", query.indexName());
        assertEquals("aid = :aid", query.keyConditionExpression());
        assertEquals(
            Map.of(":aid", AttributeValue.fromS(ID.asString())), query.expressionAttributeValues());
        assertFalse(query.scanIndexForward());
        assertFalse(query.consistentRead());
      }
      assertEquals(history(3), s.queries.get(1).exclusiveStartKey());
      assertEquals(List.of(3L, 2L, 1L), numbers(s.deletes.get(0)));
      assertTrue(s.failures.isEmpty());
    }
  }

  @Test
  void bothPathsBatchAt25AndRetryOnlyUnprocessedWithFixedLimitAndBackoff() {
    for (boolean async : List.of(false, true)) {
      for (boolean exhausted : List.of(false, true)) {
        Stub s = new Stub(RetentionPolicy.delete(1));
        s.tables =
            DynamoDbTableConfigTest.names()
                .retentionPolicy(RetentionPolicy.delete(1))
                .configurationReadRetryLimit(0)
                .build();
        s.query =
            request ->
                CompletableFuture.completedFuture(
                    QueryResponse.builder()
                        .items(
                            java.util.stream.LongStream.rangeClosed(1, 31)
                                .mapToObj(DynamoDbSnapshotRetentionTest::history)
                                .collect(Collectors.toList()))
                        .build());
        s.delete =
            request -> {
              List<WriteRequest> writes = request.requestItems().get("snapshot");
              return CompletableFuture.completedFuture(
                  (s.deletes.size() == 1 || exhausted)
                      ? BatchWriteItemResponse.builder()
                          .unprocessedItems(Map.of("snapshot", writes.subList(0, 2)))
                          .build()
                      : BatchWriteItemResponse.builder().build());
            };
        s.write(async, true, 31);
        assertEquals(25, numbers(s.deletes.get(0)).size());
        assertEquals(numbers(s.deletes.get(0)).subList(0, 2), numbers(s.deletes.get(1)));
        if (exhausted) {
          assertEquals(11, s.deletes.size());
          assertEquals(
              List.of(50L, 100L, 200L, 400L, 800L, 1000L, 1000L, 1000L, 1000L, 1000L), s.waits);
          assertEquals(1, s.failures.size());
          assertInstanceOf(StorageException.class, s.failures.get(0).cause());
          for (int i = 1; i < s.deletes.size(); i++)
            assertEquals(numbers(s.deletes.get(1)), numbers(s.deletes.get(i)));
        } else {
          assertEquals(3, s.deletes.size());
          assertEquals(5, numbers(s.deletes.get(2)).size());
          assertEquals(List.of(50L), s.waits);
          assertTrue(s.failures.isEmpty());
        }
      }
    }
  }

  @Test
  void queryAndDeleteFailuresWarnOncePreserveOriginalCauseAndIgnoreCallbackException() {
    Logger logger = (Logger) LoggerFactory.getLogger(DynamoDbSnapshotRetention.class);
    ListAppender<ILoggingEvent> logs = new ListAppender<>();
    logs.start();
    logger.addAppender(logs);
    try {
      for (boolean async : List.of(false, true)) {
        for (boolean queryFailure : List.of(false, true)) {
          logs.list.clear();
          Stub s = new Stub(RetentionPolicy.delete(1));
          IllegalStateException cause = new IllegalStateException("original");
          s.query =
              request ->
                  queryFailure
                      ? CompletableFuture.failedFuture(
                          new CompletionException(new ExecutionException(cause)))
                      : CompletableFuture.completedFuture(
                          QueryResponse.builder().items(history(1)).build());
          s.delete = request -> CompletableFuture.failedFuture(cause);
          Thread caller = Thread.currentThread();
          s.listener =
              failure -> {
                assertEquals(ID, failure.aggregateId());
                assertEquals(RetentionMode.DELETE, failure.mode());
                assertSame(cause, failure.cause());
                if (!async) assertSame(caller, Thread.currentThread());
                throw new IllegalStateException("callback");
              };
          assertDoesNotThrow(() -> s.write(async, true, 2));
          assertEquals(1, s.failures.size());
          assertEquals(1, logs.list.stream().filter(log -> log.getLevel() == Level.WARN).count());
        }
      }
    } finally {
      logger.detachAppender(logs);
      logs.stop();
    }
  }

  @Test
  void notificationRunsOnSdkCompletionThreadBeforeOuterFutureCompletes() throws Exception {
    Stub s = new Stub(RetentionPolicy.delete(1));
    CompletableFuture<QueryResponse> response = new CompletableFuture<>();
    s.query = request -> response;
    ExecutorService executor =
        Executors.newSingleThreadExecutor(r -> new Thread(r, "controlled-sdk-completion"));
    try {
      CompletableFuture<Void> result =
          s.asyncStore().persistEventAndSnapshot(event(2), snapshot(2));
      s.listener =
          failure -> {
            assertEquals("controlled-sdk-completion", Thread.currentThread().getName());
            assertFalse(result.isDone());
          };
      executor
          .submit(() -> response.completeExceptionally(new IllegalStateException("query")))
          .get();
      assertNull(result.get(5, TimeUnit.SECONDS));
      assertEquals(1, s.failures.size());
    } finally {
      executor.shutdown();
      assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
    }
  }

  @Test
  void malformedGsiKeysAndUnexpectedUnprocessedDeletesNeverReachAnotherDelete() {
    for (boolean async : List.of(false, true)) {
      for (String field : List.of("aid", "skey", "active_history_seq_nr", "ttl")) {
        Stub s = new Stub(RetentionPolicy.delete(1));
        Map<String, AttributeValue> invalid = new LinkedHashMap<>(history(1));
        invalid.put(
            field,
            field.equals("aid") ? AttributeValue.fromS("Order-other") : AttributeValue.fromN("0"));
        s.query =
            request ->
                CompletableFuture.completedFuture(QueryResponse.builder().items(invalid).build());
        s.write(async, true, 2);
        assertEquals(1, s.failures.size());
        StorageException cause =
            assertInstanceOf(StorageException.class, s.failures.get(0).cause());
        assertEquals(ErrorCategory.STORAGE, cause.category());
        assertTrue(s.deletes.isEmpty());
      }
      Stub s = new Stub(RetentionPolicy.delete(1));
      s.query =
          request ->
              CompletableFuture.completedFuture(QueryResponse.builder().items(history(1)).build());
      s.delete =
          request ->
              CompletableFuture.completedFuture(
                  BatchWriteItemResponse.builder()
                      .unprocessedItems(Map.of("other", request.requestItems().get("snapshot")))
                      .build());
      s.write(async, true, 2);
      assertEquals(1, s.deletes.size());
      assertEquals(1, s.failures.size());
      assertTrue(s.waits.isEmpty());
    }
  }

  @Test
  void invalidStoredHistoryNumbersAreStorageFailuresOnBothPaths() {
    for (boolean async : List.of(false, true)) {
      for (String field : List.of("skey", "active_history_seq_nr")) {
        for (String number : List.of("9007199254740992", "1.5", "9223372036854775808")) {
          Stub s = new Stub(RetentionPolicy.delete(1));
          Map<String, AttributeValue> invalid = new LinkedHashMap<>(history(1));
          invalid.put(field, AttributeValue.fromN(number));
          if (field.equals("skey"))
            invalid.put("active_history_seq_nr", AttributeValue.fromN(number));
          s.query =
              request ->
                  CompletableFuture.completedFuture(
                      QueryResponse.builder().items(invalid).lastEvaluatedKey(history(1)).build());
          s.write(async, true, 2);
          assertEquals(1, s.queries.size());
          assertTrue(s.deletes.isEmpty());
          assertEquals(1, s.failures.size());
          StorageException cause =
              assertInstanceOf(StorageException.class, s.failures.get(0).cause());
          assertEquals(ErrorCategory.STORAGE, cause.category());
          if (field.equals("active_history_seq_nr") && number.equals("9007199254740992"))
            assertNull(cause.getCause()); // The existing unequal-key rejection is already Storage.
          else if (number.equals("9007199254740992"))
            assertInstanceOf(ContractViolationException.class, cause.getCause());
          else assertInstanceOf(ArithmeticException.class, cause.getCause());
        }
      }
    }
  }

  @Test
  void asynchronousBackoffListenerErrorStillCompletesCommittedWrite() throws Exception {
    Stub s = new Stub(RetentionPolicy.delete(1));
    s.query =
        request ->
            CompletableFuture.completedFuture(QueryResponse.builder().items(history(1)).build());
    s.delete =
        request ->
            CompletableFuture.completedFuture(
                BatchWriteItemResponse.builder().unprocessedItems(request.requestItems()).build());
    CompletableFuture<Void> wait = new CompletableFuture<>();
    s.wait = delay -> wait;
    IllegalStateException cause = new IllegalStateException("backoff");
    AssertionError callbackError = new AssertionError("listener");
    CompletableFuture<Void> result = s.asyncStore().persistEventAndSnapshot(event(2), snapshot(2));
    s.listener =
        failure -> {
          assertFalse(result.isDone());
          throw callbackError;
        };
    assertFalse(result.isDone());
    wait.completeExceptionally(cause);
    assertNull(result.get(5, TimeUnit.SECONDS));
    assertEquals(1, s.failures.size());
    assertSame(cause, s.failures.get(0).cause());
    assertEquals(1, s.deletes.size());
  }

  @Test
  void requestStartupAndBackoffFailuresNotifyOnceAndStopDeletion() {
    for (String phase : List.of("query", "delete", "sleep-throw", "sleep-future")) {
      Stub s = new Stub(RetentionPolicy.delete(1));
      IllegalStateException cause = new IllegalStateException(phase);
      s.query =
          request ->
              CompletableFuture.completedFuture(QueryResponse.builder().items(history(1)).build());
      s.delete =
          request ->
              CompletableFuture.completedFuture(
                  BatchWriteItemResponse.builder()
                      .unprocessedItems(request.requestItems())
                      .build());
      if (phase.equals("query"))
        s.query =
            request -> {
              throw cause;
            };
      if (phase.equals("delete"))
        s.delete =
            request -> {
              throw cause;
            };
      s.wait =
          delay -> {
            if (phase.equals("sleep-throw")) throw cause;
            return CompletableFuture.failedFuture(cause);
          };
      assertDoesNotThrow(() -> s.write(true, true, 2));
      assertEquals(1, s.failures.size());
      assertSame(cause, s.failures.get(0).cause());
      assertEquals(phase.equals("query") ? 0 : 1, s.deletes.size());
    }
  }

  @Test
  void lastAllowedRetryWithoutUnprocessedItemsSucceedsWithoutNotification() {
    for (boolean async : List.of(false, true)) {
      Stub s = new Stub(RetentionPolicy.delete(1));
      s.query =
          request ->
              CompletableFuture.completedFuture(QueryResponse.builder().items(history(1)).build());
      s.delete =
          request ->
              CompletableFuture.completedFuture(
                  BatchWriteItemResponse.builder()
                      .unprocessedItems(s.deletes.size() < 11 ? request.requestItems() : Map.of())
                      .build());
      s.write(async, true, 2);
      assertEquals(11, s.deletes.size());
      assertEquals(10, s.waits.size());
      assertTrue(s.failures.isEmpty());
    }
  }

  private static List<Long> numbers(BatchWriteItemRequest request) {
    assertEquals(Set.of("snapshot"), request.requestItems().keySet());
    return request.requestItems().get("snapshot").stream()
        .map(
            write -> {
              assertNull(write.putRequest());
              assertEquals(ID.asString(), write.deleteRequest().key().get("aid").s());
              return Long.parseLong(write.deleteRequest().key().get("skey").n());
            })
        .collect(Collectors.toList());
  }

  private static final class Stub {
    final List<QueryRequest> queries = new ArrayList<>();
    final List<BatchWriteItemRequest> deletes = new ArrayList<>();
    final List<Long> waits = new ArrayList<>();
    final List<RetentionFailure> failures = new ArrayList<>();
    RetentionFailureListener listener = failure -> {};
    DynamoDbTableConfig tables;
    Function<TransactWriteItemsRequest, CompletableFuture<TransactWriteItemsResponse>> commit =
        request -> CompletableFuture.completedFuture(TransactWriteItemsResponse.builder().build());
    Function<QueryRequest, CompletableFuture<QueryResponse>> query =
        request -> CompletableFuture.completedFuture(QueryResponse.builder().build());
    Function<BatchWriteItemRequest, CompletableFuture<BatchWriteItemResponse>> delete =
        request -> CompletableFuture.completedFuture(BatchWriteItemResponse.builder().build());
    Function<Long, CompletableFuture<Void>> wait = delay -> CompletableFuture.completedFuture(null);
    final Sleeper sleeper =
        new Sleeper() {
          public void sleep(long delay) {
            waits.add(delay);
            wait.apply(delay).join();
          }

          public CompletableFuture<Void> sleepAsync(long delay) {
            waits.add(delay);
            return wait.apply(delay);
          }
        };

    Stub(RetentionPolicy policy) {
      tables = DynamoDbTableConfigTest.names().retentionPolicy(policy).build();
    }

    EventStoreConfig<String, String> config() {
      return EventStoreConfig.<String, String>builder()
          .payloadSerializer(JSON)
          .snapshotSerializer(JSON)
          .retentionFailureListener(
              failure -> {
                failures.add(failure);
                listener.onRetentionFailure(failure);
              })
          .build();
    }

    Object sdk(String method, Object[] args) {
      switch (method) {
        case "batchGetItem":
          Map<String, List<Map<String, AttributeValue>>> items = new LinkedHashMap<>();
          ((BatchGetItemRequest) args[0])
              .requestItems()
              .forEach(
                  (table, keys) -> {
                    Map<String, AttributeValue> item = new LinkedHashMap<>(keys.keys().get(0));
                    item.put("store_id", AttributeValue.fromS("unit"));
                    item.put("layout_version", AttributeValue.fromN("1"));
                    items.put(table, List.of(item));
                  });
          return CompletableFuture.completedFuture(
              BatchGetItemResponse.builder().responses(items).build());
        case "transactWriteItems":
          return commit.apply((TransactWriteItemsRequest) args[0]);
        case "query":
          queries.add((QueryRequest) args[0]);
          return sdkCompletion((QueryRequest) args[0], query.apply((QueryRequest) args[0]));
        case "batchWriteItem":
          deletes.add((BatchWriteItemRequest) args[0]);
          return sdkCompletion(
              (BatchWriteItemRequest) args[0], delete.apply((BatchWriteItemRequest) args[0]));
        default:
          throw new AssertionError("Unexpected client call: " + method);
      }
    }

    private <T extends SdkResponse> CompletableFuture<T> sdkCompletion(
        AwsRequest request, CompletableFuture<T> response) {
      var configuration = DynamoDbServiceClientConfiguration.builder();
      configuration.overrideConfiguration(ClientOverrideConfiguration.builder().build());
      request
          .overrideConfiguration()
          .ifPresent(
              overrides ->
                  overrides.plugins().forEach(plugin -> plugin.configureClient(configuration)));
      List<ExecutionInterceptor> interceptors =
          new ArrayList<>(configuration.overrideConfiguration().executionInterceptors());
      Collections.reverse(interceptors);
      CompletableFuture<T> result = new CompletableFuture<>();
      response.whenComplete(
          (value, failure) -> {
            try {
              ExecutionAttributes attributes = new ExecutionAttributes();
              if (failure == null) {
                Context.AfterExecution context =
                    InterceptorContext.builder().request(request).response(value).build();
                interceptors.forEach(
                    interceptor -> interceptor.afterExecution(context, attributes));
                result.complete(value);
              } else {
                Context.FailedExecution context =
                    (Context.FailedExecution)
                        Proxy.newProxyInstance(
                            Context.FailedExecution.class.getClassLoader(),
                            new Class<?>[] {Context.FailedExecution.class},
                            (proxy, method, args) -> {
                              switch (method.getName()) {
                                case "exception":
                                  return failure;
                                case "request":
                                  return request;
                                case "httpRequest":
                                case "httpResponse":
                                case "response":
                                  return Optional.empty();
                                default:
                                  throw new AssertionError(
                                      "Unexpected failure context access: " + method);
                              }
                            });
                interceptors.forEach(
                    interceptor -> interceptor.onExecutionFailure(context, attributes));
                result.completeExceptionally(failure);
              }
            } catch (Throwable hookFailure) {
              result.completeExceptionally(hookFailure);
            }
          });
      return result;
    }

    <T> T client(Class<T> type, boolean async) {
      return type.cast(
          Proxy.newProxyInstance(
              type.getClassLoader(),
              new Class<?>[] {type},
              (proxy, method, args) -> {
                Object response = sdk(method.getName(), args);
                return async ? response : ((CompletableFuture<?>) response).join();
              }));
    }

    AsyncEventStore<String, String> asyncStore() {
      return DynamoDbEventStore.createAsync(
              client(DynamoDbAsyncClient.class, true), tables, config(), sleeper)
          .join();
    }

    void write(boolean async, boolean withSnapshot, long seq) {
      if (async) {
        AsyncEventStore<String, String> store = asyncStore();
        (withSnapshot
                ? store.persistEventAndSnapshot(event(seq), snapshot(seq))
                : store.persistEvent(event(seq)))
            .join();
      } else {
        EventStore<String, String> store =
            DynamoDbEventStore.create(
                client(DynamoDbClient.class, false), tables, config(), sleeper);
        if (withSnapshot) store.persistEventAndSnapshot(event(seq), snapshot(seq));
        else store.persistEvent(event(seq));
      }
    }
  }
}
