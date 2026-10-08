package com.github.j5ik2o.event.store.adapter.java.dynamodbtest;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.core.SdkResponse;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.core.interceptor.Context;
import software.amazon.awssdk.core.interceptor.ExecutionAttributes;
import software.amazon.awssdk.core.interceptor.ExecutionInterceptor;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;

class DynamoDbFaultFoundationTest {
  @RegisterExtension static final DynamoDbLocalExtension local = new DynamoDbLocalExtension();
  private static final String AID = "User-A";

  static Map<String, AttributeValue> key(String sort, long seq) {
    return Map.of("aid", AttributeValue.fromS(AID), sort, AttributeValue.fromN(Long.toString(seq)));
  }

  static Map<String, AttributeValue> item(String sort, long seq) {
    return Map.of(
        "aid",
        AttributeValue.fromS(AID),
        sort,
        AttributeValue.fromN(Long.toString(seq)),
        "payload",
        AttributeValue.fromB(SdkBytes.fromUtf8String("{\"value\":1}")));
  }

  static QueryRequest events(DynamoDbTestContext context) {
    return QueryRequest.builder()
        .tableName(context.journal)
        .consistentRead(true)
        .keyConditionExpression("aid = :aid AND seq_nr >= :seq")
        .expressionAttributeValues(
            Map.of(":aid", AttributeValue.fromS(AID), ":seq", AttributeValue.fromN("1")))
        .build();
  }

  static QueryResponse query(DynamoDbTestContext context, QueryRequest request, boolean async) {
    return async ? context.async.query(request).join() : context.client.query(request);
  }

  static void transact(
      DynamoDbTestContext context, TransactWriteItemsRequest request, boolean async) {
    if (async) context.async.transactWriteItems(request).join();
    else context.client.transactWriteItems(request);
  }

  static Throwable unwrap(Throwable error) {
    while (error instanceof CompletionException && error.getCause() != null)
      error = error.getCause();
    return error;
  }

  @Test
  void localUsesPinnedVersionDynamicEndpointAndRealSyncAndAsyncClients() {
    try (DynamoDbTestContext context = new DynamoDbTestContext(local.endpoint())) {
      context.acquire(DynamoDbTestContext.table(context.head, null));
      assertTrue(local.logs().contains("3.3.1"), local.logs());
      assertEquals("http", local.endpoint().getScheme());
      assertTrue(local.endpoint().getPort() > 0);
      assertTrue(context.admin.listTables().tableNames().contains(context.head));
      assertTrue(context.adminAsync.listTables().join().tableNames().contains(context.head));
      // With -inMemory and no -sharedDb, the explicit credential/region pair sees the same data.
      assertEquals(0, context.recorder.requests().size());
    }
  }

