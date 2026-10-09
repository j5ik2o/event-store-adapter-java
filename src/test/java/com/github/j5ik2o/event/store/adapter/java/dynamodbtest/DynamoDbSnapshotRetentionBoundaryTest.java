package com.github.j5ik2o.event.store.adapter.java.dynamodbtest;

import static org.junit.jupiter.api.Assertions.*;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.j5ik2o.event.store.adapter.java.core.*;
import com.github.j5ik2o.event.store.adapter.java.dynamodb.*;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;
import java.util.stream.LongStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.awscore.AwsRequest;
import software.amazon.awssdk.awscore.AwsRequestOverrideConfiguration;
import software.amazon.awssdk.core.SdkPlugin;
import software.amazon.awssdk.core.interceptor.Context;
import software.amazon.awssdk.core.interceptor.ExecutionAttributes;
import software.amazon.awssdk.core.interceptor.ExecutionInterceptor;
import software.amazon.awssdk.retries.StandardRetryStrategy;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.*;

class DynamoDbSnapshotRetentionBoundaryTest {
  @RegisterExtension
  static final DynamoDbConfigurationFixture configuration = new DynamoDbConfigurationFixture();

  private final DynamoDbSnapshotRetentionFixture fixture =
      new DynamoDbSnapshotRetentionFixture(configuration);
  private static final AggregateId ID = AggregateId.of("Order", "9");
  private static final AggregateId OTHER = AggregateId.of("Order", "90");
  private static final PayloadSerializer<JsonNode> JSON =
      JsonPayloadSerializer.of(DynamoDbJson.mapper(), JsonNode.class);

  private interface Scenario {
    void run(DynamoDbSnapshotRetentionFixture.Scene scene) throws Exception;
  }

  private Stream<DynamicTest> both(String name, RetentionPolicy policy, Scenario action) {
    return Stream.of(false, true)
        .map(
            async ->
                DynamicTest.dynamicTest(
                    name + (async ? " async" : " sync"),
                    () -> {
                      try (DynamoDbSnapshotRetentionFixture.Scene scene =
                          fixture.scene(name, async, policy)) {
                        action.run(scene);
                      }
                    }));
  }

  private static EventEnvelope<JsonNode> event(AggregateId id, long seq) {
    return EventEnvelope.<JsonNode>builder()
        .aggregateId(id)
        .seqNr(seq)
        .occurredAt(Instant.ofEpochSecond(0, 123456789))
        .manifest("event")
        .payload(DynamoDbJson.object().put("number", seq))
        .build();
  }

  private static SnapshotEnvelope<JsonNode> snapshot(long seq) {
    return SnapshotEnvelope.<JsonNode>builder()
        .seqNr(seq)
        .manifest("snapshot")
        .aggregate(DynamoDbJson.object().put("total", seq))
        .build();
  }

  private static EventStoreConfig<JsonNode, JsonNode> config() {
    return EventStoreConfig.<JsonNode, JsonNode>builder()
        .payloadSerializer(JSON)
        .snapshotSerializer(JSON)
        .build();
  }

  private static AwsRequest observedRequest(AwsRequest request, ExecutionInterceptor observer) {
    SdkPlugin plugin =
        builder ->
            builder.overrideConfiguration(
                builder.overrideConfiguration().toBuilder()
                    .addExecutionInterceptor(observer)
                    .build());
    return ((AwsRequest.Builder) request.toBuilder())
        .overrideConfiguration(
            request
                .overrideConfiguration()
                .map(AwsRequestOverrideConfiguration::toBuilder)
                .orElseGet(AwsRequestOverrideConfiguration::builder)
                .addPlugin(plugin)
                .build())
        .build();
  }

  private static DynamoDbAsyncClient retentionClient(
      DynamoDbAsyncClient client, UnaryOperator<AwsRequest> amend) {
    return (DynamoDbAsyncClient)
        Proxy.newProxyInstance(
            DynamoDbAsyncClient.class.getClassLoader(),
            new Class<?>[] {DynamoDbAsyncClient.class},
            (proxy, method, args) -> {
              Object[] actualArgs = args;
              if (Set.of("query", "batchWriteItem").contains(method.getName())) {
                actualArgs = args.clone();
                actualArgs[0] = amend.apply((AwsRequest) args[0]);
              }
              try {
                return method.invoke(client, actualArgs);
              } catch (InvocationTargetException failure) {
                throw failure.getCause();
              }
            });
  }

  private static final class WarningLogs implements AutoCloseable {
    private final Logger logger =
        (Logger)
            LoggerFactory.getLogger(
                DynamoDbEventStore.class.getPackage().getName() + ".DynamoDbSnapshotRetention");
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();

    WarningLogs() {
      logs.start();
      logger.addAppender(logs);
    }

    long count() {
      return logs.list.stream().filter(log -> log.getLevel() == Level.WARN).count();
    }

    @Override
    public void close() {
      logger.detachAppender(logs);
      logs.stop();
    }
  }

  private static AsyncEventStore<JsonNode, JsonNode> asyncStore(
      DynamoDbSnapshotRetentionFixture.Scene s, DynamoDbAsyncClient client) {
    return s.operate(
        false,
        () ->
            DynamoDbTestFactory.createAsync(
                    client,
                    DynamoDbConfigurationTables.config(s.c)
                        .retentionPolicy(RetentionPolicy.delete(1))
                        .build(),
                    EventStoreConfig.<JsonNode, JsonNode>builder()
                        .payloadSerializer(JSON)
                        .snapshotSerializer(JSON)
                        .retentionFailureListener(
                            failure -> {
                              s.failures.add(failure);
                              s.notificationThreads.add(Thread.currentThread().getName());
                              s.listener.onRetentionFailure(failure);
                            })
                        .build(),
                    delay -> {
                      s.waits.add(delay);
                      return CompletableFuture.completedFuture(null);
                    })
                .join());
  }

