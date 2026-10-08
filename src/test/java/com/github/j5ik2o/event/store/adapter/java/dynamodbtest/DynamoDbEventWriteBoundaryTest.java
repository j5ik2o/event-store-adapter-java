package com.github.j5ik2o.event.store.adapter.java.dynamodbtest;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.j5ik2o.event.store.adapter.java.core.*;
import com.github.j5ik2o.event.store.adapter.java.dynamodb.*;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.extension.RegisterExtension;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.core.interceptor.Context;
import software.amazon.awssdk.core.interceptor.ExecutionAttributes;
import software.amazon.awssdk.core.interceptor.ExecutionInterceptor;
import software.amazon.awssdk.http.SdkHttpRequest;
import software.amazon.awssdk.http.apache5.Apache5HttpClient;
import software.amazon.awssdk.http.nio.netty.SdkEventLoopGroup;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;

/** Direct write acceptance; distributed conformance classification is deliberately unchanged. */
class DynamoDbEventWriteBoundaryTest {
  @RegisterExtension
  static final DynamoDbConfigurationFixture fixture = new DynamoDbConfigurationFixture();

  private static final AggregateId AID = AggregateId.of("A", "x");

  private interface Scenario {
    void run(Scene scene) throws Exception;
  }

  private Stream<DynamicTest> both(String name, Scenario scenario) {
    return Stream.of(false, true)
        .map(
            async ->
                DynamicTest.dynamicTest(
                    name + (async ? " async" : " sync"),
                    () -> {
                      try (Scene scene = new Scene(name, async)) {
                        scenario.run(scene);
                      }
                    }));
  }

  private static EventEnvelope<byte[]> event(
      long seq, long nanos, String manifest, byte[] payload) {
    return EventEnvelope.<byte[]>builder()
        .aggregateId(AID)
        .seqNr(seq)
        .occurredAt(Instant.ofEpochSecond(0, nanos))
        .manifest(manifest)
        .payload(payload)
        .build();
  }

  private static EventEnvelope<byte[]> event(long seq) {
    return event(seq, -1, "書式é", new byte[] {0, 1, (byte) 255});
  }

  private static Map<String, AttributeValue> journal(EventEnvelope<byte[]> event, long nanos) {
    return Map.of(
        "aid",
        AttributeValue.fromS(event.aggregateId().asString()),
        "seq_nr",
        AttributeValue.fromN(Long.toString(event.seqNr())),
        "occurred_at",
        AttributeValue.fromN(Long.toString(nanos)),
        "manifest",
        AttributeValue.fromS(event.manifest()),
        "payload",
        AttributeValue.fromB(SdkBytes.fromByteArray(event.payload())));
  }

  private static Map<String, AttributeValue> head(EventEnvelope<byte[]> event, long nanos) {
    Map<String, AttributeValue> envelope = new LinkedHashMap<>(journal(event, nanos));
    envelope.remove("aid");
    return Map.of(
        "aid",
        AttributeValue.fromS(event.aggregateId().asString()),
        "type_name",
        AttributeValue.fromS(event.aggregateId().typeName()),
        "seq_nr",
        AttributeValue.fromN(Long.toString(event.seqNr())),
        "events",
        AttributeValue.fromL(List.of(AttributeValue.fromM(envelope))));
  }

  @TestFactory
  Stream<DynamicTest> createAndAppendKeepAllAttributesAndExistingSnapshots() {
    return both(
        "create-and-append",
        s -> {
          Map<String, AttributeValue> snapshot =
              Map.of(
                  "aid",
                  AttributeValue.fromS("A-x"),
                  "skey",
                  AttributeValue.fromN("0"),
                  "seq_nr",
                  AttributeValue.fromN("1"),
                  "manifest",
                  AttributeValue.fromS("snapshot"),
                  "payload",
                  AttributeValue.fromB(SdkBytes.fromByteArray(new byte[] {7})),
                  "last_updated_at",
                  AttributeValue.fromN("0"));
          Map<String, AttributeValue> history = new LinkedHashMap<>(snapshot);
          history.put("skey", AttributeValue.fromN("1"));
          history.put("active_history_seq_nr", AttributeValue.fromN("1"));
          s.c.admin.putItem(
              PutItemRequest.builder().tableName(s.c.snapshot).item(snapshot).build());
          s.c.admin.putItem(PutItemRequest.builder().tableName(s.c.snapshot).item(history).build());
          List<Map<String, AttributeValue>> snapshots = s.rows(s.c.snapshot);
          for (long seq : List.of(1L, 2L, 3L)) {
            EventEnvelope<byte[]> event = event(seq);
            assertNull(s.write(event, 1, 1));
            s.assertSaved(event, -1);
            assertEquals(snapshots, s.rows(s.c.snapshot));
            assertEquals(seq, s.rows(s.c.journal).size());
          }
          assertEquals(3, s.serializeCalls.get());
          assertEquals(0, s.snapshotCalls.get());
        });
  }

