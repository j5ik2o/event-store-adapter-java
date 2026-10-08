package com.github.j5ik2o.event.store.adapter.java.dynamodbtest;

import static com.github.j5ik2o.event.store.adapter.java.dynamodbtest.DynamoDbFaultFoundationTest.*;
import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import software.amazon.awssdk.core.interceptor.Context;
import software.amazon.awssdk.core.interceptor.ExecutionAttributes;
import software.amazon.awssdk.core.interceptor.ExecutionInterceptor;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.*;

class DynamoDbResponsePlansTest {
  @RegisterExtension static final DynamoDbLocalExtension local = new DynamoDbLocalExtension();
  private static final String AID = "User-A";

  @Test
  void unprocessedBatchWritePreparationsKeepTheirOwnExclusionsAndEmptyState() {
    WriteRequest firstWrite =
        WriteRequest.builder()
            .deleteRequest(DeleteRequest.builder().key(key("skey", 1)).build())
            .build();
    WriteRequest secondWrite =
        WriteRequest.builder()
            .deleteRequest(DeleteRequest.builder().key(key("skey", 2)).build())
            .build();
    DynamoDbFaultEffects.UnprocessedBatchWrite plan =
        (DynamoDbFaultEffects.UnprocessedBatchWrite) DynamoDbFaultEffects.unprocessedFirst(1);
    DynamoDbFaultEffects.UnprocessedBatchWrite first = plan.forRequest();
    DynamoDbFaultEffects.UnprocessedBatchWrite second = plan.forRequest();
    BatchWriteItemRequest firstRequest =
        BatchWriteItemRequest.builder()
            .requestItems(Map.of("snapshot", List.of(firstWrite)))
            .build();
    BatchWriteItemRequest secondRequest =
        BatchWriteItemRequest.builder()
            .requestItems(Map.of("snapshot", List.of(secondWrite, firstWrite)))
            .build();
    assertEquals(
        firstRequest, first.prepare(firstRequest, FaultRegistry.Injection.REPLACE_REQUEST));
    assertEquals(
        Map.of("snapshot", List.of(firstWrite)),
        ((BatchWriteItemRequest)
                second.prepare(secondRequest, FaultRegistry.Injection.REPLACE_REQUEST))
            .requestItems());
    assertEquals(
        DynamoDbJson.sdk(Map.of("snapshot", List.of(firstWrite))),
        DynamoDbJson.read(first.reply(null).body()).path("UnprocessedItems"));
    assertNull(second.reply(null));
    BatchWriteItemResponse service = BatchWriteItemResponse.builder().build();
    assertEquals(
        Map.of("snapshot", List.of(secondWrite)),
        ((BatchWriteItemResponse) second.afterResponse(null, service)).unprocessedItems());
    BatchWriteItemResponse skipped =
        BatchWriteItemResponse.builder()
            .unprocessedItems(Map.of("snapshot", List.of(firstWrite)))
            .build();
    assertEquals(skipped, first.afterResponse(null, skipped));
    assertTrue(service.unprocessedItems().isEmpty());
  }

  @Test
  void asyncAllThenPartialBatchWriteKeepsEachRequestsUnprocessedItems() throws Exception {
    assertOverlappingBatchWrite(true);
  }

  @Test
  void asyncPartialThenAllBatchWriteKeepsEachRequestsUnprocessedItems() throws Exception {
    assertOverlappingBatchWrite(false);
  }

  private static void assertOverlappingBatchWrite(boolean firstAll) throws Exception {
    try (DynamoDbTestContext context = new DynamoDbTestContext(local.endpoint())) {
      context.createMinimalTables();
      for (int seq = 1; seq <= 4; seq++)
        context.admin.putItem(
            PutItemRequest.builder().tableName(context.snapshot).item(item("skey", seq)).build());
      BatchWriteItemRequest firstRequest = firstAll ? deletes(context, 1) : deletes(context, 1, 2);
      BatchWriteItemRequest secondRequest = firstAll ? deletes(context, 3, 4) : deletes(context, 3);
      FaultRegistry.Fault fault =
          context.faults.register(
              1,
              "retention-delete",
              2,
              FaultRegistry.Injection.REPLACE_REQUEST,
              DynamoDbFaultEffects.unprocessedFirst(1));
      CountDownLatch firstPrepared = new CountDownLatch(1);
      CountDownLatch secondPrepared = new CountDownLatch(1);
      CountDownLatch releaseFirst = new CountDownLatch(1);
      CountDownLatch releaseSecond = new CountDownLatch(1);
      java.util.concurrent.atomic.AtomicInteger prepared =
          new java.util.concurrent.atomic.AtomicInteger();
      ExecutionInterceptor gate =
          new ExecutionInterceptor() {
            @Override
            public void beforeTransmission(
                Context.BeforeTransmission actual, ExecutionAttributes attrs) {
              int index = prepared.getAndIncrement();
              if (index > 1) return;
              System.out.println("BatchWrite prepared " + index + " " + actual.request());
              (index == 0 ? firstPrepared : secondPrepared).countDown();
              await(index == 0 ? releaseFirst : releaseSecond);
            }
          };
      ExecutorService callers = Executors.newFixedThreadPool(2);
      FaultRegistry.Operation operation = context.faults.begin(1, true);
      try (FaultAsyncHttpClient http =
              new FaultAsyncHttpClient(DynamoDbTestClients.asyncHttp().build(), context.recorder);
          DynamoDbAsyncClient client =
              DynamoDbTestClients.observedAsync(local.endpoint(), context.recorder, http, gate)) {
        try {
          CompletableFuture<BatchWriteItemResponse> first =
              CompletableFuture.supplyAsync(
                  () -> client.batchWriteItem(firstRequest).join(), callers);
          assertTrue(firstPrepared.await(10, TimeUnit.SECONDS), "first prepare completed");
          CompletableFuture<BatchWriteItemResponse> second =
              CompletableFuture.supplyAsync(
                  () -> client.batchWriteItem(secondRequest).join(), callers);
          assertTrue(secondPrepared.await(10, TimeUnit.SECONDS), "second prepare completed");
          assertEquals(2, context.faults.reservations(fault));
          assertEquals(2, context.faults.pending(operation));
          assertEquals(0, context.faults.applications(fault));
          releaseFirst.countDown();
          BatchWriteItemResponse firstResponse = first.get(10, TimeUnit.SECONDS);
          releaseSecond.countDown();
          BatchWriteItemResponse secondResponse = second.get(10, TimeUnit.SECONDS);
          context.recorder.requestsFinished(operation).get(10, TimeUnit.SECONDS);
          System.out.println("BatchWrite first=" + firstResponse + " second=" + secondResponse);
          printOverlapObservations(context, fault, operation);
          for (int seq : new int[] {1, 2, 3, 4})
            System.out.println("stored " + seq + "=" + stored(context, seq));
          assertAll(
              () ->
                  assertEquals(
                      Map.of(
                          context.snapshot,
                          firstRequest.requestItems().get(context.snapshot).subList(0, 1)),
                      firstResponse.unprocessedItems()),
              () ->
                  assertEquals(
                      Map.of(
                          context.snapshot,
                          secondRequest.requestItems().get(context.snapshot).subList(0, 1)),
                      secondResponse.unprocessedItems()),
              () -> assertFalse(stored(context, 1).isEmpty()),
              () -> assertFalse(stored(context, 3).isEmpty()),
              () -> assertEquals(!firstAll, stored(context, 2).isEmpty()),
              () -> assertEquals(firstAll, stored(context, 4).isEmpty()));
          assertTrue(
              client
                  .batchWriteItem(
                      firstRequest.toBuilder()
                          .requestItems(firstResponse.unprocessedItems())
                          .build())
                  .get(10, TimeUnit.SECONDS)
                  .unprocessedItems()
                  .isEmpty());
          assertTrue(stored(context, 1).isEmpty());
          assertFalse(stored(context, 3).isEmpty());
          assertTrue(
              client
                  .batchWriteItem(
                      secondRequest.toBuilder()
                          .requestItems(secondResponse.unprocessedItems())
                          .build())
                  .get(10, TimeUnit.SECONDS)
                  .unprocessedItems()
                  .isEmpty());
          context.recorder.requestsFinished(operation).get(10, TimeUnit.SECONDS);
          List<DynamoDbRequestRecorder.Request> observed = context.recorder.requests();
          assertEquals(4, observed.size());
          for (int i = 0; i < 2; i++) {
            BatchWriteItemRequest original = i == 0 ? firstRequest : secondRequest;
            DynamoDbRequestRecorder.Request initial = observed.get(i);
            DynamoDbRequestRecorder.Request retry = observed.get(i + 2);
            List<WriteRequest> writes = original.requestItems().get(context.snapshot);
            assertEquals(DynamoDbJson.sdk(original), initial.original);
            assertEquals(1, initial.httpAttempts);
            assertEquals(writes.size() == 1 ? 0 : 1, initial.transmissions);
            List<com.fasterxml.jackson.databind.JsonNode> processed = new ArrayList<>();
            if (writes.size() == 1) assertNull(initial.transmitted);
            else {
              assertEquals(
                  DynamoDbJson.sdk(writes.subList(1, writes.size())),
                  initial.transmitted.path("RequestItems").path(context.snapshot));
              assertEquals(initial.marshalled, initial.transmitted);
              initial
                  .transmitted
                  .path("RequestItems")
                  .path(context.snapshot)
                  .forEach(processed::add);
            }
            assertEquals(1, retry.httpAttempts);
            assertEquals(1, retry.transmissions);
            assertEquals(retry.original, retry.transmitted);
            assertEquals(retry.marshalled, retry.transmitted);
            assertEquals(
                DynamoDbJson.sdk(
                    i == 0 ? firstResponse.unprocessedItems() : secondResponse.unprocessedItems()),
                retry.transmitted.path("RequestItems"));
            retry.transmitted.path("RequestItems").path(context.snapshot).forEach(processed::add);
            assertEquals(writes.size(), processed.size());
            assertEquals(
                writes.size(), processed.stream().distinct().count(), "no repeated delete");
            assertEquals(
                java.util.Set.copyOf(DynamoDbJson.sdk(writes).findParents("DeleteRequest")),
                java.util.Set.copyOf(processed));
          }
          assertTrue(stored(context, 1).isEmpty());
          assertTrue(stored(context, 3).isEmpty());
          assertEquals(!firstAll, stored(context, 2).isEmpty());
          assertEquals(firstAll, stored(context, 4).isEmpty());
          assertEquals(2, context.faults.applications(fault));
          assertEquals(0, context.faults.reservations(fault));
          assertEquals(0, context.faults.pending(operation));
          assertEquals("passed", context.faults.finish(operation).status);
          FaultRegistry.Operation next = context.faults.begin(2, true);
          assertTrue(
              client
                  .batchWriteItem(deletes(context, 2, 4))
                  .get(10, TimeUnit.SECONDS)
                  .unprocessedItems()
                  .isEmpty());
          context.recorder.requestsFinished(next).get(10, TimeUnit.SECONDS);
          assertTrue(stored(context, 2).isEmpty());
          assertTrue(stored(context, 4).isEmpty());
          DynamoDbRequestRecorder.Request nextRequest = context.recorder.requests().get(4);
          assertEquals(2, nextRequest.operation);
          assertEquals(1, nextRequest.httpAttempts);
          assertEquals(1, nextRequest.transmissions);
          assertEquals(DynamoDbJson.sdk(deletes(context, 2, 4)), nextRequest.transmitted);
          assertEquals(2, context.faults.applications(fault));
          assertEquals(0, context.faults.reservations(fault));
          assertEquals(0, context.faults.pending(next));
          assertEquals("passed", context.faults.finish(next).status);
          printOverlapObservations(context, fault, next);
        } finally {
          releaseFirst.countDown();
          releaseSecond.countDown();
          try {
            callers.shutdown();
            assertTrue(callers.awaitTermination(30, TimeUnit.SECONDS));
            context.recorder.requestsFinished(operation).get(10, TimeUnit.SECONDS);
          } finally {
            callers.shutdownNow();
          }
        }
      } finally {
        callers.shutdownNow();
      }
    }
  }