  @org.junit.jupiter.api.Test
  void realSdkAfterExecutionProcessesSuccessAndUnprocessedBeforeFutureCompletion() {
    try (DynamoDbSnapshotRetentionFixture.Scene s =
        fixture.scene("sdk-after-execution", true, RetentionPolicy.delete(1))) {
      backlog(s, 2);
      Thread caller = Thread.currentThread();
      var receipts = s.observation.putArray("after_execution");
      List<CompletableFuture<?>> completionChecks = new ArrayList<>();
      DynamoDbAsyncClient observed =
          (DynamoDbAsyncClient)
              Proxy.newProxyInstance(
                  DynamoDbAsyncClient.class.getClassLoader(),
                  new Class<?>[] {DynamoDbAsyncClient.class},
                  (proxy, method, args) -> {
                    boolean retention =
                        Set.of("query", "batchWriteItem").contains(method.getName());
                    Object[] actualArgs = args;
                    CompletableFuture<Void> attached = new CompletableFuture<>();
                    AtomicReference<Thread> hookThread = new AtomicReference<>();
                    ObjectNode receipt = DynamoDbJson.object();
                    if (retention) {
                      actualArgs = args.clone();
                      actualArgs[0] =
                          observedRequest(
                              (AwsRequest) args[0],
                              new ExecutionInterceptor() {
                                @Override
                                public void afterExecution(
                                    Context.AfterExecution context,
                                    ExecutionAttributes attributes) {
                                  hookThread.set(Thread.currentThread());
                                  receipt
                                      .put(
                                          "response_type",
                                          context.response().getClass().getSimpleName())
                                      .put("thread", Thread.currentThread().getName())
                                      .put("caller_thread", caller == Thread.currentThread());
                                  receipt.set("response", DynamoDbJson.sdk(context.response()));
                                  receipts.add(receipt);
                                  attached.join();
                                }
                              });
                    }
                    try {
                      Object response = method.invoke(s.c.async, actualArgs);
                      if (retention)
                        completionChecks.add(
                            ((CompletableFuture<?>) response)
                                .whenComplete(
                                    (value, failure) -> {
                                      assertNull(failure);
                                      receipt
                                          .put(
                                              "same_response_thread",
                                              hookThread.get() == Thread.currentThread())
                                          .put(
                                              "sdk_future_completion_thread",
                                              Thread.currentThread().getName())
                                          .put(
                                              "after_execution_observed_at_sdk_future_completion",
                                              hookThread.get() != null);
                                    }));
                      return response;
                    } catch (InvocationTargetException failure) {
                      throw failure.getCause();
                    } finally {
                      attached.complete(null);
                    }
                  });
      AsyncEventStore<JsonNode, JsonNode> store = asyncStore(s, observed);
      s.register(
          "retention-delete",
          1,
          FaultRegistry.Injection.REPLACE_REQUEST,
          DynamoDbFaultEffects.unprocessedFirst(1));
      s.operate(
          true,
          () -> {
            store.persistEventAndSnapshot(event(ID, 3), snapshot(3)).join();
            return null;
          });
      completionChecks.forEach(CompletableFuture::join);
      assertEquals(3, receipts.size());
      for (JsonNode receipt : receipts) {
        assertTrue(receipt.path("same_response_thread").booleanValue(), receipt.toString());
        assertFalse(receipt.path("caller_thread").booleanValue(), receipt.toString());
        assertTrue(
            receipt.path("after_execution_observed_at_sdk_future_completion").booleanValue());
      }
      assertEquals(
          1, receipts.get(1).path("response").path("UnprocessedItems").path(s.c.snapshot).size());
      assertTrue(s.failures.isEmpty());
      assertHistory(s, List.of(3L));
    } catch (Exception failure) {
      throw new CompletionException(failure);
    }
  }

  /** Backlog comes from actual public writers on the same independent three tables. */
  private static void backlog(DynamoDbSnapshotRetentionFixture.Scene s, int count) {
    DynamoDbTableConfig tables =
        DynamoDbConfigurationTables.config(s.c)
            .retentionPolicy(RetentionPolicy.delete(count))
            .build();
    if (s.asynchronous) {
      AsyncEventStore<JsonNode, JsonNode> writer =
          DynamoDbEventStore.createAsync(s.c.adminAsync, tables, config()).join();
      for (long seq = 1; seq <= count; seq++)
        writer.persistEventAndSnapshot(event(ID, seq), snapshot(seq)).join();
    } else {
      EventStore<JsonNode, JsonNode> writer =
          DynamoDbEventStore.create(s.c.admin, tables, config());
      for (long seq = 1; seq <= count; seq++)
        writer.persistEventAndSnapshot(event(ID, seq), snapshot(seq));
    }
    s.observation.put("history_seed_source", "public writer");
    s.observation.set("history_before_retention", DynamoDbJson.sdk(s.rows(ID)));
    assertEquals(count + 1, s.rows(ID).size());
  }

  private static List<DynamoDbRequestRecorder.Request> phase(
      DynamoDbSnapshotRetentionFixture.Scene s, String phase) {
    return s.lastRequests().stream()
        .filter(r -> r.phase.equals(phase))
        .collect(Collectors.toList());
  }