  @TestFactory
  Stream<DynamicTest> realConditionalFailuresAreAtomicAndAllowNextAppendOnTheSameStore() {
    return both(
        "real-conditions",
        s -> {
          assertNull(s.write(event(1), 1, 1));
          assertNull(s.write(event(2), 1, 1));
          for (long seq : List.of(1L, 2L, 4L)) {
            Map<String, Object> before = s.stored();
            Throwable failure = s.write(event(seq), 1, 1);
            if (seq == 4) s.assertGap(failure, seq);
            else assertInstanceOf(OptimisticLockException.class, failure);
            assertEquals(before, s.stored());
          }
          // A stale head condition is observed even when the journal row itself is absent.
          s.c.admin.deleteItem(
              DeleteItemRequest.builder()
                  .tableName(s.c.journal)
                  .key(
                      Map.of(
                          "aid", AttributeValue.fromS("A-x"), "seq_nr", AttributeValue.fromN("1")))
                  .build());
          Map<String, Object> before = s.stored();
          assertInstanceOf(OptimisticLockException.class, s.write(event(1), 1, 1));
          assertEquals(before, s.stored());
          assertNull(s.write(event(3), 1, 1));
          s.assertSaved(event(3), -1);
          s.c.admin.deleteItem(
              DeleteItemRequest.builder()
                  .tableName(s.c.journal)
                  .key(
                      Map.of(
                          "aid", AttributeValue.fromS("A-x"), "seq_nr", AttributeValue.fromN("2")))
                  .build());
          before = s.stored();
          assertInstanceOf(OptimisticLockException.class, s.write(event(2), 1, 1));
          assertEquals(before, s.stored());
          assertNull(s.write(event(4), 1, 1));
          s.assertSaved(event(4), -1);
        });
  }

  @TestFactory
  Stream<DynamicTest> missingHeadAndJournalCollisionDoNotPartiallyCommit() {
    return both(
        "missing-head-and-journal-collision",
        s -> {
          for (long seq : List.of(2L, 3L)) {
            Map<String, Object> before = s.stored();
            s.assertGap(s.write(event(seq), 1, 1), seq);
            assertEquals(before, s.stored());
          }
          Map<String, AttributeValue> collision = journal(event(1), -1);
          s.c.admin.putItem(
              PutItemRequest.builder().tableName(s.c.journal).item(collision).build());
          Map<String, Object> before = s.stored();
          assertInstanceOf(OptimisticLockException.class, s.write(event(1), 1, 1));
          assertEquals(before, s.stored());
          assertTrue(s.head().isEmpty());
        });
  }

  @TestFactory
  Stream<DynamicTest> journalCollisionAloneKeepsExistingHead() {
    return both(
        "journal-collision-alone",
        s -> {
          assertNull(s.write(event(1), 1, 1));
          s.c.admin.putItem(
              PutItemRequest.builder().tableName(s.c.journal).item(journal(event(2), -1)).build());
          Map<String, Object> before = s.stored();
          assertInstanceOf(OptimisticLockException.class, s.write(event(2), 1, 1));
          assertEquals(before, s.stored());
        });
  }