  @Test
  void historyRequestReplacementRetriesAfterUnappliedFailure() throws Exception {
    assertHistoryRetryAfterUnappliedTerminal(FaultRegistry.Injection.REPLACE_REQUEST, false);
  }

  @Test
  void historyResponseReplacementRetriesAfterUnappliedFailure() throws Exception {
    assertHistoryRetryAfterUnappliedTerminal(FaultRegistry.Injection.REPLACE_RESPONSE, false);
  }

  @Test
  void historyResponseReplacementRetriesAfterUnappliedCancellation() throws Exception {
    assertHistoryRetryAfterUnappliedTerminal(FaultRegistry.Injection.REPLACE_RESPONSE, true);
  }

  private static void assertHistoryRetryAfterUnappliedTerminal(
      FaultRegistry.Injection injection, boolean cancel) throws Exception {
    try (DynamoDbTestContext context = new DynamoDbTestContext(local.endpoint())) {
      createHistoryTable(context);
      for (int seq = 1; seq <= 2; seq++) {
        java.util.HashMap<String, AttributeValue> history =
            new java.util.HashMap<>(item("skey", seq));
        history.put("active_history_seq_nr", AttributeValue.fromN(Integer.toString(seq)));
        context.admin.putItem(
            PutItemRequest.builder().tableName(context.snapshot).item(history).build());
      }
      DynamoDbFaultEffects.HistoryPages plan =
          DynamoDbFaultEffects.historyPages(
              context.admin, context.snapshot, AID, List.of(List.of(2L), List.of(1L)), null, false);
      FaultRegistry.Fault fault = context.faults.register(1, "retention-query", 1, injection, plan);
      java.util.concurrent.atomic.AtomicBoolean first =
          new java.util.concurrent.atomic.AtomicBoolean(true);
      CountDownLatch responseReached = new CountDownLatch(1);
      CountDownLatch release = new CountDownLatch(1);
      RuntimeException stop =
          cancel
              ? new java.util.concurrent.CancellationException(
                  "history request cancelled before application")
              : new IllegalStateException("history request failed before application");
      CompletableFuture<Throwable> pipelineFailure = new CompletableFuture<>();
      ExecutionInterceptor gate =
          new ExecutionInterceptor() {
            @Override
            public void beforeTransmission(
                Context.BeforeTransmission actual, ExecutionAttributes attrs) {
              if (!cancel && first.compareAndSet(true, false)) throw stop;
            }

            @Override
            public void afterUnmarshalling(
                Context.AfterUnmarshalling actual, ExecutionAttributes attrs) {
              if (cancel && first.compareAndSet(true, false)) {
                assertEquals(200, actual.httpResponse().statusCode());
                responseReached.countDown();
                await(release);
                throw stop;
              }
            }

            @Override
            public void onExecutionFailure(
                Context.FailedExecution actual, ExecutionAttributes attrs) {
              pipelineFailure.complete(actual.exception());
            }
          };
      FaultRegistry.Operation operation = context.faults.begin(1, true);
      QueryRequest request =
          QueryRequest.builder()
              .tableName(context.snapshot)
              .indexName(context.historyIndex)
              .keyConditionExpression("aid=:aid")
              .expressionAttributeValues(Map.of(":aid", AttributeValue.fromS(AID)))
              .scanIndexForward(false)
              .limit(1)
              .build();
      try (FaultAsyncHttpClient http =
              new FaultAsyncHttpClient(DynamoDbTestClients.asyncHttp().build(), context.recorder);
          DynamoDbAsyncClient client =
              DynamoDbTestClients.observedAsync(local.endpoint(), context.recorder, http, gate)) {
        CompletableFuture<QueryResponse> initial = client.query(request);
        CompletableFuture<Void> initialTermination = context.recorder.requestsFinished(operation);
        try {
          if (cancel) {
            assertTrue(responseReached.await(10, TimeUnit.SECONDS));
            assertEquals(0, context.faults.applications(fault));
            assertEquals(1, context.faults.reservations(fault));
            assertEquals(1, context.faults.pending(operation));
            assertTrue(initial.cancel(true));
            release.countDown();
            assertTrue(initial.isCancelled());
          } else assertNotNull(initial.handle((value, error) -> error).get(10, TimeUnit.SECONDS));
          Throwable failure = pipelineFailure.get(10, TimeUnit.SECONDS);
          while (failure.getCause() != null && failure != stop) failure = failure.getCause();
          assertSame(stop, failure);
          context.recorder.requestsFinished(operation).get(10, TimeUnit.SECONDS);
          assertEquals(0, context.faults.applications(fault));
          assertEquals(0, context.faults.reservations(fault));
          assertEquals(0, context.faults.pending(operation));
          DynamoDbRequestRecorder.Request failed = context.recorder.requests().get(0);
          assertEquals(cancel ? 1 : 0, failed.httpAttempts);
          assertEquals(cancel ? 1 : 0, failed.transmissions);
          System.out.println(
              "History terminated cancel="
                  + cancel
                  + " injection="
                  + injection
                  + " failure="
                  + failure);
          printOverlapObservations(context, fault, operation);
          CompletableFuture<QueryResponse> retry = client.query(request);
          Throwable retryFailure = retry.handle((value, error) -> error).get(10, TimeUnit.SECONDS);
          System.out.println("History retry failure=" + retryFailure);
          assertNull(retryFailure, "the same unconsumed history fault can be retried");
          QueryResponse page = retry.join();
          context.recorder.requestsFinished(operation).get(10, TimeUnit.SECONDS);
          java.util.HashMap<String, AttributeValue> newest =
              new java.util.HashMap<>(key("skey", 2));
          newest.put("active_history_seq_nr", stored(context, 2).get("active_history_seq_nr"));
          assertEquals(List.of(newest), page.items());
          assertEquals(newest, page.lastEvaluatedKey());
          assertEquals(1, context.faults.applications(fault));
          assertEquals(0, context.faults.reservations(fault));
          assertEquals(0, context.faults.pending(operation));
          QueryResponse last =
              client
                  .query(request.toBuilder().exclusiveStartKey(page.lastEvaluatedKey()).build())
                  .get(10, TimeUnit.SECONDS);
          context.recorder.requestsFinished(operation).get(10, TimeUnit.SECONDS);
          java.util.HashMap<String, AttributeValue> oldest =
              new java.util.HashMap<>(key("skey", 1));
          oldest.put("active_history_seq_nr", stored(context, 1).get("active_history_seq_nr"));
          assertEquals(List.of(oldest), last.items());
          assertTrue(last.lastEvaluatedKey().isEmpty());
          assertFalse(plan.hasNext());
          assertEquals(3, context.recorder.requests().size());
          List<DynamoDbRequestRecorder.Request> observed = context.recorder.requests();
          assertEquals(
              DynamoDbJson.sdk(page.lastEvaluatedKey()),
              observed.get(2).marshalled.path("ExclusiveStartKey"));
          for (DynamoDbRequestRecorder.Request query : observed.subList(1, 3)) {
            assertEquals("retention-query", query.phase);
            assertEquals(1, query.httpAttempts);
            assertEquals(
                injection == FaultRegistry.Injection.REPLACE_REQUEST ? 0 : 1, query.transmissions);
          }
          assertEquals(1, context.faults.applications(fault));
          assertEquals(0, context.faults.reservations(fault));
          assertEquals(0, context.faults.pending(operation));
          assertEquals("passed", context.faults.finish(operation).status);
          System.out.println("History retry=" + page + " continuation=" + last + " finish=passed");
          printOverlapObservations(context, fault, operation);
        } finally {
          release.countDown();
          initialTermination.get(10, TimeUnit.SECONDS);
        }
      }
    }
  }

  private static void createHistoryTable(DynamoDbTestContext context) {
    context.acquire(
        DynamoDbTestContext.table(context.snapshot, "skey").toBuilder()
            .attributeDefinitions(
                AttributeDefinition.builder()
                    .attributeName("aid")
                    .attributeType(ScalarAttributeType.S)
                    .build(),
                AttributeDefinition.builder()
                    .attributeName("skey")
                    .attributeType(ScalarAttributeType.N)
                    .build(),
                AttributeDefinition.builder()
                    .attributeName("active_history_seq_nr")
                    .attributeType(ScalarAttributeType.N)
                    .build())
            .globalSecondaryIndexes(
                GlobalSecondaryIndex.builder()
                    .indexName(context.historyIndex)
                    .keySchema(
                        KeySchemaElement.builder()
                            .attributeName("aid")
                            .keyType(KeyType.HASH)
                            .build(),
                        KeySchemaElement.builder()
                            .attributeName("active_history_seq_nr")
                            .keyType(KeyType.RANGE)
                            .build())
                    .projection(
                        Projection.builder().projectionType(ProjectionType.KEYS_ONLY).build())
                    .build())
            .build());
  }

  @Test
  void syncZeroUnprocessedPlanSendsAndProcessesTheWholeBatch() throws Exception {
    assertZeroUnprocessedPlan(false);
  }

  @Test
  void asyncZeroUnprocessedPlanSendsAndProcessesTheWholeBatch() throws Exception {
    assertZeroUnprocessedPlan(true);
  }