  private static List<Long> deleteNumbers(
      DynamoDbSnapshotRetentionFixture.Scene s, DynamoDbRequestRecorder.Request request) {
    assertEquals(
        Set.of(s.c.snapshot),
        DynamoDbConfigurationFixture.stringsFromFields(request.original.path("RequestItems")));
    List<Long> result = new ArrayList<>();
    for (JsonNode write : request.original.path("RequestItems").path(s.c.snapshot)) {
      assertEquals(Set.of("DeleteRequest"), DynamoDbConfigurationFixture.stringsFromFields(write));
      assertEquals(ID.asString(), write.at("/DeleteRequest/Key/aid/S").asText());
      result.add(Long.parseLong(write.at("/DeleteRequest/Key/skey/N").asText()));
    }
    return result;
  }

  private static List<Long> range(long first, long last) {
    return LongStream.rangeClosed(first, last).boxed().collect(Collectors.toList());
  }

  private static void assertJson(JsonNode expected, JsonNode actual) {
    assertEquals(
        DynamoDbJson.read(DynamoDbJson.bytes(expected)),
        DynamoDbJson.read(DynamoDbJson.bytes(actual)));
  }

  private static void assertHistory(DynamoDbSnapshotRetentionFixture.Scene s, List<Long> active) {
    assertEquals(DynamoDbJson.sdk(active), s.history(ID).path("active"));
  }

  private static void assertQuery(
      DynamoDbSnapshotRetentionFixture.Scene s, DynamoDbRequestRecorder.Request r) {
    assertEquals("Query", r.api);
    assertEquals(s.c.snapshot, r.original.path("TableName").asText());
    assertEquals(s.c.historyIndex, r.original.path("IndexName").asText());
    assertFalse(r.original.path("ConsistentRead").booleanValue());
    assertFalse(r.original.path("ScanIndexForward").booleanValue());
    JsonNode conditions = r.structure.at("/key_condition/all");
    assertEquals(1, conditions.size());
    assertEquals("aid", conditions.get(0).path("attribute").asText());
    assertEquals("eq", conditions.get(0).path("operator").asText());
    assertEquals(ID.asString(), conditions.get(0).at("/argument/S").asText());
    assertEquals(r.marshalled, r.transmitted);
    assertEquals(1, r.httpAttempts);
  }

  @TestFactory
  Stream<DynamicTest>
      actualSparseGsiKeepsNewestNAndProtectsMarkedCurrentConfigurationOtherAidHeadAndJournal() {
    return both(
        "sparse-gsi-protection",
        RetentionPolicy.delete(2),
        s -> {
          Map<String, Map<String, AttributeValue>> configurationBefore =
              DynamoDbConfigurationFixture.stored(s.c);
          s.write(event(ID, 1), snapshot(1));
          Map<String, AttributeValue> marked = new LinkedHashMap<>(s.stored("snapshot", ID, 1));
          marked.remove("active_history_seq_nr");
          marked.put(
              "ttl",
              software.amazon.awssdk.services.dynamodb.model.AttributeValue.fromN("4102444800"));
          s.c.admin.putItem(PutItemRequest.builder().tableName(s.c.snapshot).item(marked).build());
          s.observation.set("artificial_mark_before_retention", DynamoDbJson.sdk(marked));
          s.write(event(OTHER, 1), snapshot(1));
          Map<String, AttributeValue> otherBefore = s.stored("snapshot", OTHER, 1);
          for (long seq = 2; seq <= 6; seq++) {
            s.write(event(ID, seq), snapshot(seq));
            phase(s, "retention-query").forEach(r -> assertQuery(s, r));
          }
          assertHistory(s, List.of(5L, 6L));
          assertEquals(marked, s.stored("snapshot", ID, 1));
          assertEquals(otherBefore, s.stored("snapshot", OTHER, 1));
          assertEquals(configurationBefore, DynamoDbConfigurationFixture.stored(s.c));
          assertEquals("6", s.stored("head", ID, 0).get("seq_nr").n());
          assertEquals("6", s.stored("snapshot", ID, 0).get("seq_nr").n());
          assertJson(
              snapshot(6).aggregate(),
              JSON.deserialize(s.stored("snapshot", ID, 0).get("payload").b().asByteArray()));
          for (long seq = 1; seq <= 6; seq++)
            assertJson(
                event(ID, seq).payload(),
                JSON.deserialize(s.stored("journal", ID, seq).get("payload").b().asByteArray()));
          assertTrue(s.failures.isEmpty());
          s.last().set("stored_marked", DynamoDbJson.sdk(s.stored("snapshot", ID, 1)));
          s.last().set("stored_other_aid", DynamoDbJson.sdk(s.stored("snapshot", OTHER, 1)));
          s.last()
              .set(
                  "configuration_after",
                  DynamoDbJson.sdk(DynamoDbConfigurationFixture.stored(s.c)));
        });
  }