  @TestFactory
  Stream<DynamicTest> returnedOldHeadAndMultipleReasonsDetermineClassificationWithoutReads() {
    return both(
        "cancellation-priority",
        s -> {
          assertNull(s.write(event(1), 1, 1));
          String[][] codes = {
            {"TransactionConflict", "ConditionalCheckFailed"},
            {"ConditionalCheckFailed", "TransactionConflict"},
            {"ConditionalCheckFailed", "ConditionalCheckFailed"},
            {"ThrottlingError", "ConditionalCheckFailed"},
            {"ConditionalCheckFailed", "ThrottlingError"},
            {"ThrottlingError", "None"}
          };
          for (int i = 0; i < codes.length; i++) {
            Map<String, Object> before = s.stored();
            s.cancel(codes[i][0], codes[i][1], 1L);
            Throwable failure = s.write(event(3), 1, 0);
            if (i == 2 || i == 3) s.assertGap(failure, 3);
            else if (i == 5) assertInstanceOf(StorageException.class, failure);
            else {
              assertInstanceOf(OptimisticLockException.class, failure);
              assertFalse(failure.getMessage().contains("Injected DynamoDB test failure"));
              assertInstanceOf(TransactionCanceledException.class, failure.getCause());
            }
            assertEquals(before, s.stored());
          }
          // Actual stored head is 1; the injected exception reports 3. Classification must use 3.
          s.cancel("None", "ConditionalCheckFailed", 3L);
          Map<String, Object> before = s.stored();
          Throwable failure = s.write(event(2), 1, 0);
          assertInstanceOf(OptimisticLockException.class, failure);
          assertEquals(
              "3",
              ((TransactionCanceledException) failure.getCause())
                  .cancellationReasons()
                  .get(1)
                  .item()
                  .get("seq_nr")
                  .n());
          assertEquals("1", s.head().get("seq_nr").n());
          assertEquals(before, s.stored());
          s.cancel("None", "ConditionalCheckFailed", null);
          s.assertGap(s.write(event(2), 1, 0), 2);
          assertEquals(before, s.stored());
          assertNull(s.write(event(2), 1, 1));
        });
  }

  @TestFactory
  Stream<DynamicTest> serializerFailureAndInputRejectionNeverSubmitARequest() {
    return both(
        "pre-send-rejection",
        s -> {
          Map<String, Object> before = s.stored();
          for (RuntimeException error :
              List.of(
                  new SerializationException("serializer failure"),
                  new IllegalStateException("serializer failure"))) {
            s.register(
                "serialize-event",
                FaultRegistry.Injection.REPLACE_REQUEST,
                DynamoDbFaultEffects.serializationError(error));
            Throwable failure = s.write(event(1), 0, 0);
            assertInstanceOf(SerializationException.class, failure);
            assertEquals(before, s.stored());
          }
          int calls = s.serializeCalls.get();
          assertInstanceOf(ContractViolationException.class, s.write(null, 0, 0));
          for (long seq : List.of(-1L, 0L, 1L << 53)) {
            EventEnvelope<byte[]> invalid = event(1);
            set(invalid, "seqNr", seq);
            Throwable failure = s.write(invalid, 0, 0);
            assertEquals(seq == 0 ? "W-6" : "T-9", ((ContractViolationException) failure).rule());
          }
          for (Instant time :
              List.of(
                  Instant.ofEpochSecond(0, Long.MIN_VALUE).minusNanos(1),
                  Instant.ofEpochSecond(0, Long.MAX_VALUE).plusNanos(1))) {
            EventEnvelope<byte[]> invalid = event(1);
            set(invalid, "occurredAt", time);
            assertEquals("T-13", ((ContractViolationException) s.write(invalid, 0, 0)).rule());
          }
          assertEquals(calls, s.serializeCalls.get());
          assertEquals(before, s.stored());
          assertNull(s.write(event(1), 1, 1));
        });
  }

  private static void set(Object target, String fieldName, Object value) throws Exception {
    Field field = target.getClass().getDeclaredField(fieldName);
    field.setAccessible(true);
    field.set(target, value);
  }

  @TestFactory
  Stream<DynamicTest> sdkFailuresDoNotCommitOrReadAndDoNotPoisonTheNextWrite() {
    return both(
        "sdk-errors",
        s -> {
          for (String code :
              List.of("ProvisionedThroughputExceededException", "InternalServerError")) {
            Map<String, Object> before = s.stored();
            s.register(
                "commit",
                FaultRegistry.Injection.REPLACE_REQUEST,
                DynamoDbFaultEffects.sdkError(code));
            Throwable failure = s.write(event(1), 1, 0);
            assertInstanceOf(StorageException.class, failure);
            assertInstanceOf(DynamoDbException.class, failure.getCause());
            assertEquals(before, s.stored());
          }
          assertNull(s.write(event(1), 1, 1));
        });
  }