  private static void assertZeroUnprocessedPlan(boolean async) throws Exception {
    try (DynamoDbTestContext context = new DynamoDbTestContext(local.endpoint())) {
      context.createMinimalTables();
      for (int seq = 1; seq <= 2; seq++)
        context.admin.putItem(
            PutItemRequest.builder().tableName(context.snapshot).item(item("skey", seq)).build());
      FaultRegistry.Fault fault =
          context.faults.register(
              1,
              "retention-delete",
              1,
              FaultRegistry.Injection.REPLACE_REQUEST,
              DynamoDbFaultEffects.unprocessedFirst(0));
      FaultRegistry.Operation operation = context.faults.begin(1, true);
      BatchWriteItemRequest request = deletes(context, 1, 2);
      BatchWriteItemResponse response =
          async
              ? context.async.batchWriteItem(request).join()
              : context.client.batchWriteItem(request);
      context.recorder.requestsFinished(operation).get(10, TimeUnit.SECONDS);
      assertTrue(response.unprocessedItems().isEmpty());
      assertTrue(stored(context, 1).isEmpty());
      assertTrue(stored(context, 2).isEmpty());
      assertEquals(1, context.recorder.requests().size());
      DynamoDbRequestRecorder.Request observed = context.recorder.requests().get(0);
      assertEquals(1, observed.httpAttempts);
      assertEquals(1, observed.transmissions);
      assertEquals(DynamoDbJson.sdk(request), observed.original);
      assertEquals(observed.original, observed.marshalled);
      assertEquals(observed.marshalled, observed.transmitted);
      assertEquals(1, context.faults.applications(fault));
      assertEquals(0, context.faults.reservations(fault));
      assertEquals(0, context.faults.pending(operation));
      assertEquals("passed", context.faults.finish(operation).status);
      System.out.println(
          "Zero unprocessed async=" + async + " response=" + response + " finish=passed");
      printOverlapObservations(context, fault, operation);
    }
  }

  @Test
  void unprocessedPlanRejectsNegativeAndExcessCounts() {
    assertThrows(IllegalArgumentException.class, () -> DynamoDbFaultEffects.unprocessedFirst(-1));
    BatchWriteItemRequest request =
        BatchWriteItemRequest.builder()
            .requestItems(
                Map.of(
                    "snapshot",
                    List.of(
                        WriteRequest.builder()
                            .deleteRequest(DeleteRequest.builder().key(key("skey", 1)).build())
                            .build())))
            .build();
    FaultRegistry.Effect effect = DynamoDbFaultEffects.unprocessedFirst(2);
    assertTrue(effect.supports(FaultRegistry.Injection.REPLACE_REQUEST));
    assertFalse(effect.supports(FaultRegistry.Injection.REPLACE_RESPONSE));
    assertThrows(
        IllegalArgumentException.class,
        () -> effect.prepare(request, FaultRegistry.Injection.REPLACE_REQUEST));
  }

  @Test
  void partialBatchGetPreparationsKeepEachRequestsKeyAndSupplementedAttributes() {
    KeysAndAttributes firstKeys =
        KeysAndAttributes.builder()
            .keys(key("skey", 1), key("skey", 2))
            .projectionExpression("#first")
            .expressionAttributeNames(Map.of("#first", "store_id"))
            .build();
    KeysAndAttributes secondKeys =
        KeysAndAttributes.builder()
            .keys(key("skey", 1), key("skey", 3))
            .projectionExpression("#id, #second")
            .expressionAttributeNames(Map.of("#id", "aid", "#second", "payload"))
            .build();
    DynamoDbFaultEffects.PartialBatchGet plan =
        (DynamoDbFaultEffects.PartialBatchGet)
            DynamoDbFaultEffects.partialBatchGet(null, Map.of("snapshot", List.of(key("skey", 1))));
    DynamoDbFaultEffects.PartialBatchGet first = plan.forRequest();
    DynamoDbFaultEffects.PartialBatchGet second = plan.forRequest();
    first.prepare(
        BatchGetItemRequest.builder().requestItems(Map.of("snapshot", firstKeys)).build(),
        FaultRegistry.Injection.REPLACE_RESPONSE);
    second.prepare(
        BatchGetItemRequest.builder().requestItems(Map.of("snapshot", secondKeys)).build(),
        FaultRegistry.Injection.REPLACE_RESPONSE);
    java.util.HashMap<String, AttributeValue> firstItem = new java.util.HashMap<>(key("skey", 2));
    firstItem.put("store_id", AttributeValue.fromS("owner-2"));
    java.util.HashMap<String, AttributeValue> secondItem = new java.util.HashMap<>(key("skey", 3));
    secondItem.put("payload", AttributeValue.fromS("payload-3"));
    BatchGetItemResponse firstResponse =
        (BatchGetItemResponse)
            first.response(
                null,
                BatchGetItemResponse.builder()
                    .responses(Map.of("snapshot", List.of(firstItem)))
                    .build());
    BatchGetItemResponse secondResponse =
        (BatchGetItemResponse)
            second.response(
                null,
                BatchGetItemResponse.builder()
                    .responses(Map.of("snapshot", List.of(secondItem)))
                    .build());
    assertEquals(
        List.of(Map.of("store_id", AttributeValue.fromS("owner-2"))),
        firstResponse.responses().get("snapshot"));
    assertEquals(
        List.of(
            Map.of("aid", AttributeValue.fromS(AID), "payload", AttributeValue.fromS("payload-3"))),
        secondResponse.responses().get("snapshot"));
    assertEquals(
        firstKeys.toBuilder().keys(key("skey", 1)).build(),
        firstResponse.unprocessedKeys().get("snapshot"));
    assertEquals(
        secondKeys.toBuilder().keys(key("skey", 1)).build(),
        secondResponse.unprocessedKeys().get("snapshot"));
  }

  @Test
  void asyncRequestReplacementIsolatesOverlappingBatchGetPreparation() throws Exception {
    assertOverlappingBatchGetPreparation(FaultRegistry.Injection.REPLACE_REQUEST);
  }

  @Test
  void asyncResponseReplacementIsolatesOverlappingBatchGetPreparation() throws Exception {
    assertOverlappingBatchGetPreparation(FaultRegistry.Injection.REPLACE_RESPONSE);
  }

  private static void assertOverlappingBatchGetPreparation(FaultRegistry.Injection injection)
      throws Exception {
    try (DynamoDbTestContext context = new DynamoDbTestContext(local.endpoint())) {
      context.createMinimalTables();
      Map<String, AttributeValue> pendingKey = key("skey", 1);
      Map<String, AttributeValue> firstKey = key("skey", 2);
      Map<String, AttributeValue> secondKey = key("skey", 3);
      for (int seq = 1; seq <= 3; seq++) {
        java.util.HashMap<String, AttributeValue> stored =
            new java.util.HashMap<>(key("skey", seq));
        stored.put("store_id", AttributeValue.fromS("owner-" + seq));
        stored.put("payload", AttributeValue.fromS("payload-" + seq));
        stored.put("extra", AttributeValue.fromS("outside-projection"));
        context.admin.putItem(
            PutItemRequest.builder().tableName(context.snapshot).item(stored).build());
      }
      KeysAndAttributes firstKeys =
          KeysAndAttributes.builder()
              .keys(pendingKey, firstKey)
              .consistentRead(true)
              .projectionExpression("#first")
              .expressionAttributeNames(Map.of("#first", "store_id"))
              .build();
      KeysAndAttributes secondKeys =
          KeysAndAttributes.builder()
              .keys(pendingKey, secondKey)
              .consistentRead(false)
              .projectionExpression("#id, #second")
              .expressionAttributeNames(Map.of("#id", "aid", "#second", "payload"))
              .build();
      BatchGetItemRequest firstRequest =
          BatchGetItemRequest.builder().requestItems(Map.of(context.snapshot, firstKeys)).build();
      BatchGetItemRequest secondRequest =
          BatchGetItemRequest.builder().requestItems(Map.of(context.snapshot, secondKeys)).build();
      FaultRegistry.Fault fault =
          context.faults.register(
              1,
              "read-snapshot",
              2,
              injection,
              DynamoDbFaultEffects.partialBatchGet(
                  injection == FaultRegistry.Injection.REPLACE_REQUEST ? context.admin : null,
                  Map.of(context.snapshot, List.of(pendingKey))));
      CountDownLatch firstPrepared = new CountDownLatch(1);
      CountDownLatch secondPrepared = new CountDownLatch(1);
      CountDownLatch releaseFirst = new CountDownLatch(1);
      CountDownLatch releaseSecond = new CountDownLatch(1);
      ExecutionInterceptor gate =
          new ExecutionInterceptor() {
            @Override
            public void beforeTransmission(
                Context.BeforeTransmission actual, ExecutionAttributes attrs) {
              KeysAndAttributes keys =
                  ((BatchGetItemRequest) actual.request()).requestItems().get(context.snapshot);
              if (keys.keys().size() != 2) return;
              boolean first = keys.keys().contains(firstKey);
              System.out.println("prepared " + (first ? "A" : "B") + " " + keys);
              (first ? firstPrepared : secondPrepared).countDown();
              await(first ? releaseFirst : releaseSecond);
            }
          };
      ExecutorService callers = Executors.newFixedThreadPool(2);
      FaultRegistry.Operation operation = context.faults.begin(1, false);
      try (FaultAsyncHttpClient http =
              new FaultAsyncHttpClient(DynamoDbTestClients.asyncHttp().build(), context.recorder);
          DynamoDbAsyncClient client =
              DynamoDbTestClients.observedAsync(local.endpoint(), context.recorder, http, gate)) {
        try {
          CompletableFuture<BatchGetItemResponse> first =
              CompletableFuture.supplyAsync(
                  () -> client.batchGetItem(firstRequest).join(), callers);
          assertTrue(firstPrepared.await(10, TimeUnit.SECONDS), "first prepare completed");
          CompletableFuture<BatchGetItemResponse> second =
              CompletableFuture.supplyAsync(
                  () -> client.batchGetItem(secondRequest).join(), callers);
          assertTrue(secondPrepared.await(10, TimeUnit.SECONDS), "second prepare completed");
          System.out.println(
              "both prepared pending="
                  + context.faults.pending(operation)
                  + " reservations="
                  + context.faults.reservations(fault)
                  + " applications="
                  + context.faults.applications(fault));
          assertEquals(2, context.faults.pending(operation));
          assertEquals(2, context.faults.reservations(fault));
          assertEquals(0, context.faults.applications(fault));
          releaseFirst.countDown();
          Throwable firstFailure =
              first.handle((response, error) -> error).get(10, TimeUnit.SECONDS);
          System.out.println(
              "A SDK result=" + (firstFailure == null ? first.join() : firstFailure));
          releaseSecond.countDown();
          Throwable secondFailure =
              second.handle((response, error) -> error).get(10, TimeUnit.SECONDS);
          System.out.println(
              "B SDK result=" + (secondFailure == null ? second.join() : secondFailure));
          context.recorder.requestsFinished(operation).get(10, TimeUnit.SECONDS);
          printOverlapObservations(context, fault, operation);
          assertAll(
              () -> assertNull(firstFailure, "first SDK request failed"),
              () -> assertNull(secondFailure, "second SDK request failed"));
          BatchGetItemResponse firstResponse = first.join();
          BatchGetItemResponse secondResponse = second.join();
          BatchGetItemResponse firstRetry =
              client
                  .batchGetItem(
                      firstRequest.toBuilder()
                          .requestItems(firstResponse.unprocessedKeys())
                          .build())
                  .get(10, TimeUnit.SECONDS);
          BatchGetItemResponse secondRetry =
              client
                  .batchGetItem(
                      secondRequest.toBuilder()
                          .requestItems(secondResponse.unprocessedKeys())
                          .build())
                  .get(10, TimeUnit.SECONDS);
          context.recorder.requestsFinished(operation).get(10, TimeUnit.SECONDS);
          printOverlapObservations(context, fault, operation);
          assertOverlapResponse(
              context.snapshot,
              firstKeys,
              pendingKey,
              firstResponse,
              firstRetry,
              Map.of("store_id", AttributeValue.fromS("owner-2")),
              Map.of("store_id", AttributeValue.fromS("owner-1")));
          assertOverlapResponse(
              context.snapshot,
              secondKeys,
              pendingKey,
              secondResponse,
              secondRetry,
              Map.of(
                  "aid", AttributeValue.fromS(AID), "payload", AttributeValue.fromS("payload-3")),
              Map.of(
                  "aid", AttributeValue.fromS(AID), "payload", AttributeValue.fromS("payload-1")));
          List<DynamoDbRequestRecorder.Request> observed = context.recorder.requests();
          assertEquals(4, observed.size());
          assertOverlapRequest(observed.get(0), firstRequest, injection);
          assertOverlapRequest(observed.get(1), secondRequest, injection);
          for (int i = 2; i < 4; i++) {
            DynamoDbRequestRecorder.Request retry = observed.get(i);
            assertEquals(1, retry.httpAttempts);
            assertEquals(1, retry.transmissions);
            assertEquals(retry.original, retry.marshalled);
            assertEquals(retry.marshalled, retry.transmitted);
            assertEquals(
                DynamoDbJson.sdk(
                    i == 2 ? firstResponse.unprocessedKeys() : secondResponse.unprocessedKeys()),
                retry.transmitted.path("RequestItems"));
          }
          assertEquals(2, context.faults.applications(fault));
          assertEquals(0, context.faults.reservations(fault));
          assertEquals(0, context.faults.pending(operation), "pending at finish");
          String status = context.faults.finish(operation).status;
          System.out.println("finish pending=0 status=" + status);
          assertEquals("passed", status);
          FaultRegistry.Operation next = context.faults.begin(2, false);
          BatchGetItemResponse nextResponse =
              client.batchGetItem(firstRequest).get(10, TimeUnit.SECONDS);
          context.recorder.requestsFinished(next).get(10, TimeUnit.SECONDS);
          assertTrue(nextResponse.unprocessedKeys().isEmpty());
          assertEquals(
              java.util.Set.of(
                  Map.of("store_id", AttributeValue.fromS("owner-1")),
                  Map.of("store_id", AttributeValue.fromS("owner-2"))),
              java.util.Set.copyOf(nextResponse.responses().get(context.snapshot)));
          DynamoDbRequestRecorder.Request nextObserved = context.recorder.requests().get(4);
          assertEquals(2, nextObserved.operation);
          assertEquals(1, nextObserved.httpAttempts);
          assertEquals(1, nextObserved.transmissions);
          assertEquals(DynamoDbJson.sdk(firstRequest), nextObserved.transmitted);
          assertEquals(2, context.faults.applications(fault));
          assertEquals(0, context.faults.reservations(fault));
          assertEquals(0, context.faults.pending(next));
          String nextStatus = context.faults.finish(next).status;
          System.out.println(
              "next operation result=" + nextResponse + " pending=0 status=" + nextStatus);
          assertEquals("passed", nextStatus);
        } finally {
          releaseFirst.countDown();
          releaseSecond.countDown();
          try {
            callers.shutdown();
            assertTrue(callers.awaitTermination(30, TimeUnit.SECONDS), "SDK callers terminated");
            context.recorder.requestsFinished(operation).get(10, TimeUnit.SECONDS);
          } finally {
            callers.shutdownNow();
          }
        }
      } finally {
        callers.shutdownNow();
      }
    }
  }