  @TestFactory
  Stream<DynamicTest>
      allPagesDuplicateKeysAndMissingCommittedHistoryUseActualBacklogAndRetryOnlyUnprocessed() {
    return both(
        "pages-duplicate-missing-new",
        RetentionPolicy.delete(1),
        s -> {
          backlog(s, 30);
          List<Long> high = new ArrayList<>(range(16, 30)), low = new ArrayList<>(range(1, 16));
          Collections.reverse(high);
          Collections.reverse(low);
          s.register(
              "retention-query",
              1,
              FaultRegistry.Injection.REPLACE_RESPONSE,
              DynamoDbFaultEffects.historyPages(
                  s.c.admin, s.c.snapshot, ID.asString(), List.of(high, low), 31L, true));
          s.register(
              "retention-delete",
              1,
              FaultRegistry.Injection.REPLACE_REQUEST,
              DynamoDbFaultEffects.unprocessedFirst(2));
          s.write(event(ID, 31), snapshot(31));
          List<DynamoDbRequestRecorder.Request> queries = phase(s, "retention-query"),
              deletes = phase(s, "retention-delete");
          assertEquals(2, queries.size());
          queries.forEach(r -> assertQuery(s, r));
          assertEquals(
              queries.get(0).effectiveResponse.path("LastEvaluatedKey"),
              queries.get(1).original.path("ExclusiveStartKey"));
          assertTrue(
              queries.get(0).originalResponse.path("Items").size()
                  > queries.get(0).effectiveResponse.path("Items").size());
          assertEquals(List.of(25, 5), s.initialBatchSizes());
          assertEquals(3, deletes.size());
          assertEquals(
              deleteNumbers(s, deletes.get(0)).subList(0, 2), deleteNumbers(s, deletes.get(1)));
          assertEquals(List.of(50L), s.waits);
          assertEquals(3, deletes.stream().mapToInt(r -> r.transmissions).sum());
          assertEquals(1, s.last().at("/faults/0/applications").intValue());
          assertEquals(1, s.last().at("/faults/1/applications").intValue());
          assertHistory(s, List.of(31L));
          for (long seq = 1; seq <= 30; seq++) assertTrue(s.stored("snapshot", ID, seq).isEmpty());
          assertEquals("31", s.stored("snapshot", ID, 0).get("seq_nr").n());
          s.last().set("stored_current", DynamoDbJson.sdk(s.stored("snapshot", ID, 0)));
        });
  }

  @TestFactory
  Stream<DynamicTest> initialDeletesRespect25ItemBoundary() {
    return Stream.of(25, 26, 51)
        .flatMap(
            count ->
                both(
                    "batch-boundary-" + count,
                    RetentionPolicy.delete(1),
                    s -> {
                      backlog(s, count);
                      s.write(event(ID, count + 1), snapshot(count + 1));
                      List<Integer> expected =
                          count == 25
                              ? List.of(25)
                              : count == 26 ? List.of(25, 1) : List.of(25, 25, 1);
                      assertEquals(expected, s.initialBatchSizes());
                      for (DynamoDbRequestRecorder.Request r : phase(s, "retention-delete")) {
                        assertTrue(deleteNumbers(s, r).size() <= 25);
                        assertEquals(1, r.transmissions);
                      }
                      assertHistory(s, List.of((long) count + 1));
                      assertTrue(s.failures.isEmpty());
                    }));
  }

  @TestFactory
  Stream<DynamicTest>
      unprocessedSuccessAndLimitObserveActualSendsRemainingRowsAndNextWriteRecovery() {
    return Stream.of(false, true)
        .flatMap(
            exhausted ->
                both(
                    "unprocessed-" + (exhausted ? "limit" : "success"),
                    RetentionPolicy.delete(1),
                    s -> {
                      backlog(s, 30);
                      AtomicReference<Thread> sdkThread = new AtomicReference<>(),
                          notifiedThread = new AtomicReference<>();
                      CompletableFuture<CompletableFuture<Void>> published =
                          new CompletableFuture<>();
                      var completions = s.observation.putArray("delete_completions");
                      ExecutionInterceptor observer =
                          new ExecutionInterceptor() {
                            @Override
                            public void afterExecution(
                                Context.AfterExecution context, ExecutionAttributes attributes) {
                              if (context.response() instanceof BatchWriteItemResponse) {
                                sdkThread.set(Thread.currentThread());
                                completions
                                    .addObject()
                                    .put("thread", Thread.currentThread().getName())
                                    .set("response", DynamoDbJson.sdk(context.response()));
                              }
                            }
                          };
                      AsyncEventStore<JsonNode, JsonNode> store =
                          s.asynchronous
                              ? asyncStore(
                                  s,
                                  retentionClient(
                                      s.c.async, request -> observedRequest(request, observer)))
                              : null;
                      Thread caller = Thread.currentThread();
                      s.listener =
                          failure -> {
                            notifiedThread.set(Thread.currentThread());
                            assertEquals(ID, failure.aggregateId());
                            assertEquals(RetentionMode.DELETE, failure.mode());
                            if (s.asynchronous) {
                              assertSame(sdkThread.get(), Thread.currentThread());
                              try {
                                assertFalse(published.get(10, TimeUnit.SECONDS).isDone());
                              } catch (Exception error) {
                                throw new CompletionException(error);
                              }
                              s.observation.put("public_future_pending_at_notification", true);
                            } else assertSame(caller, Thread.currentThread());
                            throw new IllegalStateException("listener failure");
                          };
                      try (WarningLogs logs = new WarningLogs()) {
                        s.register(
                            "retention-delete",
                            exhausted ? -1 : 1,
                            FaultRegistry.Injection.REPLACE_REQUEST,
                            DynamoDbFaultEffects.unprocessedFirst(2));
                        s.operate(
                            true,
                            () -> {
                              if (s.asynchronous) {
                                CompletableFuture<Void> result =
                                    store.persistEventAndSnapshot(event(ID, 31), snapshot(31));
                                published.complete(result);
                                assertNull(result.join());
                              } else s.persist(event(ID, 31), snapshot(31));
                              return null;
                            });
                        List<DynamoDbRequestRecorder.Request> deletes =
                            phase(s, "retention-delete");
                        assertEquals(exhausted ? 11 : 3, deletes.size());
                        assertEquals(
                            exhausted ? 1 : 3,
                            deletes.stream().mapToInt(r -> r.transmissions).sum());
                        assertEquals(
                            exhausted ? 11 : 1, s.last().at("/faults/0/applications").intValue());
                        assertEquals(
                            exhausted
                                ? List.of(
                                    50L, 100L, 200L, 400L, 800L, 1000L, 1000L, 1000L, 1000L, 1000L)
                                : List.of(50L),
                            s.waits);
                        assertEquals(
                            exhausted ? List.of(25) : List.of(25, 5), s.initialBatchSizes());
                        if (exhausted) {
                          assertEquals(1, s.failures.size());
                          assertInstanceOf(StorageException.class, s.failures.get(0).cause());
                          assertEquals(1, logs.count());
                          if (s.asynchronous) {
                            assertSame(sdkThread.get(), notifiedThread.get());
                            assertEquals(11, completions.size());
                            s.observation.put("same_response_notification_thread", true);
                          }
                          List<Long> expected = new ArrayList<>(range(1, 5));
                          expected.addAll(List.of(29L, 30L, 31L));
                          assertHistory(s, expected);
                          for (long seq = 6; seq <= 28; seq++)
                            assertTrue(s.stored("snapshot", ID, seq).isEmpty());
                          s.operate(
                              true,
                              () -> {
                                if (s.asynchronous)
                                  store.persistEventAndSnapshot(event(ID, 32), snapshot(32)).join();
                                else s.persist(event(ID, 32), snapshot(32));
                                return null;
                              });
                          assertHistory(s, List.of(32L));
                          assertTrue(s.failures.isEmpty());
                        } else {
                          assertHistory(s, List.of(31L));
                          assertTrue(s.failures.isEmpty());
                          assertEquals(0, logs.count());
                        }
                        s.observation.put("warn_count", logs.count());
                      }
                    }));
  }