  @TestFactory
  Stream<DynamicTest> realCommunicationFailureAfterGenerationIsStorageAndTheSameStoreCanRecover() {
    return both(
        "communication-failure",
        s -> {
          AtomicBoolean disconnected = new AtomicBoolean();
          ExecutionInterceptor connection =
              new ExecutionInterceptor() {
                @Override
                public SdkHttpRequest modifyHttpRequest(
                    Context.ModifyHttpRequest context, ExecutionAttributes attributes) {
                  if (disconnected.get()
                      && context.request() instanceof TransactWriteItemsRequest) {
                    return context.httpRequest().toBuilder()
                        .host("127.0.0.1")
                        .port(1)
                        .protocol("http")
                        .build();
                  }
                  return context.httpRequest();
                }
              };
          StaticCredentialsProvider credentials =
              StaticCredentialsProvider.create(
                  AwsBasicCredentials.create(
                      DynamoDbTestClients.ACCESS_KEY, "DynamoDbLocalDummySecret"));
          if (s.asynchronous) {
            SdkEventLoopGroup group = SdkEventLoopGroup.builder().numberOfThreads(2).build();
            try (FaultAsyncHttpClient http =
                    new FaultAsyncHttpClient(
                        DynamoDbTestClients.asyncHttp(group).build(), s.c.recorder);
                DynamoDbAsyncClient client =
                    DynamoDbAsyncClient.builder()
                        .endpointOverride(fixture.endpoint())
                        .region(DynamoDbTestClients.REGION)
                        .credentialsProvider(credentials)
                        .overrideConfiguration(
                            DynamoDbTestClients.overrides()
                                .addExecutionInterceptor(connection)
                                .addExecutionInterceptor(s.c.recorder)
                                .build())
                        .httpClient(http)
                        .build()) {
              FaultRegistry.Operation generation = s.c.faults.begin(s.nextOperation++, false);
              s.asyncStore = DynamoDbEventStore.createAsync(client, s.tables, s.config).join();
              s.finishGeneration(generation);
              exerciseConnection(s, disconnected);
            } finally {
              io.netty.util.concurrent.Future<?> termination =
                  group.eventLoopGroup().shutdownGracefully(0, 15, TimeUnit.SECONDS);
              termination.awaitUninterruptibly();
              s.observation.put(
                  "additional_event_loop_terminated", group.eventLoopGroup().isTerminated());
              assertTrue(termination.isSuccess());
              assertTrue(group.eventLoopGroup().isTerminated());
            }
          } else {
            try (FaultHttpClient http =
                    new FaultHttpClient(Apache5HttpClient.builder().build(), s.c.recorder);
                DynamoDbClient client =
                    DynamoDbClient.builder()
                        .endpointOverride(fixture.endpoint())
                        .region(DynamoDbTestClients.REGION)
                        .credentialsProvider(credentials)
                        .overrideConfiguration(
                            DynamoDbTestClients.overrides()
                                .addExecutionInterceptor(connection)
                                .addExecutionInterceptor(s.c.recorder)
                                .build())
                        .httpClient(http)
                        .build()) {
              FaultRegistry.Operation generation = s.c.faults.begin(s.nextOperation++, false);
              s.syncStore = DynamoDbEventStore.create(client, s.tables, s.config);
              s.finishGeneration(generation);
              exerciseConnection(s, disconnected);
            }
          }
          s.observation.put("additional_clients_and_transports_closed", true);
        });
  }

  private static void exerciseConnection(Scene s, AtomicBoolean disconnected) throws Exception {
    disconnected.set(true);
    Map<String, Object> before = s.stored();
    Throwable failure = s.write(event(1), 1, 1);
    assertInstanceOf(StorageException.class, failure);
    assertInstanceOf(SdkClientException.class, failure.getCause());
    assertEquals(before, s.stored());
    disconnected.set(false);
    assertNull(s.write(event(1), 1, 1));
    s.assertSaved(event(1), -1);
  }