  private static void assertOverlapResponse(
      String table,
      KeysAndAttributes original,
      Map<String, AttributeValue> pendingKey,
      BatchGetItemResponse initial,
      BatchGetItemResponse retry,
      Map<String, AttributeValue> processedProjection,
      Map<String, AttributeValue> pendingProjection) {
    assertAll(
        () -> assertEquals(List.of(processedProjection), initial.responses().get(table)),
        () -> assertFalse(initial.responses().get(table).contains(pendingProjection)),
        () ->
            assertEquals(
                Map.of(table, original.toBuilder().keys(pendingKey).build()),
                initial.unprocessedKeys()),
        () -> assertEquals(List.of(pendingProjection), retry.responses().get(table)),
        () -> assertTrue(retry.unprocessedKeys().isEmpty()),
        () -> {
          List<Map<String, AttributeValue>> combined =
              new ArrayList<>(initial.responses().get(table));
          combined.addAll(retry.responses().get(table));
          assertEquals(2, combined.size());
          assertEquals(2, combined.stream().distinct().count(), "no duplicate after retry");
        });
  }

  private static void assertOverlapRequest(
      DynamoDbRequestRecorder.Request observed,
      BatchGetItemRequest original,
      FaultRegistry.Injection injection) {
    assertEquals(DynamoDbJson.sdk(original), observed.original);
    assertEquals(1, observed.httpAttempts);
    assertEquals(
        injection == FaultRegistry.Injection.REPLACE_REQUEST ? 0 : 1, observed.transmissions);
    if (injection == FaultRegistry.Injection.REPLACE_REQUEST) {
      assertNull(observed.transmitted);
      assertEquals(observed.original, observed.marshalled);
    } else {
      assertEquals(observed.marshalled, observed.transmitted);
      String table = original.requestItems().keySet().iterator().next();
      com.fasterxml.jackson.databind.JsonNode keys =
          observed.transmitted.path("RequestItems").path(table);
      assertEquals(DynamoDbJson.sdk(original.requestItems().get(table).keys()), keys.path("Keys"));
      Map<String, String> names = original.requestItems().get(table).expressionAttributeNames();
      names.forEach(
          (alias, attribute) ->
              assertEquals(attribute, keys.path("ExpressionAttributeNames").path(alias).asText()));
      java.util.Set<String> projected = new java.util.HashSet<>();
      for (String term : keys.path("ProjectionExpression").asText().split(","))
        projected.add(keys.path("ExpressionAttributeNames").path(term.trim()).asText());
      java.util.Set<String> expected = new java.util.HashSet<>(names.values());
      expected.addAll(java.util.Set.of("aid", "skey"));
      assertEquals(expected, projected);
    }
  }

  private static void printOverlapObservations(
      DynamoDbTestContext context, FaultRegistry.Fault fault, FaultRegistry.Operation operation) {
    for (DynamoDbRequestRecorder.Request request : context.recorder.requests())
      System.out.println(
          "request="
              + request.id
              + " operation="
              + request.operation
              + " original="
              + request.original
              + " marshalled="
              + request.marshalled
              + " transmitted="
              + request.transmitted
              + " attempts="
              + request.httpAttempts
              + " transmissions="
              + request.transmissions);
    System.out.println(
        "applications="
            + context.faults.applications(fault)
            + " reservations="
            + context.faults.reservations(fault)
            + " pending="
            + context.faults.pending(operation));
  }

  @Test
  void syncRequestReplacementPreservesKeylessAliasedProjection() {
    assertKeylessAliasedProjection(false, FaultRegistry.Injection.REPLACE_REQUEST);
  }

  @Test
  void asyncRequestReplacementPreservesKeylessAliasedProjection() {
    assertKeylessAliasedProjection(true, FaultRegistry.Injection.REPLACE_REQUEST);
  }

  @Test
  void syncResponseReplacementPreservesKeylessAliasedProjection() {
    assertKeylessAliasedProjection(false, FaultRegistry.Injection.REPLACE_RESPONSE);
  }

  @Test
  void asyncResponseReplacementPreservesKeylessAliasedProjection() {
    assertKeylessAliasedProjection(true, FaultRegistry.Injection.REPLACE_RESPONSE);
  }