  @TestFactory
  Stream<DynamicTest>
      postCommitQueryAndDeleteFailuresNotifyOnceOnCorrectThreadAndKeepWriteSuccess() {
    return Stream.of("retention-query", "retention-delete")
        .flatMap(
            phase ->
                Stream.of(false, true)
                    .flatMap(
                        callbackThrows ->
                            both(
                                "failure-" + phase + "-callback-" + callbackThrows,
                                RetentionPolicy.delete(1),
                                s -> {
                                  s.write(event(ID, 1), snapshot(1));
                                  Thread caller = Thread.currentThread();
                                  s.listener =
                                      failure -> {
                                        assertEquals(ID, failure.aggregateId());
                                        assertEquals(RetentionMode.DELETE, failure.mode());
                                        if (s.asynchronous)
                                          assertTrue(
                                              Thread.currentThread()
                                                  .getName()
                                                  .startsWith("sdk-async-response"),
                                              Thread.currentThread().getName());
                                        else assertSame(caller, Thread.currentThread());
                                        if (callbackThrows)
                                          throw new IllegalStateException("listener failure");
                                      };
                                  s.register(
                                      phase,
                                      1,
                                      FaultRegistry.Injection.REPLACE_REQUEST,
                                      DynamoDbFaultEffects.sdkError("InternalServerError"));
                                  assertDoesNotThrow(() -> s.write(event(ID, 2), snapshot(2)));
                                  assertEquals(1, s.failures.size());
                                  DynamoDbRequestRecorder.Request failed = phase(s, phase).get(0);
                                  assertSame(failed.failure, s.failures.get(0).cause());
                                  assertEquals(0, failed.transmissions);
                                  assertEquals("2", s.stored("head", ID, 0).get("seq_nr").n());
                                  assertEquals("2", s.stored("snapshot", ID, 0).get("seq_nr").n());
                                  assertFalse(s.stored("journal", ID, 2).isEmpty());
                                  assertHistory(s, List.of(1L, 2L));
                                  s.last()
                                      .set(
                                          "stored_head", DynamoDbJson.sdk(s.stored("head", ID, 0)));
                                  s.last()
                                      .set(
                                          "stored_current",
                                          DynamoDbJson.sdk(s.stored("snapshot", ID, 0)));
                                  s.write(event(ID, 3), snapshot(3));
                                  assertTrue(s.failures.isEmpty());
                                  assertHistory(s, List.of(3L));
                                })));
  }