  @TestFactory
  Stream<DynamicTest> signedTimeBoundariesEmptyManifestAndMaximumSequenceAreStoredExactly() {
    return both(
        "value-boundaries",
        s -> {
          int seq = 1;
          for (long nanos :
              List.of(
                  Long.MIN_VALUE,
                  Long.MIN_VALUE + 1,
                  -1L,
                  0L,
                  1L,
                  Long.MAX_VALUE - 1,
                  Long.MAX_VALUE)) {
            EventEnvelope<byte[]> event = event(seq++, nanos, "", new byte[0]);
            assertNull(s.write(event, 1, 1));
            s.assertSaved(event, nanos);
          }
          long maximum = (1L << 53) - 1;
          s.c.admin.putItem(
              PutItemRequest.builder()
                  .tableName(s.c.head)
                  .item(head(event(maximum - 1), -1))
                  .build());
          EventEnvelope<byte[]> last = event(maximum);
          assertNull(s.write(last, 1, 1));
          s.assertSaved(last, -1);
          Map<String, Object> before = s.stored();
          assertInstanceOf(OptimisticLockException.class, s.write(last, 1, 1));
          assertEquals(before, s.stored());
        });
  }

  @TestFactory
  Stream<DynamicTest> headAndJournalSizeBoundariesAreObservedBeforeSending() {
    return both(
        "size-boundaries",
        s -> {
          // Independently derived AWS counts: head 77 + B; journal 42 + B, for A-x/1ns/empty
          // manifest.
          byte[] exact = new byte[409600 - 77];
          Arrays.fill(exact, (byte) 0xa5);
          for (long seq : List.of(1L, 2L)) {
            Map<String, Object> before = s.stored();
            for (int bytes : List.of(409601 - 77, 409600 - 42, 409601 - 42)) {
              assertEquals(
                  "D-7",
                  ((ContractViolationException) s.write(event(seq, 1, "", new byte[bytes]), 0, 0))
                      .rule());
              assertEquals(before, s.stored());
            }
            EventEnvelope<byte[]> event = event(seq, 1, "", exact);
            assertNull(s.write(event, 1, 1));
            s.assertSaved(event, 1);
          }
        });
  }

  @TestFactory
  Stream<DynamicTest> multibyteMetadataAndNumericWidthsChangeTheBoundary() {
    return both(
        "metadata-size-boundaries",
        s -> {
          // head: 77 + manifest UTF-8(3) + occurred_at's extra 9 bytes for 19 significant digits.
          byte[] exact = new byte[409600 - 89];
          EventEnvelope<byte[]> tooLarge =
              event(1, Long.MAX_VALUE, "界", new byte[exact.length + 1]);
          assertEquals("D-7", ((ContractViolationException) s.write(tooLarge, 0, 0)).rule());
          EventEnvelope<byte[]> event = event(1, Long.MAX_VALUE, "界", exact);
          assertNull(s.write(event, 1, 1));
          s.assertSaved(event, Long.MAX_VALUE);
        });
  }

  @TestFactory
  Stream<DynamicTest> asynchronousPendingAndCancellationWaitForActualRequestTermination() {
    return Stream.of(false, true)
        .map(
            cancel ->
                DynamicTest.dynamicTest(
                    cancel ? "async cancellation" : "async pending",
                    () -> {
                      try (Scene s =
                          new Scene(cancel ? "async-cancellation" : "async-pending", true)) {
                        CompletableFuture<Void> entered = new CompletableFuture<>();
                        CompletableFuture<Void> release = new CompletableFuture<>();
                        s.register(
                            "commit",
                            FaultRegistry.Injection.REPLACE_RESPONSE,
                            DynamoDbFaultEffects.response(
                                response -> {
                                  s.observation.set(
                                      "actual_sdk_response", DynamoDbJson.sdk(response));
                                  entered.complete(null);
                                  release.join();
                                  return response;
                                }));
                        FaultRegistry.Operation operation =
                            s.c.faults.begin(s.nextOperation++, true);
                        CompletableFuture<Void> result = s.asyncStore.persistEvent(event(1));
                        try {
                          entered.get(10, TimeUnit.SECONDS);
                          assertFalse(result.isDone());
                          assertEquals(1, s.c.faults.pending(operation));
                          assertFalse(s.c.recorder.requestsFinished(operation).isDone());
                          assertFalse(s.c.closed());
                          s.assertSaved(event(1), -1);
                          s.observation.put("future_pending_before_release", !result.isDone());
                          s.observation.put(
                              "request_pending_before_release", s.c.faults.pending(operation));
                          if (cancel) {
                            assertTrue(result.cancel(true));
                            assertTrue(result.isCancelled());
                            assertFalse(s.c.recorder.requestsFinished(operation).isDone());
                          }
                        } finally {
                          release.complete(null);
                          if (!cancel) result.get(10, TimeUnit.SECONDS);
                          s.complete(operation, null, 1, 1, event(1), s.stored());
                        }
                        s.observation.put("future_cancelled", result.isCancelled());
                        assertFalse(s.c.closed());
                        s.assertSaved(event(1), -1);
                        assertNull(s.write(event(2), 1, 1));
                      }
                    }));
  }