  private static void assertKeylessAliasedProjection(
      boolean async, FaultRegistry.Injection injection) {
    try (DynamoDbTestContext context = new DynamoDbTestContext(local.endpoint())) {
      context.createMinimalTables();
      Map<String, AttributeValue> processedKey = key("skey", 1);
      Map<String, AttributeValue> pendingKey = key("skey", 2);
      Map<String, AttributeValue> processedProjection =
          Map.of("store_id", AttributeValue.fromS("processed-owner"));
      Map<String, AttributeValue> pendingProjection =
          Map.of("store_id", AttributeValue.fromS("pending-owner"));
      for (Map.Entry<Map<String, AttributeValue>, Map<String, AttributeValue>> entry :
          Map.of(processedKey, processedProjection, pendingKey, pendingProjection).entrySet()) {
        java.util.HashMap<String, AttributeValue> stored = new java.util.HashMap<>(entry.getKey());
        stored.putAll(entry.getValue());
        stored.put("extra", AttributeValue.fromS("outside-projection"));
        context.admin.putItem(
            PutItemRequest.builder().tableName(context.snapshot).item(stored).build());
      }
      KeysAndAttributes keys =
          KeysAndAttributes.builder()
              .keys(processedKey, pendingKey)
              .consistentRead(true)
              .projectionExpression("#owner")
              .expressionAttributeNames(Map.of("#owner", "store_id"))
              .build();
      BatchGetItemRequest request =
          BatchGetItemRequest.builder().requestItems(Map.of(context.snapshot, keys)).build();
      FaultRegistry.Fault fault =
          context.faults.register(
              1,
              "read-snapshot",
              1,
              injection,
              DynamoDbFaultEffects.partialBatchGet(
                  injection == FaultRegistry.Injection.REPLACE_REQUEST ? context.admin : null,
                  Map.of(context.snapshot, List.of(pendingKey))));
      FaultRegistry.Operation operation = context.faults.begin(1, false);
      BatchGetItemResponse first = batchGet(context, request, async);
      int firstApplications = context.faults.applications(fault);
      BatchGetItemResponse retry =
          batchGet(
              context, request.toBuilder().requestItems(first.unprocessedKeys()).build(), async);
      List<Map<String, AttributeValue>> combined =
          new ArrayList<>(first.responses().get(context.snapshot));
      combined.addAll(retry.responses().get(context.snapshot));
      List<DynamoDbRequestRecorder.Request> observed = context.recorder.requests();
      int applications = context.faults.applications(fault);
      String status = context.faults.finish(operation).status;
      // Preserve all boundary observations even when a projection assertion fails.
      System.out.println(
          "projection " + async + " " + injection + " first=" + first + " retry=" + retry);
      for (DynamoDbRequestRecorder.Request sent : observed)
        System.out.println(
            "original="
                + sent.original
                + " marshalled="
                + sent.marshalled
                + " transmitted="
                + sent.transmitted
                + " attempts="
                + sent.httpAttempts
                + " transmissions="
                + sent.transmissions);
      System.out.println(
          "applications=" + firstApplications + "->" + applications + " status=" + status);
      assertAll(
          () ->
              assertEquals(
                  List.of(processedProjection),
                  first.responses().get(context.snapshot),
                  "initial projection and exclusion"),
          () ->
              assertFalse(
                  first.responses().get(context.snapshot).contains(pendingProjection),
                  "processed/unprocessed exclusivity"),
          () ->
              assertEquals(
                  Map.of(context.snapshot, keys.toBuilder().keys(pendingKey).build()),
                  first.unprocessedKeys()),
          () -> assertEquals(List.of(pendingProjection), retry.responses().get(context.snapshot)),
          () -> assertTrue(retry.unprocessedKeys().isEmpty()),
          () -> assertEquals(2, combined.size(), "no duplicate after retry"),
          () -> assertEquals(2, combined.stream().distinct().count()),
          () -> assertEquals(2, observed.size()),
          () -> assertEquals(DynamoDbJson.sdk(request), observed.get(0).original),
          () -> assertEquals(keys, request.requestItems().get(context.snapshot)),
          () -> assertEquals(1, observed.get(0).httpAttempts),
          () ->
              assertEquals(
                  injection == FaultRegistry.Injection.REPLACE_REQUEST ? 0 : 1,
                  observed.get(0).transmissions),
          () -> {
            if (injection == FaultRegistry.Injection.REPLACE_REQUEST) {
              assertNull(observed.get(0).transmitted);
              assertEquals(observed.get(0).original, observed.get(0).marshalled);
            } else {
              assertEquals(observed.get(0).marshalled, observed.get(0).transmitted);
              com.fasterxml.jackson.databind.JsonNode sent =
                  observed.get(0).transmitted.path("RequestItems").path(context.snapshot);
              java.util.Set<String> projectedAttributes = new java.util.HashSet<>();
              for (String term : sent.path("ProjectionExpression").asText().split(","))
                projectedAttributes.add(
                    sent.path("ExpressionAttributeNames").path(term.trim()).asText());
              assertEquals(java.util.Set.of("aid", "skey", "store_id"), projectedAttributes);
              assertEquals(
                  "store_id", sent.path("ExpressionAttributeNames").path("#owner").asText());
            }
          },
          () -> assertEquals(1, observed.get(1).httpAttempts),
          () -> assertEquals(1, observed.get(1).transmissions),
          () -> assertEquals(observed.get(1).marshalled, observed.get(1).transmitted),
          () ->
              assertEquals(
                  DynamoDbJson.sdk(first.unprocessedKeys()),
                  observed.get(1).transmitted.path("RequestItems")),
          () -> assertEquals(1, firstApplications),
          () -> assertEquals(1, applications),
          () -> assertEquals("passed", status));
    }
  }

  @Test
  void syncPartialBatchGetReplacesTheRealProjectedResponseAndRetriesOnlyPendingKeys() {
    assertPartialBatchGetResponse(false);
  }

  @Test
  void asyncPartialBatchGetReplacesTheRealProjectedResponseAndRetriesOnlyPendingKeys() {
    assertPartialBatchGetResponse(true);
  }

  private static void assertPartialBatchGetResponse(boolean async) {
    try (DynamoDbTestContext context = new DynamoDbTestContext(local.endpoint())) {
      context.createMinimalTables();
      Map<String, AttributeValue> journalKey =
          Map.of("aid", AttributeValue.fromS("__config__"), "seq_nr", AttributeValue.fromN("0"));
      Map<String, AttributeValue> headKey = Map.of("aid", AttributeValue.fromS("__config__"));
      Map<String, AttributeValue> processedKey =
          Map.of("aid", AttributeValue.fromS("__config__"), "skey", AttributeValue.fromN("0"));
      Map<String, AttributeValue> pendingKey =
          Map.of("aid", AttributeValue.fromS("__config__"), "skey", AttributeValue.fromN("1"));
      Map<String, AttributeValue> missingKey =
          Map.of("aid", AttributeValue.fromS("__config__"), "skey", AttributeValue.fromN("99"));
      for (Map.Entry<String, List<Map<String, AttributeValue>>> table :
          Map.of(
                  context.journal,
                  List.of(journalKey),
                  context.head,
                  List.of(headKey),
                  context.snapshot,
                  List.of(processedKey, pendingKey))
              .entrySet()) {
        for (Map<String, AttributeValue> key : table.getValue()) {
          java.util.HashMap<String, AttributeValue> stored = new java.util.HashMap<>(key);
          stored.put("store_id", AttributeValue.fromS("actual-owner"));
          stored.put("extra", AttributeValue.fromS("outside-projection"));
          context.admin.putItem(
              PutItemRequest.builder().tableName(table.getKey()).item(stored).build());
        }
      }
      KeysAndAttributes snapshotKeys =
          KeysAndAttributes.builder()
              .keys(processedKey, pendingKey, missingKey)
              .consistentRead(true)
              .projectionExpression("aid, skey, #owner")
              .expressionAttributeNames(Map.of("#owner", "store_id"))
              .build();
      KeysAndAttributes journalKeys =
          snapshotKeys.toBuilder()
              .keys(journalKey)
              .projectionExpression("aid, seq_nr, #owner")
              .build();
      FaultRegistry.Fault fault =
          context.faults.register(
              0,
              "configuration-read",
              1,
              FaultRegistry.Injection.REPLACE_RESPONSE,
              DynamoDbFaultEffects.partialBatchGet(
                  context.admin,
                  Map.of(
                      context.snapshot,
                      List.of(pendingKey),
                      context.journal,
                      List.of(journalKey))));
      FaultRegistry.Operation operation = context.faults.begin(0, false);
      BatchGetItemRequest request =
          BatchGetItemRequest.builder()
              .requestItems(
                  Map.of(
                      context.snapshot,
                      snapshotKeys,
                      context.journal,
                      journalKeys,
                      context.head,
                      KeysAndAttributes.builder().keys(headKey).consistentRead(true).build()))
              .returnConsumedCapacity(ReturnConsumedCapacity.TOTAL)
              .build();
      BatchGetItemResponse first = batchGet(context, request, async);
      java.util.HashMap<String, AttributeValue> projected = new java.util.HashMap<>(processedKey);
      projected.put("store_id", AttributeValue.fromS("actual-owner"));
      assertEquals(List.of(projected), first.responses().get(context.snapshot));
      assertTrue(first.responses().getOrDefault(context.journal, List.of()).isEmpty());
      assertEquals("actual-owner", first.responses().get(context.head).get(0).get("store_id").s());
      assertEquals(
          Map.of(
              context.snapshot,
              snapshotKeys.toBuilder().keys(pendingKey).build(),
              context.journal,
              journalKeys),
          first.unprocessedKeys());
      assertFalse(first.consumedCapacity().isEmpty());
      assertEquals(200, first.sdkHttpResponse().statusCode());
      BatchGetItemResponse retry =
          batchGet(
              context, request.toBuilder().requestItems(first.unprocessedKeys()).build(), async);
      projected.putAll(pendingKey);
      assertEquals(List.of(projected), retry.responses().get(context.snapshot));
      assertEquals(
          "actual-owner", retry.responses().get(context.journal).get(0).get("store_id").s());
      assertFalse(retry.responses().get(context.journal).get(0).containsKey("extra"));
      assertTrue(retry.unprocessedKeys().isEmpty());
      List<DynamoDbRequestRecorder.Request> observed = context.recorder.requests();
      assertEquals(2, observed.size());
      for (DynamoDbRequestRecorder.Request sent : observed) {
        assertEquals(1, sent.httpAttempts);
        assertEquals(1, sent.transmissions);
        assertEquals(sent.marshalled, sent.transmitted);
      }
      assertEquals(
          3,
          observed
              .get(0)
              .transmitted
              .path("RequestItems")
              .path(context.snapshot)
              .path("Keys")
              .size());
      assertEquals(
          DynamoDbJson.sdk(first.unprocessedKeys()),
          observed.get(1).transmitted.path("RequestItems"));
      assertEquals(1, context.faults.applications(fault));
      assertEquals("passed", context.faults.finish(operation).status);
    }
  }

  @Test
  void allKeysCanBeUnprocessedTwiceBeforeARealSuccessfulRetry() {
    for (boolean async : new boolean[] {false, true}) {
      try (DynamoDbTestContext context = new DynamoDbTestContext(local.endpoint())) {
        context.createMinimalTables();
        context.admin.putItem(
            PutItemRequest.builder().tableName(context.snapshot).item(item("skey", 1)).build());
        KeysAndAttributes keys =
            KeysAndAttributes.builder().keys(key("skey", 1)).consistentRead(true).build();
        FaultRegistry.Fault fault =
            context.faults.register(
                1,
                "read-snapshot",
                2,
                FaultRegistry.Injection.REPLACE_RESPONSE,
                DynamoDbFaultEffects.partialBatchGet(
                    context.admin, Map.of(context.snapshot, keys.keys())));
        FaultRegistry.Operation operation = context.faults.begin(1, false);
        BatchGetItemRequest request =
            BatchGetItemRequest.builder().requestItems(Map.of(context.snapshot, keys)).build();
        for (int i = 0; i < 2; i++) {
          BatchGetItemResponse response = batchGet(context, request, async);
          assertTrue(response.responses().get(context.snapshot).isEmpty());
          assertEquals(Map.of(context.snapshot, keys), response.unprocessedKeys());
          request = request.toBuilder().requestItems(response.unprocessedKeys()).build();
        }
        BatchGetItemResponse last = batchGet(context, request, async);
        assertEquals(List.of(item("skey", 1)), last.responses().get(context.snapshot));
        assertTrue(last.unprocessedKeys().isEmpty());
        assertEquals(3, context.recorder.requests().size());
        for (DynamoDbRequestRecorder.Request sent : context.recorder.requests()) {
          assertEquals(1, sent.transmissions);
          assertEquals(1, sent.httpAttempts);
        }
        assertEquals(2, context.faults.applications(fault));
        assertEquals("passed", context.faults.finish(operation).status);
      }
    }
  }