  @TestFactory
  Stream<DynamicTest> realSdkCompletedAndPendingFuturesKeepNotificationOnResponseThread() {
    return Stream.of("retention-query", "retention-delete")
        .flatMap(
            phase ->
                Stream.of(false, true)
                    .flatMap(
                        completed ->
                            (completed ? Stream.of(false, true) : Stream.of(false))
                                .map(
                                    gateCommit ->
                                        DynamicTest.dynamicTest(
                                            "real SDK "
                                                + phase
                                                + (completed ? " completed" : " pending")
                                                + (gateCommit ? " commit gate" : ""),
                                            () -> {
                                              try (DynamoDbSnapshotRetentionFixture.Scene s =
                                                      fixture.scene(
                                                          "sdk-future-timing-"
                                                              + phase
                                                              + "-"
                                                              + completed
                                                              + (gateCommit ? "-commit-gate" : ""),
                                                          true,
                                                          RetentionPolicy.delete(1));
                                                  WarningLogs logs = new WarningLogs()) {
                                                s.write(event(ID, 1), snapshot(1));
                                                Thread caller = Thread.currentThread();
                                                AtomicReference<Thread>
                                                    sdkThread = new AtomicReference<>(),
                                                    notificationThread = new AtomicReference<>();
                                                AtomicReference<Throwable> sdkFailure =
                                                    new AtomicReference<>();
                                                AtomicReference<CompletableFuture<Void>>
                                                    publicResult = new AtomicReference<>();
                                                CompletableFuture<Void>
                                                    commitEntered = new CompletableFuture<>(),
                                                    commitRelease = new CompletableFuture<>(),
                                                    retentionEntered = new CompletableFuture<>(),
                                                    retentionRelease = new CompletableFuture<>(),
                                                    notificationEntered = new CompletableFuture<>(),
                                                    notificationRelease = new CompletableFuture<>();
                                                var invocations =
                                                    s.observation.putArray("sdk_invocations");
                                                ExecutionInterceptor observer =
                                                    new ExecutionInterceptor() {
                                                      @Override
                                                      public void onExecutionFailure(
                                                          Context.FailedExecution context,
                                                          ExecutionAttributes attributes) {
                                                        sdkThread.set(Thread.currentThread());
                                                        sdkFailure.set(context.exception());
                                                      }
                                                    };
                                                DynamoDbAsyncClient controlled =
                                                    (DynamoDbAsyncClient)
                                                        Proxy.newProxyInstance(
                                                            DynamoDbAsyncClient.class
                                                                .getClassLoader(),
                                                            new Class<?>[] {
                                                              DynamoDbAsyncClient.class
                                                            },
                                                            (proxy, method, args) -> {
                                                              boolean retentionOrCommit =
                                                                  Set.of(
                                                                          "transactWriteItems",
                                                                          "query",
                                                                          "batchWriteItem")
                                                                      .contains(method.getName());
                                                              Object[] actualArgs = args;
                                                              if (retentionOrCommit) {
                                                                actualArgs = args.clone();
                                                                actualArgs[0] =
                                                                    observedRequest(
                                                                        (AwsRequest) args[0],
                                                                        observer);
                                                              }
                                                              Object response;
                                                              try {
                                                                response =
                                                                    method.invoke(
                                                                        s.c.async, actualArgs);
                                                              } catch (
                                                                  InvocationTargetException
                                                                      failure) {
                                                                throw failure.getCause();
                                                              }
                                                              if (retentionOrCommit) {
                                                                CompletableFuture<?> actual =
                                                                    (CompletableFuture<?>) response;
                                                                var receipt =
                                                                    DynamoDbJson.object()
                                                                        .put(
                                                                            "method",
                                                                            method.getName())
                                                                        .put(
                                                                            "done_at_sdk_return",
                                                                            actual.isDone());
                                                                // Return the original SDK future.
                                                                // The commit gate additionally
                                                                // makes the public future available
                                                                // while completed retention
                                                                // requests are handled on a
                                                                // different SDK completion thread.
                                                                if (completed
                                                                    && !(gateCommit
                                                                        && method
                                                                            .getName()
                                                                            .equals(
                                                                                "transactWriteItems")))
                                                                  actual
                                                                      .handle(
                                                                          (value, failure) -> null)
                                                                      .get(10, TimeUnit.SECONDS);
                                                                receipt.put(
                                                                    "done_before_store_continuation",
                                                                    actual.isDone());
                                                                synchronized (invocations) {
                                                                  invocations.add(receipt);
                                                                }
                                                              }
                                                              return response;
                                                            });
                                                AsyncEventStore<JsonNode, JsonNode> store =
                                                    asyncStore(s, controlled);
                                                s.listener =
                                                    failure -> {
                                                      notificationThread.set(
                                                          Thread.currentThread());
                                                      CompletableFuture<Void> result =
                                                          publicResult.get();
                                                      s.observation.put(
                                                          "public_future_available_at_notification",
                                                          result != null);
                                                      if (result != null)
                                                        s.observation.put(
                                                            "public_future_pending_at_notification",
                                                            !result.isDone());
                                                      notificationEntered.complete(null);
                                                      if (!completed || gateCommit)
                                                        notificationRelease.join();
                                                      throw new IllegalStateException(
                                                          "listener failure");
                                                    };
                                                if (gateCommit)
                                                  s.register(
                                                      "commit",
                                                      1,
                                                      FaultRegistry.Injection.REPLACE_RESPONSE,
                                                      DynamoDbFaultEffects.response(
                                                          response -> {
                                                            s.observation.set(
                                                                "actual_commit_response_before_gate",
                                                                DynamoDbJson.sdk(response));
                                                            commitEntered.complete(null);
                                                            commitRelease.join();
                                                            return response;
                                                          }));
                                                s.register(
                                                    phase,
                                                    1,
                                                    FaultRegistry.Injection.REPLACE_RESPONSE,
                                                    DynamoDbFaultEffects.response(
                                                        response -> {
                                                          s.observation.set(
                                                              "actual_sdk_response_before_fault",
                                                              DynamoDbJson.sdk(response));
                                                          if (!completed) {
                                                            retentionEntered.complete(null);
                                                            retentionRelease.join();
                                                          }
                                                          throw DynamoDbException.builder()
                                                              .statusCode(500)
                                                              .message("retention failure")
                                                              .build();
                                                        }));
                                                s.operate(
                                                    true,
                                                    () -> {
                                                      CompletableFuture<Void> result =
                                                          store.persistEventAndSnapshot(
                                                              event(ID, 2), snapshot(2));
                                                      publicResult.set(result);
                                                      try {
                                                        if (gateCommit) {
                                                          commitEntered.get(10, TimeUnit.SECONDS);
                                                          assertFalse(result.isDone());
                                                          commitRelease.complete(null);
                                                        }
                                                        if (!completed) {
                                                          retentionEntered.get(
                                                              10, TimeUnit.SECONDS);
                                                          assertFalse(result.isDone());
                                                          s.observation.put(
                                                              "public_future_pending_before_release",
                                                              true);
                                                          retentionRelease.complete(null);
                                                        }
                                                        if (!completed || gateCommit) {
                                                          notificationEntered.get(
                                                              10, TimeUnit.SECONDS);
                                                          assertFalse(result.isDone());
                                                          s.observation.put(
                                                              "public_future_pending_while_listener_blocked",
                                                              true);
                                                          notificationRelease.complete(null);
                                                        }
                                                        assertNull(
                                                            result.get(10, TimeUnit.SECONDS));
                                                        return null;
                                                      } catch (Exception failure) {
                                                        throw new CompletionException(failure);
                                                      } finally {
                                                        commitRelease.complete(null);
                                                        retentionRelease.complete(null);
                                                        notificationRelease.complete(null);
                                                      }
                                                    });
                                                s.observation
                                                    .put(
                                                        "sdk_failure_thread",
                                                        sdkThread.get().getName())
                                                    .put(
                                                        "notification_thread",
                                                        notificationThread.get().getName())
                                                    .put(
                                                        "same_response_notification_thread",
                                                        sdkThread.get() == notificationThread.get())
                                                    .put("public_result", "success")
                                                    .put("warn_count", logs.count());
                                                assertEquals(1, s.failures.size());
                                                assertEquals(1, logs.count());
                                                assertSame(
                                                    sdkFailure.get(), s.failures.get(0).cause());
                                                assertEquals(ID, s.failures.get(0).aggregateId());
                                                assertEquals(
                                                    RetentionMode.DELETE, s.failures.get(0).mode());
                                                assertSame(
                                                    sdkThread.get(), notificationThread.get());
                                                assertNotSame(caller, notificationThread.get());
                                                Map<String, AttributeValue>
                                                    head = s.stored("head", ID, 0),
                                                    current = s.stored("snapshot", ID, 0),
                                                    journal = s.stored("journal", ID, 2);
                                                s.observation.set(
                                                    "stored_head", DynamoDbJson.sdk(head));
                                                s.observation.set(
                                                    "stored_current", DynamoDbJson.sdk(current));
                                                s.observation.set(
                                                    "stored_journal", DynamoDbJson.sdk(journal));
                                                assertEquals("2", head.get("seq_nr").n());
                                                assertEquals("2", current.get("seq_nr").n());
                                                assertEquals("2", journal.get("seq_nr").n());
                                                if (!completed || gateCommit) {
                                                  assertTrue(
                                                      s.observation
                                                          .path(
                                                              "public_future_pending_at_notification")
                                                          .booleanValue());
                                                  assertTrue(
                                                      s.observation
                                                          .path(
                                                              "public_future_pending_while_listener_blocked")
                                                          .booleanValue());
                                                }
                                                for (JsonNode invocation : invocations) {
                                                  String method =
                                                      invocation.path("method").asText();
                                                  if (completed
                                                      && !(gateCommit
                                                          && method.equals("transactWriteItems")))
                                                    assertTrue(
                                                        invocation
                                                            .path("done_before_store_continuation")
                                                            .booleanValue());
                                                  if (!completed
                                                      && method.equals(
                                                          phase.equals("retention-query")
                                                              ? "query"
                                                              : "batchWriteItem"))
                                                    assertFalse(
                                                        invocation
                                                            .path("done_before_store_continuation")
                                                            .booleanValue());
                                                }
                                              }
                                            }))));
  }