  @Test
  void replaceRequestSkipsBothTransportsAndSdkBuildsCancellationReasonsInActualOrder() {
    for (boolean async : new boolean[] {false, true}) {
      try (DynamoDbTestContext context = new DynamoDbTestContext(local.endpoint())) {
        context.createMinimalTables();
        context.faults.register(
            1,
            "commit",
            1,
            FaultRegistry.Injection.REPLACE_REQUEST,
            DynamoDbFaultEffects.transactionCanceled(
                context.targets,
                Map.of("head", "ConditionalCheckFailed", "journal", "None"),
                7L,
                () -> {}));
        FaultRegistry.Operation operation = context.faults.begin(1, true);
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
                                        AttributeValue.fromS(AID),
                                        "seq_nr",
                                        AttributeValue.fromN("8")))
                                .build())
                        .build(),
                    TransactWriteItem.builder()
                        .put(
                            Put.builder()
                                .tableName(context.journal)
                                .item(item("seq_nr", 8))
                                .build())
                        .build())
                .build();

        Throwable error =
            unwrap(assertThrows(RuntimeException.class, () -> transact(context, request, async)));
        assertInstanceOf(TransactionCanceledException.class, error);
        TransactionCanceledException canceled = (TransactionCanceledException) error;
        assertEquals(400, canceled.statusCode());
        assertEquals("TransactionCanceledException", canceled.awsErrorDetails().errorCode());
        assertEquals(
            List.of("ConditionalCheckFailed", "None"),
            canceled.cancellationReasons().stream()
                .map(CancellationReason::code)
                .collect(java.util.stream.Collectors.toList()));
        assertEquals("7", canceled.cancellationReasons().get(0).item().get("seq_nr").n());
        assertTrue(
            context
                .admin
                .getItem(
                    GetItemRequest.builder()
                        .tableName(context.journal)
                        .key(key("seq_nr", 8))
                        .build())
                .item()
                .isEmpty());
        assertTrue(
            context
                .admin
                .getItem(
                    GetItemRequest.builder()
                        .tableName(context.head)
                        .key(Map.of("aid", AttributeValue.fromS(AID)))
                        .build())
                .item()
                .isEmpty());
        DynamoDbRequestRecorder.Request observed = context.recorder.requests().get(0);
        assertEquals(1, observed.operation);
        assertEquals("TransactWriteItems", observed.api);
        assertEquals("commit", observed.phase);
        assertEquals(1, observed.httpAttempts);
        assertEquals(0, observed.transmissions);
        assertNull(observed.transmitted);
        assertFalse(observed.marshalled.path("ClientRequestToken").asText().isEmpty());
        assertEquals("passed", context.faults.finish(operation).status);
      }
    }
  }

  @Test
  void syncSdkRestoresErrorTypeStatusAndCodeWithoutRetry() {
    assertSdkErrors(false);
  }

  @Test
  void asyncSdkRestoresErrorTypeStatusAndCodeWithoutRetry() {
    assertSdkErrors(true);
  }

  private static void assertSdkErrors(boolean async) {
    try (DynamoDbTestContext context = new DynamoDbTestContext(local.endpoint())) {
      context.createMinimalTables();
      String[] codes = {"InternalServerError", "ProvisionedThroughputExceededException"};
      for (int i = 0; i < codes.length; i++) {
        String code = codes[i];
        FaultRegistry.Fault fault =
            context.faults.register(
                i + 1,
                "read-events",
                1,
                FaultRegistry.Injection.REPLACE_REQUEST,
                DynamoDbFaultEffects.sdkError(code));
        FaultRegistry.Operation operation = context.faults.begin(i + 1, false);
        Throwable failure =
            unwrap(
                assertThrows(RuntimeException.class, () -> query(context, events(context), async)));
        Class<? extends DynamoDbException> expectedType =
            i == 0
                ? InternalServerErrorException.class
                : ProvisionedThroughputExceededException.class;
        assertInstanceOf(expectedType, failure);
        DynamoDbException error = (DynamoDbException) failure;
        assertEquals(i == 0 ? 500 : 400, error.statusCode());
        assertEquals(code, error.awsErrorDetails().errorCode());
        assertEquals(1, context.faults.applications(fault));
        DynamoDbRequestRecorder.Request observed = context.recorder.requests().get(i);
        assertEquals(1, observed.httpAttempts);
        assertEquals(0, observed.transmissions);
        assertNull(observed.transmitted);
        assertEquals("passed", context.faults.finish(operation).status);
      }
    }
  }

  @Test
  void syncSdkFiniteCountRequiresEveryApplicationAndStopsAtTheLimit() {
    assertFiniteCount(false);
  }

  @Test
  void asyncSdkFiniteCountRequiresEveryApplicationAndStopsAtTheLimit() {
    assertFiniteCount(true);
  }

  private static void assertFiniteCount(boolean async) {
    for (FaultRegistry.Injection injection : FaultRegistry.Injection.values()) {
      try (DynamoDbTestContext context = new DynamoDbTestContext(local.endpoint())) {
        context.createMinimalTables();
        context.admin.putItem(
            PutItemRequest.builder().tableName(context.journal).item(item("seq_nr", 1)).build());
        FaultRegistry.Effect effect =
            injection == FaultRegistry.Injection.REPLACE_REQUEST
                ? DynamoDbFaultEffects.sdkError("InternalServerError")
                : DynamoDbFaultEffects.response(
                    response ->
                        ((QueryResponse) response).toBuilder().items(List.of()).count(0).build());
        FaultRegistry.Fault shortfall =
            context.faults.register(1, "read-events", 2, injection, effect);
        FaultRegistry.Operation one = context.faults.begin(1, false);
        assertFaultedQuery(context, injection, async);
        assertEquals(1, context.faults.applications(shortfall));
        assertEquals("failed", context.faults.finish(one).status);

        FaultRegistry.Operation next = context.faults.begin(2, false);
        assertEquals(List.of(item("seq_nr", 1)), query(context, events(context), async).items());
        assertEquals(1, context.faults.applications(shortfall));
        assertEquals("passed", context.faults.finish(next).status);

        FaultRegistry.Fault complete =
            context.faults.register(3, "read-events", 2, injection, effect);
        FaultRegistry.Operation three = context.faults.begin(3, false);
        assertFaultedQuery(context, injection, async);
        assertFaultedQuery(context, injection, async);
        assertEquals(List.of(item("seq_nr", 1)), query(context, events(context), async).items());
        assertEquals(2, context.faults.applications(complete));
        assertEquals("passed", context.faults.finish(three).status);
        List<DynamoDbRequestRecorder.Request> requests = context.recorder.requests();
        assertEquals(5, requests.size());
        for (DynamoDbRequestRecorder.Request request : requests)
          assertEquals(1, request.httpAttempts);
        for (int index : new int[] {0, 2, 3})
          assertEquals(
              injection == FaultRegistry.Injection.REPLACE_REQUEST ? 0 : 1,
              requests.get(index).transmissions);
        assertEquals(1, requests.get(1).transmissions);
        assertEquals(1, requests.get(4).transmissions);
        assertEquals(2, requests.get(1).operation);
      }
    }
  }

  private static void assertFaultedQuery(
      DynamoDbTestContext context, FaultRegistry.Injection injection, boolean async) {
    if (injection == FaultRegistry.Injection.REPLACE_REQUEST) {
      assertInstanceOf(
          InternalServerErrorException.class,
          unwrap(
              assertThrows(RuntimeException.class, () -> query(context, events(context), async))));
    } else assertTrue(query(context, events(context), async).items().isEmpty());
  }

  @Test
  void sdkDoesNotRetryRetryableFailuresInEitherTransport() {
    for (boolean async : new boolean[] {false, true}) {
      try (DynamoDbTestContext context = new DynamoDbTestContext(local.endpoint())) {
        context.createMinimalTables();
        FaultRegistry.Fault fault =
            context.faults.register(
                1,
                "read-events",
                -1,
                FaultRegistry.Injection.REPLACE_REQUEST,
                DynamoDbFaultEffects.sdkError("ProvisionedThroughputExceededException"));
        FaultRegistry.Operation operation = context.faults.begin(1, false);
        Throwable error =
            unwrap(
                assertThrows(RuntimeException.class, () -> query(context, events(context), async)));
        assertInstanceOf(ProvisionedThroughputExceededException.class, error);
        assertEquals(1, context.recorder.requests().size());
        assertEquals(1, context.recorder.requests().get(0).httpAttempts);
        assertEquals(1, context.faults.applications(fault));
        assertEquals("passed", context.faults.finish(operation).status);
      }
    }
  }

  @Test
  void responseReplacementLeavesRealCommitAndIndependentRequestRecords() {
    for (boolean async : new boolean[] {false, true}) {
      try (DynamoDbTestContext context = new DynamoDbTestContext(local.endpoint())) {
        context.createMinimalTables();
        RuntimeException lost = new IllegalStateException("response lost after commit");
        context.faults.register(
            1,
            "commit",
            1,
            FaultRegistry.Injection.REPLACE_RESPONSE,
            DynamoDbFaultEffects.response(
                response -> {
                  throw lost;
                }));
        FaultRegistry.Operation operation = context.faults.begin(1, true);
        byte[] bytes = {1, 2};
        TransactWriteItemsRequest request =
            TransactWriteItemsRequest.builder()
                .transactItems(
                    TransactWriteItem.builder()
                        .put(
                            Put.builder()
                                .tableName(context.journal)
                                .item(
                                    Map.of(
                                        "aid",
                                        AttributeValue.fromS(AID),
                                        "seq_nr",
                                        AttributeValue.fromN("1"),
                                        "payload",
                                        AttributeValue.fromB(SdkBytes.fromByteArray(bytes))))
                                .build())
                        .build())
                .build();
        Throwable failure =
            unwrap(assertThrows(RuntimeException.class, () -> transact(context, request, async)));
        assertInstanceOf(software.amazon.awssdk.core.exception.SdkClientException.class, failure);
        assertSame(lost, failure.getCause());
        assertArrayEquals(
            bytes,
            context
                .admin
                .getItem(
                    GetItemRequest.builder()
                        .tableName(context.journal)
                        .key(key("seq_nr", 1))
                        .consistentRead(true)
                        .build())
                .item()
                .get("payload")
                .b()
                .asByteArray());
        DynamoDbRequestRecorder.Request recorded = context.recorder.requests().get(0);
        assertEquals(1, recorded.transmissions);
        assertEquals(recorded.marshalled, recorded.transmitted);
        ((ObjectNode) recorded.marshalled.get("TransactItems").get(0).get("Put").get("Item"))
            .removeAll();
        ((ObjectNode) recorded.original).removeAll();
        ((ObjectNode) recorded.structure).removeAll();
        ((ObjectNode) recorded.transmitted).removeAll();
        context.recorder.requests().clear();
        assertEquals(
            "AQI=",
            context
                .recorder
                .requests()
                .get(0)
                .marshalled
                .at("/TransactItems/0/Put/Item/payload/B")
                .asText());
        assertFalse(context.recorder.requests().get(0).original.isEmpty());
        assertEquals("passed", context.faults.finish(operation).status);
      }
    }
  }

  @Test
  void countAndContinuousFaultsExpireBeforeTheNextOperationOnTheSameClients() {
    for (boolean async : new boolean[] {false, true}) {
      try (DynamoDbTestContext context = new DynamoDbTestContext(local.endpoint())) {
        context.createMinimalTables();
        context.admin.putItem(
            PutItemRequest.builder().tableName(context.journal).item(item("seq_nr", 1)).build());
        FaultRegistry.Fault first =
            context.faults.register(
                1,
                "read-events",
                1,
                FaultRegistry.Injection.REPLACE_REQUEST,
                DynamoDbFaultEffects.sdkError("InternalServerError"));
        FaultRegistry.Fault continuous =
            context.faults.register(
                1,
                "read-events",
                -1,
                FaultRegistry.Injection.REPLACE_REQUEST,
                DynamoDbFaultEffects.sdkError("ProvisionedThroughputExceededException"));
        FaultRegistry.Operation one = context.faults.begin(1, false);
        assertInstanceOf(
            InternalServerErrorException.class,
            unwrap(
                assertThrows(
                    RuntimeException.class, () -> query(context, events(context), async))));
        for (int i = 0; i < 2; i++)
          assertInstanceOf(
              ProvisionedThroughputExceededException.class,
              unwrap(
                  assertThrows(
                      RuntimeException.class, () -> query(context, events(context), async))));
        assertEquals(1, context.faults.applications(first));
        assertEquals(2, context.faults.applications(continuous));
        assertEquals("passed", context.faults.finish(one).status);
        FaultRegistry.Operation two = context.faults.begin(2, false);
        assertEquals(1, query(context, events(context), async).items().size());
        assertEquals(2, context.recorder.requests().get(3).operation);
        assertEquals(1, context.recorder.requests().get(3).transmissions);
        assertEquals(1, context.faults.applications(first));
        assertEquals(2, context.faults.applications(continuous));
        assertEquals("passed", context.faults.finish(two).status);
      }
    }
  }

  @Test
  void readResponseCanBeReplacedAfterRealTransmission() {
    try (DynamoDbTestContext context = new DynamoDbTestContext(local.endpoint())) {
      context.createMinimalTables();
      context.admin.putItem(
          PutItemRequest.builder().tableName(context.journal).item(item("seq_nr", 1)).build());
      context.faults.register(
          1,
          "read-events",
          1,
          FaultRegistry.Injection.REPLACE_RESPONSE,
          DynamoDbFaultEffects.response(
              response ->
                  ((QueryResponse) response).toBuilder().items(List.of()).count(0).build()));
      FaultRegistry.Operation operation = context.faults.begin(1, false);
      assertTrue(query(context, events(context), true).items().isEmpty());
      assertEquals(1, context.admin.query(events(context)).items().size());
      assertEquals(1, context.recorder.requests().get(0).transmissions);
      assertEquals("passed", context.faults.finish(operation).status);
    }
  }

  @Test
  void failureAfterAcquisitionPreservesOriginalErrorAndDeletesActualResource() {
    DynamoDbTestContext context = new DynamoDbTestContext(local.endpoint());
    RuntimeException original = new IllegalArgumentException("original acquisition failure");
    RuntimeException cleanupFailure = new IllegalStateException("cleanup failure");
    try (DynamoDbClient inspector = DynamoDbTestClients.admin(local.endpoint())) {
      try {
        assertSame(
            original,
            unwrap(
                assertThrows(
                    CompletionException.class,
                    () ->
                        context
                            .acquireAsync(
                                DynamoDbTestContext.table(context.head, null),
                                () -> {
                                  throw original;
                                })
                            .join())));
        assertNotNull(
            inspector
                .describeTable(DescribeTableRequest.builder().tableName(context.head).build())
                .table());
        assertSame(
            original,
            context
                .finish(
                    original,
                    () -> {
                      throw cleanupFailure;
                    })
                .join());
        assertArrayEquals(new Throwable[] {cleanupFailure}, original.getSuppressed());
        assertTrue(context.closed());
        assertThrows(
            ResourceNotFoundException.class,
            () ->
                inspector.describeTable(
                    DescribeTableRequest.builder().tableName(context.head).build()));
      } finally {
        if (!context.closed()) context.close();
      }
    }
  }

  @Test
  void sdkResponseLossAfterActualCreateReleasesAcquisitionCandidate() {
    for (boolean asyncAcquisition : new boolean[] {false, true}) {
      RuntimeException lost = new IllegalStateException("SDK CreateTable response lost");
      RuntimeException cleanupFailure = new IllegalStateException("cleanup failure");
      AtomicInteger sdkFailures = new AtomicInteger();
      AtomicInteger sdkSuccesses = new AtomicInteger();
      AtomicInteger acquisitionSuccesses = new AtomicInteger();
      ExecutionInterceptor interceptor =
          new ExecutionInterceptor() {
            @Override
            public SdkResponse modifyResponse(
                Context.ModifyResponse response, ExecutionAttributes attrs) {
              if (response.response() instanceof CreateTableResponse) throw lost;
              return response.response();
            }

            @Override
            public void onExecutionFailure(
                Context.FailedExecution failure, ExecutionAttributes attrs) {
              sdkFailures.incrementAndGet();
            }

            @Override
            public void afterExecution(Context.AfterExecution response, ExecutionAttributes attrs) {
              sdkSuccesses.incrementAndGet();
            }
          };
      DynamoDbTestContext context = new DynamoDbTestContext(local.endpoint(), interceptor);
      try (DynamoDbClient inspector = DynamoDbTestClients.admin(local.endpoint())) {
        try {
          CreateTableRequest request = DynamoDbTestContext.table(context.head, null);
          Throwable failure =
              unwrap(
                  assertThrows(
                      CompletionException.class,
                      () -> {
                        if (asyncAcquisition)
                          context
                              .acquireAsync(request, acquisitionSuccesses::incrementAndGet)
                              .join();
                        else context.acquire(request);
                      }));
          assertInstanceOf(SdkClientException.class, failure);
          assertSame(lost, failure.getCause());
          assertEquals(1, sdkFailures.get());
          assertEquals(0, sdkSuccesses.get());
          assertEquals(0, acquisitionSuccesses.get());
          assertNotNull(
              inspector
                  .describeTable(DescribeTableRequest.builder().tableName(context.head).build())
                  .table());
          assertSame(
              failure,
              context
                  .finish(
                      failure,
                      () -> {
                        throw cleanupFailure;
                      })
                  .join());
          assertArrayEquals(new Throwable[] {cleanupFailure}, failure.getSuppressed());
          assertTrue(context.closed());
          assertThrows(
              ResourceNotFoundException.class,
              () ->
                  inspector.describeTable(
                      DescribeTableRequest.builder().tableName(context.head).build()));
        } finally {
          if (!context.closed()) context.close();
          // Also release the reproduced leak if this assertion fails before the fix.
          try {
            inspector.deleteTable(DeleteTableRequest.builder().tableName(context.head).build());
          } catch (ResourceNotFoundException absent) {
            // The assertion above already verified deletion after successful cleanup.
          }
        }
      }
    }
  }

  @Test
  void sdkFailureBeforeCreationDoesNotDeleteAnotherOwnersTable() {
    try (DynamoDbTestContext owner = new DynamoDbTestContext(local.endpoint())) {
      owner.acquire(DynamoDbTestContext.table(owner.head, null));
      owner.admin.putItem(
          PutItemRequest.builder().tableName(owner.head).item(item("seq_nr", 42)).build());
      for (boolean ownName : new boolean[] {true, false}) {
        RuntimeException lost = new IllegalStateException("SDK failure before CreateTable");
        ExecutionInterceptor interceptor =
            new ExecutionInterceptor() {
              @Override
              public void beforeExecution(
                  Context.BeforeExecution request, ExecutionAttributes attrs) {
                throw lost;
              }
            };
        DynamoDbTestContext context = new DynamoDbTestContext(local.endpoint(), interceptor);
        try {
          String name = ownName ? context.head : owner.head;
          Throwable failure =
              unwrap(
                  assertThrows(
                      CompletionException.class,
                      () -> context.acquire(DynamoDbTestContext.table(name, null))));
          assertSame(lost, failure);
          assertSame(failure, context.finish(failure).join());
          assertEquals(0, failure.getSuppressed().length);
          assertTrue(context.closed());
          if (ownName) {
            assertThrows(
                ResourceNotFoundException.class,
                () ->
                    owner.admin.describeTable(
                        DescribeTableRequest.builder().tableName(name).build()));
          }
          assertEquals(
              "42",
              owner
                  .admin
                  .getItem(
                      GetItemRequest.builder()
                          .tableName(owner.head)
                          .key(Map.of("aid", AttributeValue.fromS(AID)))
                          .consistentRead(true)
                          .build())
                  .item()
                  .get("seq_nr")
                  .n());
        } finally {
          if (!context.closed()) context.close();
        }
      }
    }
  }

  @Test
  void subsequentSameNameConflictKeepsTheEarlierSuccessfulAcquisitionForCleanup() {
    DynamoDbTestContext context = new DynamoDbTestContext(local.endpoint());
    try (DynamoDbClient inspector = DynamoDbTestClients.admin(local.endpoint())) {
      try {
        CreateTableRequest request = DynamoDbTestContext.table(context.head, null);
        context.acquire(request);
        Throwable conflict =
            unwrap(assertThrows(CompletionException.class, () -> context.acquire(request)));
        assertInstanceOf(ResourceInUseException.class, conflict);
        assertNotNull(
            inspector
                .describeTable(DescribeTableRequest.builder().tableName(context.head).build())
                .table());
        assertSame(conflict, context.finish(conflict).join());
        assertEquals(0, conflict.getSuppressed().length);
        assertThrows(
            ResourceNotFoundException.class,
            () ->
                inspector.describeTable(
                    DescribeTableRequest.builder().tableName(context.head).build()));
      } finally {
        if (!context.closed()) context.close();
      }
    }
  }

  @Test
  void cancellationAfterActualCreationWaitsForAcquisitionAndUsesIndependentTermination()
      throws Exception {
    DynamoDbTestContext context = new DynamoDbTestContext(local.endpoint());
    CountDownLatch created = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    java.util.concurrent.CancellationException cancelled =
        new java.util.concurrent.CancellationException("operation cancelled");
    try (DynamoDbClient inspector = DynamoDbTestClients.admin(local.endpoint())) {
      try {
        CompletableFuture<CreateTableResponse> acquisition =
            context.acquireAsync(
                DynamoDbTestContext.table(context.head, null),
                () -> {
                  created.countDown();
                  await(release);
                });
        assertTrue(created.await(10, TimeUnit.SECONDS));
        assertNotNull(
            inspector
                .describeTable(DescribeTableRequest.builder().tableName(context.head).build())
                .table());
        assertTrue(acquisition.cancel(true));
        CompletableFuture<Throwable> closing = context.finish(cancelled);
        assertFalse(closing.isDone());
        release.countDown();
        assertSame(cancelled, closing.get(10, TimeUnit.SECONDS));
        assertTrue(acquisition.isCancelled());
        assertTrue(context.closed());
        assertThrows(
            ResourceNotFoundException.class,
            () ->
                inspector.describeTable(
                    DescribeTableRequest.builder().tableName(context.head).build()));
      } finally {
        release.countDown();
        if (!context.closed()) context.close();
      }
    }
  }

  @Test
  void deterministicSameNameConflictLeavesOtherOwnersActualData() {
    try (DynamoDbTestContext owner = new DynamoDbTestContext(local.endpoint());
        DynamoDbClient inspector = DynamoDbTestClients.admin(local.endpoint())) {
      DynamoDbTestContext competitor = new DynamoDbTestContext(local.endpoint());
      try {
        owner.acquire(DynamoDbTestContext.table(competitor.head, null));
        owner.admin.putItem(
            PutItemRequest.builder()
                .tableName(competitor.head)
                .item(
                    Map.of("aid", AttributeValue.fromS(AID), "seq_nr", AttributeValue.fromN("42")))
                .build());
        Throwable conflict =
            unwrap(
                assertThrows(
                    CompletionException.class,
                    () -> competitor.acquire(DynamoDbTestContext.table(competitor.head, null))));
        assertInstanceOf(ResourceInUseException.class, conflict);
        assertSame(conflict, competitor.finish(conflict).join());
        assertEquals(
            "42",
            inspector
                .getItem(
                    GetItemRequest.builder()
                        .tableName(competitor.head)
                        .key(Map.of("aid", AttributeValue.fromS(AID)))
                        .consistentRead(true)
                        .build())
                .item()
                .get("seq_nr")
                .n());
      } finally {
        if (!competitor.closed()) competitor.close();
      }
    }
  }

  static void await(CountDownLatch latch) {
    try {
      if (!latch.await(10, TimeUnit.SECONDS)) throw new AssertionError("Gate was not released");
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      throw new AssertionError(error);
    }
  }

  @Test
  void cancelledSdkOperationDrainsItsCapturedRequestAndCannotAffectTheNextOperation()
      throws Exception {
    try (DynamoDbTestContext context = new DynamoDbTestContext(local.endpoint())) {
      context.createMinimalTables();
      context.admin.putItem(
          PutItemRequest.builder().tableName(context.journal).item(item("seq_nr", 1)).build());
      CountDownLatch responseReached = new CountDownLatch(1);
      CountDownLatch release = new CountDownLatch(1);
      context.faults.register(
          1,
          "read-events",
          -1,
          FaultRegistry.Injection.REPLACE_RESPONSE,
          DynamoDbFaultEffects.response(
              response -> {
                responseReached.countDown();
                await(release);
                return ((QueryResponse) response).toBuilder().items(List.of()).count(0).build();
              }));
      FaultRegistry.Operation operation = context.faults.begin(1, false);
      try {
        CompletableFuture<QueryResponse> request = context.async.query(events(context));
        assertTrue(responseReached.await(10, TimeUnit.SECONDS));
        assertTrue(request.cancel(true));
        assertThrows(IllegalStateException.class, () -> context.faults.finish(operation));
        release.countDown();
        context.recorder.requestsFinished(operation).get(10, TimeUnit.SECONDS);
        assertEquals("passed", context.faults.finish(operation).status);
        assertTrue(request.isCancelled());
        FaultRegistry.Operation next = context.faults.begin(2, false);
        assertEquals(1, context.async.query(events(context)).join().items().size());
        assertEquals(1, context.recorder.requests().get(0).operation);
        assertEquals(2, context.recorder.requests().get(1).operation);
        assertEquals("passed", context.faults.finish(next).status);
      } finally {
        release.countDown();
      }
    }
  }
}