  @Test
  void partialResponsePreservesServicePendingKeysAndMetadataWithoutReadingAdmin() {
    Map<String, AttributeValue> first = key("skey", 1);
    Map<String, AttributeValue> second = key("skey", 2);
    Map<String, AttributeValue> third = key("skey", 3);
    KeysAndAttributes keys =
        KeysAndAttributes.builder()
            .keys(first, second, third)
            .consistentRead(true)
            .projectionExpression("aid, skey, payload")
            .build();
    FaultRegistry.Effect effect =
        DynamoDbFaultEffects.partialBatchGet(null, Map.of("snapshot", List.of(second, third)));
    effect.prepare(
        BatchGetItemRequest.builder().requestItems(Map.of("snapshot", keys)).build(),
        FaultRegistry.Injection.REPLACE_RESPONSE);
    BatchGetItemResponse actual =
        BatchGetItemResponse.builder()
            .responses(Map.of("snapshot", List.of(item("skey", 1), item("skey", 2))))
            .unprocessedKeys(Map.of("snapshot", keys.toBuilder().keys(third).build()))
            .consumedCapacity(
                ConsumedCapacity.builder().tableName("snapshot").capacityUnits(3.0).build())
            .build();
    BatchGetItemResponse replaced = (BatchGetItemResponse) effect.response(null, actual);
    assertEquals(List.of(item("skey", 1)), replaced.responses().get("snapshot"));
    List<Map<String, AttributeValue>> pendingKeys =
        replaced.unprocessedKeys().get("snapshot").keys();
    assertEquals(2, pendingKeys.size());
    assertTrue(pendingKeys.containsAll(List.of(second, third)));
    assertEquals(
        keys.projectionExpression(),
        replaced.unprocessedKeys().get("snapshot").projectionExpression());
    assertEquals(actual.consumedCapacity(), replaced.consumedCapacity());
    assertEquals(2, actual.responses().get("snapshot").size());
    assertEquals(List.of(third), actual.unprocessedKeys().get("snapshot").keys());
  }

  @Test
  void partialResponseRestoresServicePendingProjectionAndAvoidsAliasCollision() {
    Map<String, AttributeValue> first = key("skey", 1);
    Map<String, AttributeValue> second = key("skey", 2);
    Map<String, AttributeValue> third = key("skey", 3);
    KeysAndAttributes keys =
        KeysAndAttributes.builder()
            .keys(first, second, third)
            .consistentRead(true)
            .projectionExpression("#partialKey0")
            .expressionAttributeNames(Map.of("#partialKey0", "store_id"))
            .build();
    FaultRegistry.Effect effect =
        DynamoDbFaultEffects.partialBatchGet(null, Map.of("snapshot", List.of(second, third)));
    BatchGetItemRequest request =
        BatchGetItemRequest.builder().requestItems(Map.of("snapshot", keys)).build();
    BatchGetItemRequest supplemented =
        (BatchGetItemRequest) effect.prepare(request, FaultRegistry.Injection.REPLACE_RESPONSE);
    KeysAndAttributes sent = supplemented.requestItems().get("snapshot");
    assertEquals("store_id", sent.expressionAttributeNames().get("#partialKey0"));
    assertEquals(
        java.util.Set.of("aid", "skey", "store_id"),
        java.util.Set.copyOf(sent.expressionAttributeNames().values()));
    assertEquals(keys, request.requestItems().get("snapshot"));
    List<Map<String, AttributeValue>> items = new ArrayList<>();
    for (Map<String, AttributeValue> key : List.of(first, second)) {
      java.util.HashMap<String, AttributeValue> stored = new java.util.HashMap<>(key);
      stored.put("store_id", AttributeValue.fromS("same-projected-value"));
      items.add(stored);
    }
    BatchGetItemResponse.Builder actualBuilder =
        BatchGetItemResponse.builder()
            .responses(Map.of("snapshot", items))
            .unprocessedKeys(Map.of("snapshot", sent.toBuilder().keys(third).build()))
            .consumedCapacity(
                ConsumedCapacity.builder().tableName("snapshot").capacityUnits(3.0).build());
    actualBuilder.sdkHttpResponse(
        software.amazon.awssdk.http.SdkHttpResponse.builder()
            .statusCode(200)
            .putHeader("x-amzn-RequestId", "actual-request-id")
            .build());
    BatchGetItemResponse actual = actualBuilder.build();
    BatchGetItemResponse replaced = (BatchGetItemResponse) effect.response(null, actual);
    assertEquals(
        List.of(Map.of("store_id", AttributeValue.fromS("same-projected-value"))),
        replaced.responses().get("snapshot"));
    assertEquals(
        keys.toBuilder().keys(third, second).build(), replaced.unprocessedKeys().get("snapshot"));
    assertEquals(actual.consumedCapacity(), replaced.consumedCapacity());
    assertEquals(actual.sdkHttpResponse().statusCode(), replaced.sdkHttpResponse().statusCode());
    assertEquals(actual.sdkHttpResponse().headers(), replaced.sdkHttpResponse().headers());
    assertEquals(items, actual.responses().get("snapshot"));
    assertEquals(sent.toBuilder().keys(third).build(), actual.unprocessedKeys().get("snapshot"));
  }

  @Test
  void partialProjectionKeepsExistingDirectAndAliasedPrimaryKeys() {
    for (String projection : List.of("aid, skey, #owner", "#id, #sort, #owner", "#id, #owner")) {
      Map<String, String> names = new java.util.HashMap<>(Map.of("#owner", "store_id"));
      if (projection.contains("#id")) names.put("#id", "aid");
      if (projection.contains("#sort")) names.put("#sort", "skey");
      KeysAndAttributes keys =
          KeysAndAttributes.builder()
              .keys(key("skey", 1), key("skey", 2))
              .projectionExpression(projection)
              .expressionAttributeNames(names)
              .build();
      BatchGetItemRequest request =
          BatchGetItemRequest.builder().requestItems(Map.of("snapshot", keys)).build();
      FaultRegistry.Effect effect =
          DynamoDbFaultEffects.partialBatchGet(null, Map.of("snapshot", List.of(key("skey", 2))));
      BatchGetItemRequest prepared =
          (BatchGetItemRequest) effect.prepare(request, FaultRegistry.Injection.REPLACE_RESPONSE);
      if (projection.equals("#id, #owner")) {
        assertEquals(
            names.size() + 1,
            prepared.requestItems().get("snapshot").expressionAttributeNames().size());
      } else assertEquals(request, prepared);
      java.util.HashMap<String, AttributeValue> actualItem =
          new java.util.HashMap<>(key("skey", 1));
      actualItem.put("store_id", AttributeValue.fromS("owner"));
      BatchGetItemResponse actual =
          BatchGetItemResponse.builder().responses(Map.of("snapshot", List.of(actualItem))).build();
      BatchGetItemResponse replaced = (BatchGetItemResponse) effect.response(null, actual);
      java.util.HashMap<String, AttributeValue> expected = new java.util.HashMap<>(actualItem);
      if (projection.equals("#id, #owner")) expected.remove("skey");
      assertEquals(List.of(expected), replaced.responses().get("snapshot"));
      assertEquals(
          keys.toBuilder().keys(key("skey", 2)).build(),
          replaced.unprocessedKeys().get("snapshot"));
      assertEquals(request, effect.prepare(request, FaultRegistry.Injection.REPLACE_REQUEST));
    }
  }

  @Test
  void partialResponseRejectsItemsWithoutCompleteRequestedKeys() {
    KeysAndAttributes keys =
        KeysAndAttributes.builder()
            .keys(key("skey", 1), key("skey", 2))
            .projectionExpression("#owner")
            .expressionAttributeNames(Map.of("#owner", "store_id"))
            .build();
    FaultRegistry.Effect effect =
        DynamoDbFaultEffects.partialBatchGet(null, Map.of("snapshot", List.of(key("skey", 2))));
    effect.prepare(
        BatchGetItemRequest.builder().requestItems(Map.of("snapshot", keys)).build(),
        FaultRegistry.Injection.REPLACE_RESPONSE);
    for (Map<String, AttributeValue> item :
        List.of(Map.of("store_id", AttributeValue.fromS("owner")), key("skey", 99))) {
      BatchGetItemResponse actual =
          BatchGetItemResponse.builder().responses(Map.of("snapshot", List.of(item))).build();
      assertThrows(IllegalStateException.class, () -> effect.response(null, actual));
      assertEquals(List.of(item), actual.responses().get("snapshot"));
      assertTrue(actual.unprocessedKeys().isEmpty());
    }
  }

  @Test
  void partialBatchGetRejectsUnrequestedKeysInBothModes() {
    for (FaultRegistry.Injection injection : FaultRegistry.Injection.values()) {
      FaultRegistry.Effect effect =
          DynamoDbFaultEffects.partialBatchGet(null, Map.of("snapshot", List.of(key("skey", 2))));
      assertThrows(
          IllegalArgumentException.class,
          () -> {
            effect.prepare(
                BatchGetItemRequest.builder()
                    .requestItems(
                        Map.of(
                            "snapshot", KeysAndAttributes.builder().keys(key("skey", 1)).build()))
                    .build(),
                injection);
            if (injection == FaultRegistry.Injection.REPLACE_REQUEST) effect.reply(null);
            else effect.response(null, BatchGetItemResponse.builder().build());
          });
    }
  }

  @Test
  void syncEmptyAttributeValuesSurviveRequestRecordingStorageAndPartialHttpResponse() {
    assertEmptyAttributeValues(false);
  }

  @Test
  void asyncEmptyAttributeValuesSurviveRequestRecordingStorageAndPartialHttpResponse() {
    assertEmptyAttributeValues(true);
  }

  private static void assertEmptyAttributeValues(boolean async) {
    try (DynamoDbTestContext context = new DynamoDbTestContext(local.endpoint())) {
      context.createMinimalTables();
      java.util.HashMap<String, AttributeValue> values = new java.util.HashMap<>(key("skey", 1));
      values.put("empty_list", AttributeValue.fromL(List.of()));
      values.put("empty_map", AttributeValue.fromM(Map.of()));
      values.put("nested", AttributeValue.fromL(List.of(AttributeValue.fromM(Map.of()))));
      FaultRegistry.Operation write = context.faults.begin(1, true);
      transact(
          context,
          TransactWriteItemsRequest.builder()
              .transactItems(
                  TransactWriteItem.builder()
                      .put(Put.builder().tableName(context.snapshot).item(values).build())
                      .build())
              .build(),
          async);
      assertEquals("passed", context.faults.finish(write).status);
      DynamoDbRequestRecorder.Request recorded = context.recorder.requests().get(0);
      com.fasterxml.jackson.databind.JsonNode expected =
          DynamoDbRequestStructureTest.json("{\"L\":[]}");
      assertEquals(expected, recorded.original.at("/TransactItems/0/Put/Item/empty_list"));
      assertEquals(expected, recorded.marshalled.at("/TransactItems/0/Put/Item/empty_list"));
      expected = DynamoDbRequestStructureTest.json("{\"M\":{}}");
      assertEquals(expected, recorded.original.at("/TransactItems/0/Put/Item/empty_map"));
      assertEquals(expected, recorded.marshalled.at("/TransactItems/0/Put/Item/empty_map"));
      assertFalse(recorded.marshalled.path("ClientRequestToken").asText().isEmpty());
      assertEquals(recorded.marshalled, recorded.transmitted);
      assertEquals(values, stored(context, 1));
      context.admin.putItem(
          PutItemRequest.builder().tableName(context.snapshot).item(item("skey", 2)).build());
      FaultRegistry.Fault fault =
          context.faults.register(
              2,
              "read-snapshot",
              1,
              FaultRegistry.Injection.REPLACE_REQUEST,
              DynamoDbFaultEffects.partialBatchGet(
                  context.admin, Map.of(context.snapshot, List.of(key("skey", 2)))));
      FaultRegistry.Operation read = context.faults.begin(2, false);
      BatchGetItemRequest request =
          BatchGetItemRequest.builder()
              .requestItems(
                  Map.of(
                      context.snapshot,
                      KeysAndAttributes.builder()
                          .keys(key("skey", 1), key("skey", 2))
                          .consistentRead(true)
                          .build()))
              .build();
      BatchGetItemResponse response = batchGet(context, request, async);
      assertEquals(List.of(values), response.responses().get(context.snapshot));
      Map<String, AttributeValue> restored = response.responses().get(context.snapshot).get(0);
      assertTrue(restored.get("empty_list").hasL());
      assertTrue(restored.get("empty_list").l().isEmpty());
      assertTrue(restored.get("empty_map").hasM());
      assertTrue(restored.get("empty_map").m().isEmpty());
      assertTrue(restored.get("nested").l().get(0).hasM());
      BatchGetItemResponse retry =
          batchGet(
              context, request.toBuilder().requestItems(response.unprocessedKeys()).build(), async);
      assertEquals(List.of(item("skey", 2)), retry.responses().get(context.snapshot));
      assertEquals(0, context.recorder.requests().get(1).transmissions);
      assertEquals(1, context.recorder.requests().get(2).transmissions);
      assertEquals(1, context.faults.applications(fault));
      assertEquals("passed", context.faults.finish(read).status);
    }
  }