  @TestFactory
  Stream<DynamicTest> sdkRetrySuccessDoesNotNotifyAnIntermediateFailure() {
    return Stream.of("retention-query", "retention-delete")
        .map(
            phase ->
                DynamicTest.dynamicTest(
                    "SDK retry success " + phase,
                    () -> {
                      try (DynamoDbSnapshotRetentionFixture.Scene s =
                              fixture.scene(
                                  "sdk-retry-success-" + phase, true, RetentionPolicy.delete(1));
                          WarningLogs logs = new WarningLogs()) {
                        s.write(event(ID, 1), snapshot(1));
                        AtomicInteger finalFailures = new AtomicInteger(),
                            errorAttempts = new AtomicInteger();
                        ExecutionInterceptor observer =
                            new ExecutionInterceptor() {
                              @Override
                              public void onExecutionFailure(
                                  Context.FailedExecution context, ExecutionAttributes attributes) {
                                finalFailures.incrementAndGet();
                              }
                            };
                        // Retry strategy is a client setting in SDK 2.55.13. Only this test's
                        // additional client retries; the accepted shared client settings stay
                        // intact.
                        try (FaultAsyncHttpClient http =
                                new FaultAsyncHttpClient(
                                    DynamoDbTestClients.asyncHttp(configuration.eventLoop())
                                        .build(),
                                    s.c.recorder);
                            DynamoDbAsyncClient client =
                                DynamoDbAsyncClient.builder()
                                    .endpointOverride(configuration.endpoint())
                                    .region(DynamoDbTestClients.REGION)
                                    .credentialsProvider(
                                        software.amazon.awssdk.auth.credentials
                                            .StaticCredentialsProvider.create(
                                            software.amazon.awssdk.auth.credentials
                                                .AwsBasicCredentials.create(
                                                DynamoDbTestClients.ACCESS_KEY,
                                                "DynamoDbLocalDummySecret")))
                                    .overrideConfiguration(
                                        DynamoDbTestClients.overrides()
                                            .retryStrategy(
                                                StandardRetryStrategy.builder()
                                                    .maxAttempts(2)
                                                    .build())
                                            .addExecutionInterceptor(observer)
                                            .addExecutionInterceptor(s.c.recorder)
                                            .build())
                                    .httpClient(http)
                                    .build()) {
                          AsyncEventStore<JsonNode, JsonNode> store = asyncStore(s, client);
                          FaultRegistry.Effect error =
                              DynamoDbFaultEffects.sdkError("InternalServerError");
                          s.register(
                              phase,
                              1,
                              FaultRegistry.Injection.REPLACE_REQUEST,
                              new FaultRegistry.Effect() {
                                @Override
                                public boolean supports(FaultRegistry.Injection injection) {
                                  return error.supports(injection);
                                }

                                @Override
                                public HttpReply reply(DynamoDbRequestRecorder.Request request) {
                                  return errorAttempts.getAndIncrement() == 0
                                      ? error.reply(request)
                                      : null;
                                }
                              });
                          s.operate(
                              true,
                              () -> {
                                assertNull(
                                    store
                                        .persistEventAndSnapshot(event(ID, 2), snapshot(2))
                                        .join());
                                return null;
                              });
                          List<DynamoDbRequestRecorder.Request> requests = phase(s, phase);
                          assertEquals(1, requests.size());
                          assertEquals(2, requests.get(0).httpAttempts);
                          assertEquals(1, requests.get(0).transmissions);
                          assertEquals(2, errorAttempts.get());
                          assertEquals(0, finalFailures.get());
                          assertTrue(s.failures.isEmpty());
                          assertEquals(0, logs.count());
                          assertHistory(s, List.of(2L));
                          s.observation
                              .put("sdk_final_failure_count", finalFailures.get())
                              .put("http_attempts", errorAttempts.get())
                              .put("warn_count", logs.count());
                        }
                        assertFalse(configuration.eventLoop().eventLoopGroup().isTerminated());
                        s.observation
                            .put("additional_sdk_client_and_http_pool_close_completed", true)
                            .put("borrowed_event_loop_retained", true);
                        s.write(event(ID, 3), snapshot(3));
                        assertHistory(s, List.of(3L));
                      }
                    }));
  }