  private static final class Scene implements AutoCloseable {
    final DynamoDbTestContext c = fixture.createContext();
    final AtomicInteger serializeCalls = new AtomicInteger();
    final AtomicInteger snapshotCalls = new AtomicInteger();
    final ObjectNode observation = DynamoDbJson.object();
    final ArrayNode operations = observation.putArray("operations");
    final Map<Integer, FaultRegistry.Fault> registered = new LinkedHashMap<>();
    final boolean asynchronous;
    final String name;
    final EventStoreConfig<byte[], String> config;
    final DynamoDbTableConfig tables;
    EventStore<byte[], String> syncStore;
    AsyncEventStore<byte[], String> asyncStore;
    int nextOperation = 1;

    Scene(String name, boolean asynchronous) {
      this.name = name;
      this.asynchronous = asynchronous;
      observation.put("case", name).put("sdk_path", asynchronous ? "async" : "sync");
      try {
        DynamoDbConfigurationTables.create(c, RetentionPolicy.delete(2));
        PayloadSerializer<byte[]> payload =
            new PayloadSerializer<byte[]>() {
              public byte[] serialize(byte[] value) {
                serializeCalls.incrementAndGet();
                return value;
              }

              public byte[] deserialize(byte[] bytes) {
                throw new AssertionError("Write must not deserialize");
              }
            };
        PayloadSerializer<String> snapshot =
            new PayloadSerializer<String>() {
              public byte[] serialize(String value) {
                snapshotCalls.incrementAndGet();
                throw new AssertionError("Event write serialized snapshot");
              }

              public String deserialize(byte[] bytes) {
                snapshotCalls.incrementAndGet();
                throw new AssertionError("Event write deserialized snapshot");
              }
            };
        config =
            EventStoreConfig.<byte[], String>builder()
                .payloadSerializer(new FaultPayloadSerializer<>(payload, c.faults, false))
                .snapshotSerializer(snapshot)
                .build();
        tables =
            DynamoDbConfigurationTables.config(c)
                .retentionPolicy(RetentionPolicy.delete(2))
                .build();
        FaultRegistry.Operation operation = c.faults.begin(0, false);
        if (asynchronous)
          asyncStore = DynamoDbEventStore.createAsync(c.async, tables, config).join();
        else syncStore = DynamoDbEventStore.create(c.client, tables, config);
        c.recorder.requestsFinished(operation).join();
        assertEquals(0, c.faults.pending(operation));
        assertEquals("passed", c.faults.finish(operation).status);
        observation.set(
            "generation_requests",
            DynamoDbConfigurationFixture.requestsJson(c.recorder.requests()));
        observation.set(
            "configuration_items", DynamoDbJson.sdk(DynamoDbConfigurationFixture.stored(c)));
      } catch (Throwable failure) {
        c.close();
        throw failure;
      }
    }

    void finishGeneration(FaultRegistry.Operation operation) throws Exception {
      c.recorder.requestsFinished(operation).get(10, TimeUnit.SECONDS);
      assertEquals(0, c.faults.pending(operation));
      assertEquals("passed", c.faults.finish(operation).status);
      List<DynamoDbRequestRecorder.Request> actual = new ArrayList<>();
      for (DynamoDbRequestRecorder.Request request : c.recorder.requests()) {
        if (request.operation == operation.number) actual.add(request);
      }
      observation.set(
          "additional_generation_requests", DynamoDbConfigurationFixture.requestsJson(actual));
      assertEquals(1, actual.size());
      assertEquals("configuration-read", actual.get(0).phase);
      assertEquals(1, actual.get(0).transmissions);
    }

    void register(String phase, FaultRegistry.Injection injection, FaultRegistry.Effect effect) {
      registered.put(nextOperation, c.faults.register(nextOperation, phase, 1, injection, effect));
    }