  private static BatchGetItemResponse batchGet(
      DynamoDbTestContext context, BatchGetItemRequest request, boolean async) {
    return async
        ? context.async.batchGetItem(request).join()
        : context.client.batchGetItem(request);
  }

  @Test
  void partialConfigurationBatchReturnsOnlyRequestedStoredItemsAndRetriesActualRemainingKeys() {
    for (boolean async : new boolean[] {false, true}) {
      try (DynamoDbTestContext context = new DynamoDbTestContext(local.endpoint())) {
        context.createMinimalTables();
        Map<String, AttributeValue> journalKey =
            Map.of("aid", AttributeValue.fromS("__config__"), "seq_nr", AttributeValue.fromN("0"));
        Map<String, AttributeValue> snapshotKey =
            Map.of("aid", AttributeValue.fromS("__config__"), "skey", AttributeValue.fromN("0"));
        Map<String, AttributeValue> headKey = Map.of("aid", AttributeValue.fromS("__config__"));
        for (Map.Entry<String, Map<String, AttributeValue>> entry :
            Map.of(
                    context.journal,
                    journalKey,
                    context.snapshot,
                    snapshotKey,
                    context.head,
                    headKey)
                .entrySet()) {
          java.util.HashMap<String, AttributeValue> config =
              new java.util.HashMap<>(entry.getValue());
          config.put("store_id", AttributeValue.fromS("stored-owner"));
          context.admin.putItem(
              PutItemRequest.builder().tableName(entry.getKey()).item(config).build());
        }
        FaultRegistry.Fault fault =
            context.faults.register(
                0,
                "configuration-read",
                1,
                FaultRegistry.Injection.REPLACE_REQUEST,
                DynamoDbFaultEffects.partialBatchGet(
                    context.admin,
                    Map.of(
                        context.journal,
                        List.of(journalKey),
                        context.snapshot,
                        List.of(snapshotKey))));
        FaultRegistry.Operation initialization = context.faults.begin(0, false);
        BatchGetItemRequest batch =
            BatchGetItemRequest.builder()
                .requestItems(
                    Map.of(
                        context.journal,
                            KeysAndAttributes.builder()
                                .keys(journalKey)
                                .consistentRead(true)
                                .build(),
                        context.snapshot,
                            KeysAndAttributes.builder()
                                .keys(snapshotKey)
                                .consistentRead(true)
                                .build(),
                        context.head,
                            KeysAndAttributes.builder().keys(headKey).consistentRead(true).build()))
                .build();
        BatchGetItemResponse first =
            async ? context.async.batchGetItem(batch).join() : context.client.batchGetItem(batch);
        assertEquals(
            "stored-owner", first.responses().get(context.head).get(0).get("store_id").s());
        assertTrue(first.responses().getOrDefault(context.journal, List.of()).isEmpty());
        assertEquals(2, first.unprocessedKeys().size());
        BatchGetItemRequest retry = batch.toBuilder().requestItems(first.unprocessedKeys()).build();
        BatchGetItemResponse remaining =
            async ? context.async.batchGetItem(retry).join() : context.client.batchGetItem(retry);
        assertEquals(2, remaining.responses().values().stream().mapToInt(List::size).sum());
        assertTrue(remaining.unprocessedKeys().isEmpty());
        List<DynamoDbRequestRecorder.Request> requests = context.recorder.requests();
        assertEquals(0, requests.get(0).transmissions);
        assertEquals(1, requests.get(1).transmissions);
        assertFalse(requests.get(1).transmitted.path("RequestItems").has(context.head));
        assertTrue(
            requests
                .get(1)
                .transmitted
                .path("RequestItems")
                .path(context.journal)
                .path("ConsistentRead")
                .asBoolean());
        assertEquals(1, context.faults.applications(fault));
        assertEquals("passed", context.faults.finish(initialization).status);
      }
    }
  }

  @Test
  void unprocessedFirstItemIsExcludedFromRealDeletionAndTheRetryProcessesIt() {
    for (boolean async : new boolean[] {false, true}) {
      try (DynamoDbTestContext context = new DynamoDbTestContext(local.endpoint())) {
        context.createMinimalTables();
        for (int seq = 1; seq <= 3; seq++)
          context.admin.putItem(
              PutItemRequest.builder().tableName(context.snapshot).item(item("skey", seq)).build());
        FaultRegistry.Fault fault =
            context.faults.register(
                1,
                "retention-delete",
                1,
                FaultRegistry.Injection.REPLACE_REQUEST,
                DynamoDbFaultEffects.unprocessedFirst(1));
        FaultRegistry.Operation operation = context.faults.begin(1, true);
        BatchWriteItemRequest request = deletes(context, 1, 2, 3);
        BatchWriteItemResponse first =
            async
                ? context.async.batchWriteItem(request).join()
                : context.client.batchWriteItem(request);
        assertEquals(1, first.unprocessedItems().get(context.snapshot).size());
        assertFalse(stored(context, 1).isEmpty());
        assertTrue(stored(context, 2).isEmpty());
        assertTrue(stored(context, 3).isEmpty());
        DynamoDbRequestRecorder.Request observed = context.recorder.requests().get(0);
        assertEquals(3, observed.original.path("RequestItems").path(context.snapshot).size());
        assertEquals(2, observed.marshalled.path("RequestItems").path(context.snapshot).size());
        assertEquals(observed.marshalled, observed.transmitted);
        BatchWriteItemRequest retry =
            request.toBuilder().requestItems(first.unprocessedItems()).build();
        BatchWriteItemResponse second =
            async
                ? context.async.batchWriteItem(retry).join()
                : context.client.batchWriteItem(retry);
        assertTrue(second.unprocessedItems().isEmpty());
        assertTrue(stored(context, 1).isEmpty());
        assertEquals(
            1,
            context
                .recorder
                .requests()
                .get(1)
                .transmitted
                .path("RequestItems")
                .path(context.snapshot)
                .size());
        assertEquals(1, context.faults.applications(fault));
        assertEquals("passed", context.faults.finish(operation).status);
      }
    }
  }

  @Test
  void entirelyUnprocessedBatchNeverSendsAnEmptyBatch() {
    for (boolean async : new boolean[] {false, true}) {
      try (DynamoDbTestContext context = new DynamoDbTestContext(local.endpoint())) {
        context.createMinimalTables();
        context.admin.putItem(
            PutItemRequest.builder().tableName(context.snapshot).item(item("skey", 1)).build());
        context.faults.register(
            1,
            "retention-delete",
            1,
            FaultRegistry.Injection.REPLACE_REQUEST,
            DynamoDbFaultEffects.unprocessedFirst(1));
        FaultRegistry.Operation operation = context.faults.begin(1, true);
        BatchWriteItemResponse response =
            async
                ? context.async.batchWriteItem(deletes(context, 1)).join()
                : context.client.batchWriteItem(deletes(context, 1));
        assertEquals(1, response.unprocessedItems().get(context.snapshot).size());
        assertFalse(stored(context, 1).isEmpty());
        assertEquals(0, context.recorder.requests().get(0).transmissions);
        assertEquals("passed", context.faults.finish(operation).status);
      }
    }
  }