  @TestFactory
  Stream<DynamicTest> uncommittedWriteAndEventOnlyNeverSendRetentionRequests() {
    return both(
        "commit-failure-event-only",
        RetentionPolicy.delete(1),
        s -> {
          s.register(
              "commit",
              1,
              FaultRegistry.Injection.REPLACE_REQUEST,
              DynamoDbFaultEffects.sdkError("InternalServerError"));
          assertThrows(RuntimeException.class, () -> s.write(event(ID, 1), snapshot(1)));
          assertEquals(1, s.lastRequests().size());
          assertTrue(phase(s, "retention-query").isEmpty());
          assertTrue(s.stored("head", ID, 0).isEmpty());
          assertTrue(s.stored("snapshot", ID, 1).isEmpty());
          assertTrue(s.failures.isEmpty());
          s.write(event(ID, 1), snapshot(1));
          Map<String, AttributeValue> before = s.stored("snapshot", ID, 1);
          s.write(event(ID, 2), null);
          assertEquals(1, s.lastRequests().size());
          assertEquals(before, s.stored("snapshot", ID, 1));
        });
  }

  @TestFactory
  Stream<DynamicTest> realPendingRetentionHoldsBothApiResultsUntilSdkRequestTerminates() {
    return both(
        "pending-retention",
        RetentionPolicy.delete(1),
        s -> {
          CompletableFuture<Void> entered = new CompletableFuture<>(),
              release = new CompletableFuture<>();
          s.register(
              "retention-query",
              1,
              FaultRegistry.Injection.REPLACE_RESPONSE,
              DynamoDbFaultEffects.response(
                  response -> {
                    s.observation.set("actual_pending_sdk_response", DynamoDbJson.sdk(response));
                    entered.complete(null);
                    release.join();
                    return response;
                  }));
          FaultRegistry.Operation operation = s.c.faults.begin(s.nextOperation++, true);
          ExecutorService caller = Executors.newSingleThreadExecutor();
          CompletableFuture<Void> result =
              s.asynchronous
                  ? s.asyncStore.persistEventAndSnapshot(event(ID, 1), snapshot(1))
                  : CompletableFuture.runAsync(
                      () -> s.syncStore.persistEventAndSnapshot(event(ID, 1), snapshot(1)), caller);
          try {
            entered.get(10, TimeUnit.SECONDS);
            assertFalse(result.isDone());
            assertFalse(s.c.recorder.requestsFinished(operation).isDone());
            assertEquals(1, s.c.faults.pending(operation));
            assertFalse(s.c.closed());
            assertFalse(s.stored("snapshot", ID, 1).isEmpty());
            s.observation
                .put("api_pending_before_release", !result.isDone())
                .put("request_pending_before_release", s.c.faults.pending(operation));
          } finally {
            release.complete(null);
            result.get(10, TimeUnit.SECONDS);
            s.finish(operation, null);
            caller.shutdown();
            assertTrue(caller.awaitTermination(5, TimeUnit.SECONDS));
          }
          s.write(event(ID, 2), null);
          assertEquals("2", s.stored("head", ID, 0).get("seq_nr").n());
        });
  }
}