    void cancel(String journalCode, String headCode, Long oldHead) {
      FaultRegistry.Effect delegate =
          DynamoDbFaultEffects.transactionCanceled(
              c.targets, Map.of("journal", journalCode, "head", headCode), oldHead, () -> {});
      register(
          "commit",
          FaultRegistry.Injection.REPLACE_REQUEST,
          new FaultRegistry.Effect() {
            public boolean supports(FaultRegistry.Injection injection) {
              return delegate.supports(injection);
            }

            public HttpReply reply(DynamoDbRequestRecorder.Request request) {
              HttpReply reply = delegate.reply(request);
              observation.withArray("injected_http_errors").add(DynamoDbJson.read(reply.body()));
              return reply;
            }
          });
    }

    Throwable write(EventEnvelope<byte[]> event, int requests, int transmissions) throws Exception {
      Map<String, Object> before = stored();
      FaultRegistry.Operation operation = c.faults.begin(nextOperation++, true);
      Throwable failure = null;
      try {
        if (asynchronous) {
          CompletableFuture<Void> result = assertDoesNotThrow(() -> asyncStore.persistEvent(event));
          result.get(10, TimeUnit.SECONDS);
        } else syncStore.persistEvent(event);
      } catch (ExecutionException | RuntimeException error) {
        failure = EventStoreExceptions.unwrap(error);
      }
      complete(operation, failure, requests, transmissions, event, before);
      return failure;
    }

    void complete(
        FaultRegistry.Operation operation,
        Throwable failure,
        int requests,
        int transmissions,
        EventEnvelope<byte[]> event,
        Map<String, Object> before)
        throws Exception {
      c.recorder.requestsFinished(operation).get(10, TimeUnit.SECONDS);
      List<DynamoDbRequestRecorder.Request> actual = new ArrayList<>();
      for (DynamoDbRequestRecorder.Request request : c.recorder.requests()) {
        if (request.operation == operation.number) actual.add(request);
      }
      ObjectNode result = operations.addObject().put("operation", operation.number);
      result.put(
          "result", failure == null ? "success" : DynamoDbConfigurationFixture.category(failure));
      result
          .put("serialize_calls_total", serializeCalls.get())
          .put("snapshot_calls_total", snapshotCalls.get());
      result.set("before", DynamoDbJson.sdk(before));
      result.set("after", DynamoDbJson.sdk(stored()));
      result.set("requests", DynamoDbConfigurationFixture.requestsJson(actual));
      result.put("pending", c.faults.pending(operation));
      result.put("request_terminal", c.recorder.requestsFinished(operation).isDone());
      if (failure instanceof ContractViolationException)
        result.put("rule", ((ContractViolationException) failure).rule());
      if (failure != null && failure.getCause() instanceof TransactionCanceledException) {
        result.set(
            "actual_sdk_cancellation_reasons",
            DynamoDbJson.sdk(
                ((TransactionCanceledException) failure.getCause()).cancellationReasons()));
      }
      FaultRegistry.Fault fault = registered.get(operation.number);
      if (fault != null) {
        result.put("fault_applications", c.faults.applications(fault));
        result.put("fault_reservations", c.faults.reservations(fault));
        assertEquals(1, c.faults.applications(fault));
        assertEquals(0, c.faults.reservations(fault));
      }
      assertEquals(0, c.faults.pending(operation));
      assertEquals("passed", c.faults.finish(operation).status);
      assertEquals(requests, actual.size());
      assertEquals(transmissions, actual.stream().mapToInt(r -> r.transmissions).sum());
      for (DynamoDbRequestRecorder.Request request : actual) assertRequest(request, event);
      if (failure != null) assertEquals(before, stored());
      assertEquals(0, snapshotCalls.get());
    }