  @Test
  void historyPagesUseStoredItemsAndRealCursorsButConsumeOnlyOnce() {
    for (boolean async : new boolean[] {false, true}) {
      try (DynamoDbTestContext context = new DynamoDbTestContext(local.endpoint())) {
        context.acquire(
            DynamoDbTestContext.table(context.snapshot, "skey").toBuilder()
                .attributeDefinitions(
                    AttributeDefinition.builder()
                        .attributeName("aid")
                        .attributeType(ScalarAttributeType.S)
                        .build(),
                    AttributeDefinition.builder()
                        .attributeName("skey")
                        .attributeType(ScalarAttributeType.N)
                        .build(),
                    AttributeDefinition.builder()
                        .attributeName("active_history_seq_nr")
                        .attributeType(ScalarAttributeType.N)
                        .build())
                .globalSecondaryIndexes(
                    GlobalSecondaryIndex.builder()
                        .indexName(context.historyIndex)
                        .keySchema(
                            KeySchemaElement.builder()
                                .attributeName("aid")
                                .keyType(KeyType.HASH)
                                .build(),
                            KeySchemaElement.builder()
                                .attributeName("active_history_seq_nr")
                                .keyType(KeyType.RANGE)
                                .build())
                        .projection(
                            Projection.builder().projectionType(ProjectionType.KEYS_ONLY).build())
                        .build())
                .build());
        for (int seq = 1; seq <= 4; seq++) {
          java.util.HashMap<String, AttributeValue> history =
              new java.util.HashMap<>(item("skey", seq));
          history.put("active_history_seq_nr", AttributeValue.fromN(Integer.toString(seq)));
          context.admin.putItem(
              PutItemRequest.builder().tableName(context.snapshot).item(history).build());
        }
        for (FaultRegistry.Injection injection : FaultRegistry.Injection.values()) {
          int operationNumber = injection.ordinal() + 1;
          DynamoDbFaultEffects.HistoryPages plan =
              DynamoDbFaultEffects.historyPages(
                  context.admin,
                  context.snapshot,
                  AID,
                  List.of(List.of(3L, 2L), List.of(1L)),
                  4L,
                  true);
          FaultRegistry.Fault fault =
              context.faults.register(operationNumber, "retention-query", 1, injection, plan);
          FaultRegistry.Operation operation = context.faults.begin(operationNumber, true);
          TransactWriteItemsRequest commit =
              TransactWriteItemsRequest.builder()
                  .transactItems(
                      TransactWriteItem.builder()
                          .put(
                              Put.builder()
                                  .tableName(context.snapshot)
                                  .item(stored(context, 4))
                                  .build())
                          .build())
                  .build();
          if (async) context.async.transactWriteItems(commit).join();
          else context.client.transactWriteItems(commit);
          QueryRequest request =
              QueryRequest.builder()
                  .tableName(context.snapshot)
                  .indexName(context.historyIndex)
                  .keyConditionExpression("aid=:aid")
                  .expressionAttributeValues(Map.of(":aid", AttributeValue.fromS(AID)))
                  .scanIndexForward(false)
                  .build();
          QueryResponse first = query(context, request, async);
          assertEquals(2, first.items().size());
          assertEquals("2", first.lastEvaluatedKey().get("skey").n());
          QueryResponse next =
              query(
                  context,
                  request.toBuilder().exclusiveStartKey(first.lastEvaluatedKey()).build(),
                  async);
          assertEquals("1", next.items().get(0).get("skey").n());
          assertTrue(next.lastEvaluatedKey().isEmpty());
          assertFalse(plan.hasNext());
          assertEquals(1, context.faults.applications(fault));
          List<DynamoDbRequestRecorder.Request> observed =
              context.recorder.requests().stream()
                  .filter(r -> r.operation == operationNumber)
                  .collect(java.util.stream.Collectors.toList());
          assertEquals(3, observed.size());
          assertEquals("commit", observed.get(0).phase);
          assertEquals("TransactWriteItems", observed.get(0).api);
          assertEquals(1, observed.get(0).transmissions);
          assertEquals(
              0,
              observed.stream()
                  .filter(r -> r.phase.equals("classify-condition-failure-read"))
                  .count());
          assertTrue(
              observed.subList(1, observed.size()).stream()
                  .allMatch(
                      r ->
                          r.phase.equals("retention-query")
                              && r.transmissions
                                  == (injection == FaultRegistry.Injection.REPLACE_REQUEST
                                      ? 0
                                      : 1)));
          assertFalse(stored(context, 4).isEmpty());
          assertEquals("passed", context.faults.finish(operation).status);
        }
        assertThrows(
            IllegalArgumentException.class,
            () ->
                DynamoDbFaultEffects.historyPages(
                    context.admin, context.snapshot, AID, List.of(List.of(99L)), null, false));
        assertThrows(
            IllegalArgumentException.class,
            () ->
                DynamoDbFaultEffects.historyPages(
                    context.admin, context.snapshot, AID, List.of(List.of(4L)), 4L, true));
      }
    }
  }

  @Test
  void interleavedResponseHasCapturedHeadAndNewSnapshotWhileRealHeadAdvances() {
    for (boolean async : new boolean[] {false, true}) {
      try (DynamoDbTestContext context = new DynamoDbTestContext(local.endpoint())) {
        context.createMinimalTables();
        context.admin.putItem(
            PutItemRequest.builder()
                .tableName(context.head)
                .item(Map.of("aid", AttributeValue.fromS(AID), "seq_nr", AttributeValue.fromN("1")))
                .build());
        context.admin.putItem(
            PutItemRequest.builder()
                .tableName(context.snapshot)
                .item(
                    Map.of(
                        "aid",
                        AttributeValue.fromS(AID),
                        "skey",
                        AttributeValue.fromN("0"),
                        "seq_nr",
                        AttributeValue.fromN("1")))
                .build());
        context.faults.register(
            1,
            "read-snapshot",
            1,
            FaultRegistry.Injection.REPLACE_RESPONSE,
            DynamoDbFaultEffects.readInterleave(
                context.admin,
                context.head,
                () ->
                    context.admin.transactWriteItems(
                        TransactWriteItemsRequest.builder()
                            .transactItems(
                                TransactWriteItem.builder()
                                    .put(
                                        Put.builder()
                                            .tableName(context.head)
                                            .item(
                                                Map.of(
                                                    "aid",
                                                    AttributeValue.fromS(AID),
                                                    "seq_nr",
                                                    AttributeValue.fromN("2")))
                                            .build())
                                    .build(),
                                TransactWriteItem.builder()
                                    .put(
                                        Put.builder()
                                            .tableName(context.snapshot)
                                            .item(
                                                Map.of(
                                                    "aid",
                                                    AttributeValue.fromS(AID),
                                                    "skey",
                                                    AttributeValue.fromN("0"),
                                                    "seq_nr",
                                                    AttributeValue.fromN("2")))
                                            .build())
                                    .build())
                            .build())));
        FaultRegistry.Operation operation = context.faults.begin(1, false);
        BatchGetItemRequest request =
            BatchGetItemRequest.builder()
                .requestItems(
                    Map.of(
                        context.head,
                            KeysAndAttributes.builder()
                                .keys(Map.of("aid", AttributeValue.fromS(AID)))
                                .consistentRead(true)
                                .build(),
                        context.snapshot,
                            KeysAndAttributes.builder()
                                .keys(key("skey", 0))
                                .consistentRead(true)
                                .build()))
                .build();
        BatchGetItemResponse response =
            async
                ? context.async.batchGetItem(request).join()
                : context.client.batchGetItem(request);
        assertEquals("1", response.responses().get(context.head).get(0).get("seq_nr").n());
        assertEquals("2", response.responses().get(context.snapshot).get(0).get("seq_nr").n());
        assertEquals(
            "2",
            context
                .admin
                .getItem(
                    GetItemRequest.builder()
                        .tableName(context.head)
                        .key(Map.of("aid", AttributeValue.fromS(AID)))
                        .consistentRead(true)
                        .build())
                .item()
                .get("seq_nr")
                .n());
        assertEquals(1, context.recorder.requests().size());
        assertEquals(1, context.recorder.requests().get(0).transmissions);
        assertEquals("passed", context.faults.finish(operation).status);
      }
    }
  }

  @Test
  void installItemsCommitsTheOtherActorsDataBeforeReturningConfigurationConflict() {
    try (DynamoDbTestContext context = new DynamoDbTestContext(local.endpoint())) {
      context.createMinimalTables();
      Map<String, AttributeValue> installed =
          Map.of(
              "aid",
              AttributeValue.fromS("__config__"),
              "store_id",
              AttributeValue.fromS("other-owner"));
      context.faults.register(
          0,
          "configuration-create",
          1,
          FaultRegistry.Injection.REPLACE_REQUEST,
          DynamoDbFaultEffects.transactionCanceled(
              context.targets,
              Map.of("configuration:head", "ConditionalCheckFailed"),
              null,
              () ->
                  context.admin.putItem(
                      PutItemRequest.builder().tableName(context.head).item(installed).build())));
      FaultRegistry.Operation operation = context.faults.begin(0, false);
      TransactWriteItemsRequest request =
          TransactWriteItemsRequest.builder()
              .transactItems(
                  TransactWriteItem.builder()
                      .put(
                          Put.builder()
                              .tableName(context.head)
                              .item(
                                  Map.of(
                                      "aid",
                                      AttributeValue.fromS("__config__"),
                                      "store_id",
                                      AttributeValue.fromS("our-owner")))
                              .conditionExpression("attribute_not_exists(aid)")
                              .build())
                      .build())
              .build();
      assertInstanceOf(
          TransactionCanceledException.class,
          unwrap(assertThrows(RuntimeException.class, () -> transact(context, request, true))));
      assertEquals(
          "other-owner",
          context
              .admin
              .getItem(
                  GetItemRequest.builder()
                      .tableName(context.head)
                      .key(Map.of("aid", AttributeValue.fromS("__config__")))
                      .build())
              .item()
              .get("store_id")
              .s());
      assertEquals(0, context.recorder.requests().get(0).transmissions);
      assertEquals("passed", context.faults.finish(operation).status);
    }
  }

  @Test
  void ttlRequestReplacementLeavesItemUntouchedAndFollowingOperationActuallyMarksIt() {
    try (DynamoDbTestContext context = new DynamoDbTestContext(local.endpoint())) {
      context.createMinimalTables();
      Map<String, AttributeValue> active =
          Map.of(
              "aid",
              AttributeValue.fromS(AID),
              "skey",
              AttributeValue.fromN("1"),
              "active_history_seq_nr",
              AttributeValue.fromN("1"));
      context.admin.putItem(
          PutItemRequest.builder().tableName(context.snapshot).item(active).build());
      context.faults.register(
          1,
          "retention-mark",
          1,
          FaultRegistry.Injection.REPLACE_REQUEST,
          DynamoDbFaultEffects.sdkError("ProvisionedThroughputExceededException"));
      UpdateItemRequest mark =
          UpdateItemRequest.builder()
              .tableName(context.snapshot)
              .key(key("skey", 1))
              .updateExpression("SET #ttl=:expires REMOVE active_history_seq_nr")
              .conditionExpression("attribute_exists(active_history_seq_nr)")
              .expressionAttributeNames(Map.of("#ttl", "ttl"))
              .expressionAttributeValues(Map.of(":expires", AttributeValue.fromN("4102444800")))
              .build();
      FaultRegistry.Operation failed = context.faults.begin(1, true);
      assertInstanceOf(
          ProvisionedThroughputExceededException.class,
          unwrap(
              assertThrows(RuntimeException.class, () -> context.async.updateItem(mark).join())));
      assertEquals(active, stored(context, 1));
      assertEquals("passed", context.faults.finish(failed).status);
      FaultRegistry.Operation next = context.faults.begin(2, true);
      context.async.updateItem(mark).join();
      assertEquals("4102444800", stored(context, 1).get("ttl").n());
      assertFalse(stored(context, 1).containsKey("active_history_seq_nr"));
      assertEquals(
          "4102444800",
          context.recorder.requests().get(1).structure.at("/update/set/ttl/N").asText());
      assertEquals("passed", context.faults.finish(next).status);
    }
  }

  private static BatchWriteItemRequest deletes(DynamoDbTestContext context, long... sequences) {
    List<WriteRequest> writes = new ArrayList<>();
    for (long seq : sequences)
      writes.add(
          WriteRequest.builder()
              .deleteRequest(DeleteRequest.builder().key(key("skey", seq)).build())
              .build());
    return BatchWriteItemRequest.builder().requestItems(Map.of(context.snapshot, writes)).build();
  }

  private static Map<String, AttributeValue> stored(DynamoDbTestContext context, long seq) {
    return context
        .admin
        .getItem(
            GetItemRequest.builder()
                .tableName(context.snapshot)
                .key(key("skey", seq))
                .consistentRead(true)
                .build())
        .item();
  }
}
