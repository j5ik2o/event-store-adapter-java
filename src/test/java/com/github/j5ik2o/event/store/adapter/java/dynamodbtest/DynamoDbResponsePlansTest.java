package com.github.j5ik2o.event.store.adapter.java.dynamodbtest;

import static com.github.j5ik2o.event.store.adapter.java.dynamodbtest.DynamoDbFaultFoundationTest.*;
import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import software.amazon.awssdk.services.dynamodb.model.*;

class DynamoDbResponsePlansTest {
  @RegisterExtension static final DynamoDbLocalExtension local = new DynamoDbLocalExtension();
  private static final String AID = "User-A";

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