    private void assertRequest(
        DynamoDbRequestRecorder.Request request, EventEnvelope<byte[]> event) {
      assertEquals("TransactWriteItems", request.api);
      assertEquals("commit", request.phase);
      assertEquals(1, request.httpAttempts);
      assertEquals(
          request.original.path("TransactItems"), request.marshalled.path("TransactItems"));
      if (request.transmissions > 0) assertEquals(request.marshalled, request.transmitted);
      JsonNode actions = request.marshalled.path("TransactItems");
      assertEquals(2, actions.size());
      JsonNode journal = actions.get(0).path("Put");
      assertEquals(c.journal, journal.path("TableName").asText());
      long nanos =
          java.math.BigInteger.valueOf(event.occurredAt().getEpochSecond())
              .multiply(java.math.BigInteger.valueOf(1_000_000_000L))
              .add(java.math.BigInteger.valueOf(event.occurredAt().getNano()))
              .longValueExact();
      assertEquals(DynamoDbJson.sdk(journal(event, nanos)), journal.path("Item"));
      JsonNode condition = DynamoDbRequestStructure.parse(journal).at("/condition/all");
      assertEquals(1, condition.size());
      assertEquals("aid", condition.get(0).path("attribute").asText());
      assertEquals("attribute_not_exists", condition.get(0).path("operator").asText());
      JsonNode head = actions.get(1).path(event.seqNr() == 1 ? "Put" : "Update");
      assertEquals(c.head, head.path("TableName").asText());
      assertEquals("ALL_OLD", head.path("ReturnValuesOnConditionCheckFailure").asText());
      JsonNode parsed = DynamoDbRequestStructure.parse(head);
      if (event.seqNr() == 1) {
        assertEquals("attribute_not_exists", parsed.at("/condition/all/0/operator").asText());
        assertEquals("aid", parsed.at("/condition/all/0/attribute").asText());
      } else {
        assertEquals("seq_nr", parsed.at("/condition/all/0/attribute").asText());
        assertEquals("eq", parsed.at("/condition/all/0/operator").asText());
        assertEquals(
            Long.toString(event.seqNr() - 1), parsed.at("/condition/all/0/argument/N").asText());
        assertEquals(
            DynamoDbJson.sdk(Map.of("aid", AttributeValue.fromS(event.aggregateId().asString()))),
            head.path("Key"));
        assertEquals(
            Set.of("seq_nr", "events"),
            DynamoDbConfigurationFixture.stringsFromFields(parsed.at("/update/set")));
        assertEquals(Long.toString(event.seqNr()), parsed.at("/update/set/seq_nr/N").asText());
        assertEquals(1, parsed.at("/update/set/events/L").size());
        assertTrue(parsed.at("/update/remove").isEmpty());
      }
    }

    void assertGap(Throwable failure, long seq) {
      assertInstanceOf(ContractViolationException.class, failure);
      ContractViolationException violation = (ContractViolationException) failure;
      assertEquals("W-8", violation.rule());
      assertEquals(seq, violation.seqNr().getAsLong());
      assertTrue(violation.getMessage().contains("W-8"));
      assertTrue(violation.getMessage().contains(Long.toString(seq)));
    }

    List<Map<String, AttributeValue>> rows(String table) {
      List<Map<String, AttributeValue>> rows = new ArrayList<>();
      c.admin
          .queryPaginator(
              QueryRequest.builder()
                  .tableName(table)
                  .consistentRead(true)
                  .keyConditionExpression("aid = :aid")
                  .expressionAttributeValues(Map.of(":aid", AttributeValue.fromS("A-x")))
                  .build())
          .forEach(page -> rows.addAll(page.items()));
      return rows;
    }

    Map<String, AttributeValue> head() {
      return c.admin
          .getItem(
              GetItemRequest.builder()
                  .tableName(c.head)
                  .consistentRead(true)
                  .key(Map.of("aid", AttributeValue.fromS("A-x")))
                  .build())
          .item();
    }

    Map<String, Object> stored() {
      return Map.of("journal", rows(c.journal), "head", head(), "snapshot", rows(c.snapshot));
    }

    void assertSaved(EventEnvelope<byte[]> event, long nanos) {
      Map<String, AttributeValue> actualJournal =
          c.admin
              .getItem(
                  GetItemRequest.builder()
                      .tableName(c.journal)
                      .consistentRead(true)
                      .key(
                          Map.of(
                              "aid",
                              AttributeValue.fromS(event.aggregateId().asString()),
                              "seq_nr",
                              AttributeValue.fromN(Long.toString(event.seqNr()))))
                      .build())
              .item();
      assertEquals(journal(event, nanos), actualJournal);
      assertEquals(DynamoDbEventWriteBoundaryTest.head(event, nanos), head());
    }

    @Override
    public void close() throws Exception {
      try {
        DynamoDbConfigurationFixture.close(c);
      } finally {
        observation.put("resources_closed", c.closed());
        Path directory = Path.of("build/reports/dynamodb-event-write");
        Files.createDirectories(directory);
        Files.writeString(
            directory.resolve(name + (asynchronous ? "-async" : "-sync") + ".json"),
            observation.toPrettyString());
      }
    }
  }
}
