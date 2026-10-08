package com.github.j5ik2o.event.store.adapter.java.dynamodbtest;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.j5ik2o.event.store.adapter.java.core.*;
import com.github.j5ik2o.event.store.adapter.java.dynamodb.*;
import java.lang.reflect.Field;
import java.math.BigInteger;
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
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;

/** Direct snapshot-write acceptance; snapshot reads and retention conformance remain unverified. */
class DynamoDbSnapshotWriteBoundaryTest {
  @RegisterExtension
  static final DynamoDbConfigurationFixture fixture = new DynamoDbConfigurationFixture();

  private static final AggregateId AID = AggregateId.of("A", "x");
  private static final StaticCredentialsProvider CREDENTIALS =
      StaticCredentialsProvider.create(
          AwsBasicCredentials.create(DynamoDbTestClients.ACCESS_KEY, "DynamoDbLocalDummySecret"));

  private interface Scenario {
    void run(Scene scene) throws Exception;
  }

  private Stream<DynamicTest> both(String name, RetentionPolicy policy, Scenario scenario) {
    return Stream.of(false, true)
        .map(
            async ->
                DynamicTest.dynamicTest(
                    name + (async ? " async" : " sync"),
                    () -> {
                      try (Scene scene = new Scene(name, async, policy)) {
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
    return event(seq, -1, "event書式é", new byte[] {0, 1, (byte) 255});
  }

  private static SnapshotEnvelope<byte[]> snapshot(long seq, String manifest, byte[] payload) {
    return SnapshotEnvelope.<byte[]>builder()
        .aggregate(payload)
        .seqNr(seq)
        .manifest(manifest)
        .build();
  }

  private static SnapshotEnvelope<byte[]> snapshot(long seq) {
    return snapshot(seq, "snapshot形式界", new byte[] {7, 8, (byte) 254});
  }

  private static long nanos(EventEnvelope<?> event) {
    return BigInteger.valueOf(event.occurredAt().getEpochSecond())
        .multiply(BigInteger.valueOf(1_000_000_000L))
        .add(BigInteger.valueOf(event.occurredAt().getNano()))
        .longValueExact();
  }

  private static Map<String, AttributeValue> journal(EventEnvelope<byte[]> event) {
    return Map.of(
        "aid", AttributeValue.fromS(event.aggregateId().asString()),
        "seq_nr", AttributeValue.fromN(Long.toString(event.seqNr())),
        "occurred_at", AttributeValue.fromN(Long.toString(nanos(event))),
        "manifest", AttributeValue.fromS(event.manifest()),
        "payload", AttributeValue.fromB(SdkBytes.fromByteArray(event.payload())));
  }

  private static Map<String, AttributeValue> head(EventEnvelope<byte[]> event) {
    Map<String, AttributeValue> envelope = new LinkedHashMap<>(journal(event));
    envelope.remove("aid");
    return Map.of(
        "aid", AttributeValue.fromS(event.aggregateId().asString()),
        "type_name", AttributeValue.fromS(event.aggregateId().typeName()),
        "seq_nr", AttributeValue.fromN(Long.toString(event.seqNr())),
        "events", AttributeValue.fromL(List.of(AttributeValue.fromM(envelope))));
  }

  private static Map<String, AttributeValue> snapshotItem(
      EventEnvelope<byte[]> event, SnapshotEnvelope<byte[]> snapshot, boolean history) {
    Map<String, AttributeValue> item = new LinkedHashMap<>();
    item.put("aid", AttributeValue.fromS(event.aggregateId().asString()));
    item.put("skey", AttributeValue.fromN(history ? Long.toString(snapshot.seqNr()) : "0"));
    item.put("seq_nr", AttributeValue.fromN(Long.toString(snapshot.seqNr())));
    item.put("manifest", AttributeValue.fromS(snapshot.manifest()));
    item.put("payload", AttributeValue.fromB(SdkBytes.fromByteArray(snapshot.aggregate())));
    item.put(
        "last_updated_at",
        AttributeValue.fromN(Long.toString(Math.floorDiv(nanos(event), 1_000_000L))));
    if (history)
      item.put("active_history_seq_nr", AttributeValue.fromN(Long.toString(snapshot.seqNr())));
    return item;
  }

  private static void set(Object target, String name, Object value) throws Exception {
    Field field = target.getClass().getDeclaredField(name);
    field.setAccessible(true);
    field.set(target, value);
  }

  private static void violation(Throwable failure, String rule, long seq) {
    assertInstanceOf(ContractViolationException.class, failure);
    ContractViolationException error = (ContractViolationException) failure;
    assertEquals(rule, error.rule());
    assertEquals(seq, error.seqNr().getAsLong());
    assertTrue(error.getMessage().contains(rule));
    assertTrue(error.getMessage().contains(Long.toString(seq)));
  }

  @TestFactory
  Stream<DynamicTest> createAndUpdatePersistAllAttributesAndRestoreJournalThroughTheProduct() {
    return Stream.of(RetentionPolicy.none(), RetentionPolicy.delete(2), RetentionPolicy.ttl(2, 60))
        .flatMap(
            policy ->
                both(
                    "create-and-update-" + policy.mode().map(Enum::name).orElse("none"),
                    policy,
                    s -> {
                      List<EventEnvelope<byte[]>> events = new ArrayList<>();
                      for (long seq : List.of(1L, 2L, 3L)) {
                        EventEnvelope<byte[]> event =
                            event(
                                seq,
                                seq == 1 ? -1 : 123456789L,
                                "event書式é",
                                new byte[] {(byte) seq});
                        SnapshotEnvelope<byte[]> snapshot =
                            snapshot(seq, "snapshot形式界", new byte[] {(byte) (seq + 10)});
                        assertNull(s.write(event, snapshot, 1, 1));
                        s.assertSaved(event, snapshot);
                        events.add(event);
                        assertEquals(seq, s.rows(s.c.journal).size());
                        assertEquals(s.history ? seq + 1 : 1, s.rows(s.c.snapshot).size());
                      }
                      assertEquals(3, s.eventSerializations.get());
                      assertEquals(3, s.snapshotSerializations.get());
                      s.assertEvents(events);
                    }));
  }

  @TestFactory
  Stream<DynamicTest> inputFailuresDoNotSerializeSendOrChangeAnyItem() {
    return both(
        "input-rejection",
        RetentionPolicy.delete(2),
        s -> {
          assertNull(s.write(event(1), snapshot(1), 1, 1));
          int eventCalls = s.eventSerializations.get();
          int snapshotCalls = s.snapshotSerializations.get();
          assertEquals(
              "T-2", ((ContractViolationException) s.write(null, snapshot(2), 0, 0)).rule());
          violation(s.write(event(2), null, 0, 0), "T-10", 2);
          for (long seq : List.of(0L, 3L)) {
            Throwable failure = s.write(event(2), snapshot(seq), 0, 0);
            violation(failure, "W-9", 2);
            assertTrue(failure.getMessage().contains(Long.toString(seq)));
          }
          for (long seq : List.of(-1L, 0L, 1L << 53)) {
            EventEnvelope<byte[]> invalid = event(2);
            set(invalid, "seqNr", seq);
            violation(s.write(invalid, snapshot(2), 0, 0), seq == 0 ? "W-6" : "T-9", seq);
          }
          for (long seq : List.of(-1L, 1L << 53)) {
            SnapshotEnvelope<byte[]> invalid = snapshot(2);
            set(invalid, "seqNr", seq);
            violation(s.write(event(2), invalid, 0, 0), "T-9", seq);
          }
          for (Instant time :
              List.of(
                  Instant.ofEpochSecond(0, Long.MIN_VALUE).minusNanos(1),
                  Instant.ofEpochSecond(0, Long.MAX_VALUE).plusNanos(1))) {
            EventEnvelope<byte[]> invalid = event(2);
            set(invalid, "occurredAt", time);
            violation(s.write(invalid, snapshot(2), 0, 0), "T-13", 2);
          }
          assertEquals(eventCalls, s.eventSerializations.get());
          assertEquals(snapshotCalls, s.snapshotSerializations.get());
          assertNull(s.write(event(2), snapshot(2), 1, 1));
          s.assertSaved(event(2), snapshot(2));
        });
  }

  @TestFactory
  Stream<DynamicTest> eachSerializerFailureLeavesEveryItemUnchangedWithoutSending() {
    return both(
        "serializer-failures",
        RetentionPolicy.delete(2),
        s -> {
          assertNull(s.write(event(1), snapshot(1), 1, 1));
          for (String phase : List.of("serialize-event", "serialize-snapshot")) {
            for (RuntimeException error :
                List.of(
                    new SerializationException("classified serializer failure"),
                    new IllegalStateException("serializer failure"))) {
              int eventCalls = s.eventSerializations.get();
              int snapshotCalls = s.snapshotSerializations.get();
              s.register(
                  phase,
                  FaultRegistry.Injection.REPLACE_REQUEST,
                  DynamoDbFaultEffects.serializationError(error));
              Throwable failure = s.write(event(2), snapshot(2), 0, 0);
              assertInstanceOf(SerializationException.class, failure);
              if (error instanceof SerializationException) assertSame(error, failure);
              else assertSame(error, failure.getCause());
              assertEquals(
                  eventCalls + (phase.equals("serialize-snapshot") ? 1 : 0),
                  s.eventSerializations.get());
              assertEquals(snapshotCalls, s.snapshotSerializations.get());
            }
          }
          assertNull(s.write(event(2), snapshot(2), 1, 1));
          s.assertSaved(event(2), snapshot(2));
        });
  }

  @TestFactory
  Stream<DynamicTest> currentAndHistoryControlTheExactItemLimitForCreateAndUpdate() {
    return Stream.of(RetentionPolicy.none(), RetentionPolicy.delete(2), RetentionPolicy.ttl(2, 60))
        .flatMap(
            policy ->
                both(
                    "size-boundaries-" + policy.mode().map(Enum::name).orElse("none"),
                    policy,
                    s -> {
                      // Independent AWS counts for A-x, single-digit numbers and empty manifest:
                      // current = 43 name bytes + 3 aid bytes + 6 number bytes + B = 52 + B.
                      // history adds active_history_seq_nr(21) + N(2) = 75 + B.
                      int overhead = s.history ? 75 : 52;
                      byte[] exact = new byte[409600 - overhead];
                      Arrays.fill(exact, (byte) 0xa5);
                      for (long seq : List.of(1L, 2L)) {
                        EventEnvelope<byte[]> event = event(seq, 1, "", new byte[0]);
                        violation(
                            s.write(event, snapshot(seq, "", new byte[exact.length + 1]), 0, 0),
                            "D-7",
                            seq);
                        SnapshotEnvelope<byte[]> snapshot = snapshot(seq, "", exact);
                        assertNull(s.write(event, snapshot, 1, 1));
                        s.assertSaved(event, snapshot);
                      }
                      s.observation.put(
                          "limiting_item", s.history ? "history-snapshot" : "current-snapshot");
                      s.observation.put("independent_overhead_bytes", overhead);
                    }));
  }

  @TestFactory
  Stream<DynamicTest> unicodeAndMaximumSequenceChangeTheHistorySizeBoundary() {
    return both(
        "metadata-size-boundary",
        RetentionPolicy.delete(2),
        s -> {
          long maximum = (1L << 53) - 1;
          s.c.admin.putItem(
              PutItemRequest.builder().tableName(s.c.head).item(head(event(maximum - 1))).build());
          EventEnvelope<byte[]> event = event(maximum, Long.MAX_VALUE, "", new byte[0]);
          // history 75 + manifest UTF-8(3) + three 16-digit numbers' extra bytes(7 each)
          // + last_updated_at's 13-digit number's extra bytes(6) = 105.
          violation(
              s.write(event, snapshot(maximum, "界", new byte[409601 - 105]), 0, 0), "D-7", maximum);
          SnapshotEnvelope<byte[]> snapshot = snapshot(maximum, "界", new byte[409600 - 105]);
          assertNull(s.write(event, snapshot, 1, 1));
          s.assertSaved(event, snapshot);
          s.observation.put("independent_overhead_bytes", 105);
        });
  }

  @TestFactory
  Stream<DynamicTest> signedNanosecondBoundariesProduceIntegerSnapshotMilliseconds() {
    return both(
        "signed-timestamps",
        RetentionPolicy.ttl(2, 60),
        s -> {
          List<EventEnvelope<byte[]>> events = new ArrayList<>();
          long seq = 1;
          for (long nanos :
              List.of(
                  Long.MIN_VALUE,
                  Long.MIN_VALUE + 1,
                  -1L,
                  0L,
                  1L,
                  Long.MAX_VALUE - 1,
                  Long.MAX_VALUE)) {
            EventEnvelope<byte[]> event = event(seq, nanos, "", new byte[0]);
            SnapshotEnvelope<byte[]> snapshot = snapshot(seq++, "", new byte[0]);
            assertNull(s.write(event, snapshot, 1, 1));
            s.assertSaved(event, snapshot);
            events.add(event);
          }
          s.assertEvents(events);
        });
  }

  @TestFactory
  Stream<DynamicTest> realDuplicateGapAndStaleHeadFailuresNeverPartiallyCommit() {
    return both(
        "real-conditions",
        RetentionPolicy.delete(2),
        s -> {
          assertNull(s.write(event(1), snapshot(1), 1, 1));
          assertNull(s.write(event(2), snapshot(2), 1, 1));
          for (long seq : List.of(1L, 2L, 4L)) {
            Throwable failure =
                s.write(event(seq), snapshot(seq, "losing-state", new byte[] {99}), 1, 1);
            if (seq == 4) violation(failure, "W-8", seq);
            else {
              assertInstanceOf(OptimisticLockException.class, failure);
              assertInstanceOf(TransactionCanceledException.class, failure.getCause());
              assertEquals(
                  "2",
                  ((TransactionCanceledException) failure.getCause())
                      .cancellationReasons()
                      .get(1)
                      .item()
                      .get("seq_nr")
                      .n());
            }
          }
          s.c.admin.deleteItem(
              DeleteItemRequest.builder()
                  .tableName(s.c.journal)
                  .key(
                      Map.of(
                          "aid",
                          AttributeValue.fromS(AID.asString()),
                          "seq_nr",
                          AttributeValue.fromN("2")))
                  .build());
          assertInstanceOf(OptimisticLockException.class, s.write(event(2), snapshot(2), 1, 1));
          assertNull(s.write(event(3), snapshot(3), 1, 1));
          s.assertSaved(event(3), snapshot(3));
        });
  }

  @TestFactory
  Stream<DynamicTest> absentHeadAndJournalCollisionProtectCurrentAndHistory() {
    return both(
        "absent-head-and-journal-collision",
        RetentionPolicy.delete(2),
        s -> {
          for (long seq : List.of(2L, 3L))
            violation(s.write(event(seq), snapshot(seq), 1, 1), "W-8", seq);
          s.c.admin.putItem(
              PutItemRequest.builder().tableName(s.c.journal).item(journal(event(1))).build());
          assertInstanceOf(OptimisticLockException.class, s.write(event(1), snapshot(1), 1, 1));
          assertTrue(s.item(s.c.head, null, 0).isEmpty());
          assertTrue(s.rows(s.c.snapshot).isEmpty());
          s.c.admin.deleteItem(
              DeleteItemRequest.builder()
                  .tableName(s.c.journal)
                  .key(
                      Map.of(
                          "aid",
                          AttributeValue.fromS(AID.asString()),
                          "seq_nr",
                          AttributeValue.fromN("1")))
                  .build());
          assertNull(s.write(event(1), snapshot(1), 1, 1));
          s.c.admin.putItem(
              PutItemRequest.builder().tableName(s.c.journal).item(journal(event(2))).build());
          assertInstanceOf(OptimisticLockException.class, s.write(event(2), snapshot(2), 1, 1));
          s.assertSaved(event(1), snapshot(1));
        });
  }

  @TestFactory
  Stream<DynamicTest> multipleCancellationReasonsKeepConflictHeadJournalPriority() {
    return Stream.of(RetentionPolicy.none(), RetentionPolicy.delete(2))
        .flatMap(
            policy ->
                both(
                    "cancellation-priority-"
                        + (policy.keepCount().isPresent() ? "history" : "current"),
                    policy,
                    s -> {
                      assertNull(s.write(event(1), snapshot(1), 1, 1));
                      int count = s.history ? 4 : 3;
                      for (int position = 2; position < count; position++) {
                        String[][] combinations = {
                          {
                            "ConditionalCheckFailed",
                            "ConditionalCheckFailed",
                            "TransactionConflict"
                          },
                          {"ConditionalCheckFailed", "ConditionalCheckFailed", "ThrottlingError"},
                          {"ConditionalCheckFailed", "None", "ThrottlingError"},
                          {"None", "None", "ThrottlingError"},
                          {"None", "None", "ConditionalCheckFailed"}
                        };
                        for (int i = 0; i < combinations.length; i++) {
                          String[] codes = new String[count];
                          Arrays.fill(codes, "None");
                          codes[0] = combinations[i][0];
                          codes[1] = combinations[i][1];
                          codes[position] = combinations[i][2];
                          s.cancel(codes, 1L);
                          Throwable failure = s.write(event(3), snapshot(3), 1, 0);
                          if (i == 1) violation(failure, "W-8", 3);
                          else if (i >= 3) assertInstanceOf(StorageException.class, failure);
                          else assertInstanceOf(OptimisticLockException.class, failure);
                        }
                      }
                      String[] codes = new String[count];
                      Arrays.fill(codes, "None");
                      codes[1] = "ConditionalCheckFailed";
                      s.cancel(codes, 3L);
                      Throwable failure = s.write(event(2), snapshot(2), 1, 0);
                      assertInstanceOf(OptimisticLockException.class, failure);
                      assertEquals(
                          "3",
                          ((TransactionCanceledException) failure.getCause())
                              .cancellationReasons()
                              .get(1)
                              .item()
                              .get("seq_nr")
                              .n());
                      assertEquals("1", s.item(s.c.head, null, 0).get("seq_nr").n());
                      s.cancel(codes, null);
                      violation(s.write(event(2), snapshot(2), 1, 0), "W-8", 2);
                      assertNull(s.write(event(2), snapshot(2), 1, 1));
                    }));
  }

  @TestFactory
  Stream<DynamicTest> throttlingAndServiceFailuresDoNotCommitOrRead() {
    return both(
        "sdk-errors",
        RetentionPolicy.delete(2),
        s -> {
          assertNull(s.write(event(1), snapshot(1), 1, 1));
          for (String code :
              List.of("ProvisionedThroughputExceededException", "InternalServerError")) {
            s.register(
                "commit",
                FaultRegistry.Injection.REPLACE_REQUEST,
                DynamoDbFaultEffects.sdkError(code));
            Throwable failure = s.write(event(2), snapshot(2), 1, 0);
            assertInstanceOf(StorageException.class, failure);
            assertInstanceOf(DynamoDbException.class, failure.getCause());
          }
          assertNull(s.write(event(2), snapshot(2), 1, 1));
        });
  }

  @TestFactory
  Stream<DynamicTest> realCommunicationFailureKeepsAllItemsAndUsesTheSameGeneratedStore() {
    return both(
        "communication-failure",
        RetentionPolicy.delete(2),
        s -> {
          assertNull(s.write(event(1), snapshot(1), 1, 1));
          AtomicBoolean disconnected = new AtomicBoolean();
          ExecutionInterceptor connection =
              new ExecutionInterceptor() {
                @Override
                public SdkHttpRequest modifyHttpRequest(
                    Context.ModifyHttpRequest context, ExecutionAttributes attributes) {
                  if (disconnected.get() && context.request() instanceof TransactWriteItemsRequest)
                    return context.httpRequest().toBuilder()
                        .host("127.0.0.1")
                        .port(1)
                        .protocol("http")
                        .build();
                  return context.httpRequest();
                }
              };
          if (s.asynchronous) {
            try (FaultAsyncHttpClient http =
                    new FaultAsyncHttpClient(
                        DynamoDbTestClients.asyncHttp(fixture.eventLoop()).build(), s.c.recorder);
                DynamoDbAsyncClient client =
                    DynamoDbTestClients.observedAsync(
                        fixture.endpoint(), s.c.recorder, http, connection)) {
              FaultRegistry.Operation generation = s.c.faults.begin(s.nextOperation++, false);
              s.asyncStore = DynamoDbEventStore.createAsync(client, s.tables, s.config).join();
              s.finishGeneration(generation, 1);
              exerciseConnection(s, disconnected);
            }
          } else {
            try (FaultHttpClient http =
                    new FaultHttpClient(Apache5HttpClient.builder().build(), s.c.recorder);
                DynamoDbClient client =
                    DynamoDbClient.builder()
                        .endpointOverride(fixture.endpoint())
                        .region(DynamoDbTestClients.REGION)
                        .credentialsProvider(CREDENTIALS)
                        .overrideConfiguration(
                            DynamoDbTestClients.overrides()
                                .addExecutionInterceptor(connection)
                                .addExecutionInterceptor(s.c.recorder)
                                .build())
                        .httpClient(http)
                        .build()) {
              FaultRegistry.Operation generation = s.c.faults.begin(s.nextOperation++, false);
              s.syncStore = DynamoDbEventStore.create(client, s.tables, s.config);
              s.finishGeneration(generation, 1);
              exerciseConnection(s, disconnected);
            }
          }
          s.observation.put("additional_clients_and_transports_closed", true);
          assertFalse(fixture.eventLoop().eventLoopGroup().isShuttingDown());
          s.observation.put("borrowed_event_loop_alive_after_clients_closed", true);
        });
  }

  private static void exerciseConnection(Scene s, AtomicBoolean disconnected) throws Exception {
    disconnected.set(true);
    Throwable failure = s.write(event(2), snapshot(2), 1, 1);
    assertInstanceOf(StorageException.class, failure);
    assertInstanceOf(SdkClientException.class, failure.getCause());
    disconnected.set(false);
    assertNull(s.write(event(2), snapshot(2), 1, 1));
    s.assertSaved(event(2), snapshot(2));
  }

  @TestFactory
  Stream<DynamicTest> losingUpdateCannotOverwriteTheWinnersSnapshotWithEitherSdkPath() {
    return both(
        "competing-updates",
        RetentionPolicy.delete(2),
        s -> {
          assertNull(s.write(event(1), snapshot(1), 1, 1));
          CompletableFuture<Void> entered = new CompletableFuture<>();
          CompletableFuture<Void> release = new CompletableFuture<>();
          ExecutionInterceptor gate =
              new ExecutionInterceptor() {
                @Override
                public void beforeTransmission(
                    Context.BeforeTransmission context, ExecutionAttributes attributes) {
                  if (context.request() instanceof TransactWriteItemsRequest) {
                    TransactWriteItemsRequest request =
                        (TransactWriteItemsRequest) context.request();
                    if ("loser-event"
                        .equals(request.transactItems().get(0).put().item().get("manifest").s())) {
                      entered.complete(null);
                      release.join();
                    }
                  }
                }
              };
          ExecutorService executor = Executors.newSingleThreadExecutor();
          try (FaultHttpClient http =
                  new FaultHttpClient(Apache5HttpClient.builder().build(), s.c.recorder);
              FaultAsyncHttpClient asyncHttp =
                  new FaultAsyncHttpClient(
                      DynamoDbTestClients.asyncHttp(fixture.eventLoop()).build(), s.c.recorder);
              DynamoDbClient client =
                  DynamoDbClient.builder()
                      .endpointOverride(fixture.endpoint())
                      .region(DynamoDbTestClients.REGION)
                      .credentialsProvider(CREDENTIALS)
                      .overrideConfiguration(
                          DynamoDbTestClients.overrides()
                              .addExecutionInterceptor(gate)
                              .addExecutionInterceptor(s.c.recorder)
                              .build())
                      .httpClient(http)
                      .build();
              DynamoDbAsyncClient asyncClient =
                  DynamoDbTestClients.observedAsync(
                      fixture.endpoint(), s.c.recorder, asyncHttp, gate)) {
            FaultRegistry.Operation syncGeneration = s.c.faults.begin(s.nextOperation++, false);
            s.syncStore = DynamoDbEventStore.create(client, s.tables, s.config);
            s.finishGeneration(syncGeneration, 1);
            FaultRegistry.Operation asyncGeneration = s.c.faults.begin(s.nextOperation++, false);
            s.asyncStore = DynamoDbEventStore.createAsync(asyncClient, s.tables, s.config).join();
            s.finishGeneration(asyncGeneration, 1);
            Map<String, Object> before = s.stored();
            FaultRegistry.Operation operation = s.c.faults.begin(s.nextOperation++, true);
            EventEnvelope<byte[]> losingEvent = event(2, 1, "loser-event", new byte[] {41});
            SnapshotEnvelope<byte[]> losingSnapshot =
                snapshot(2, "loser-snapshot", new byte[] {42});
            EventEnvelope<byte[]> winningEvent = event(2, 2, "winner-event", new byte[] {43});
            SnapshotEnvelope<byte[]> winningSnapshot =
                snapshot(2, "winner-snapshot", new byte[] {44});
            CompletableFuture<Throwable> loser =
                CompletableFuture.supplyAsync(
                    () -> {
                      try {
                        return s.call(losingEvent, losingSnapshot);
                      } catch (Exception failure) {
                        throw new CompletionException(failure);
                      }
                    },
                    executor);
            try {
              entered.get(10, TimeUnit.SECONDS);
              assertEquals(1, s.c.faults.pending(operation));
              assertEquals(0, s.requests(operation).stream().mapToInt(r -> r.transmissions).sum());
              assertEquals(before, s.stored());
              if (s.asynchronous)
                s.syncStore.persistEventAndSnapshot(winningEvent, winningSnapshot);
              else
                assertNull(
                    s.asyncStore
                        .persistEventAndSnapshot(winningEvent, winningSnapshot)
                        .get(10, TimeUnit.SECONDS));
              s.assertSaved(winningEvent, winningSnapshot);
              Map<String, Object> winner = s.stored();
              s.observation.set("winner_before_loser_release", DynamoDbJson.sdk(winner));
              release.complete(null);
              Throwable failure = loser.get(10, TimeUnit.SECONDS);
              assertInstanceOf(OptimisticLockException.class, failure);
              assertEquals(
                  "2",
                  ((TransactionCanceledException) failure.getCause())
                      .cancellationReasons()
                      .get(1)
                      .item()
                      .get("seq_nr")
                      .n());
              s.complete(operation, failure, 2, 2, before);
              assertEquals(winner, s.stored());
              List<DynamoDbRequestRecorder.Request> requests = s.requests(operation);
              s.assertRequest(requests.get(0), losingEvent, losingSnapshot);
              s.assertRequest(requests.get(1), winningEvent, winningSnapshot);
              s.observation.put("loser_sdk_path", s.asynchronous ? "async" : "sync");
              s.observation.put("winner_sdk_path", s.asynchronous ? "sync" : "async");
              s.assertEvents(List.of(event(1), winningEvent));
            } finally {
              release.complete(null);
              loser.get(10, TimeUnit.SECONDS);
            }
          } finally {
            release.complete(null);
            executor.shutdown();
            assertTrue(executor.awaitTermination(15, TimeUnit.SECONDS));
            s.observation.put("competition_executor_terminated", executor.isTerminated());
          }
          s.observation.put("additional_clients_and_transports_closed", true);
          assertFalse(fixture.eventLoop().eventLoopGroup().isShuttingDown());
          s.observation.put("borrowed_event_loop_alive_after_clients_closed", true);
        });
  }

  @TestFactory
  Stream<DynamicTest> pendingAndCancelledFuturesWaitForTheActualSnapshotRequestToTerminate() {
    return Stream.of(false, true)
        .map(
            cancel ->
                DynamicTest.dynamicTest(
                    cancel ? "snapshot async cancellation" : "snapshot async pending",
                    () -> {
                      try (Scene s =
                          new Scene(
                              cancel ? "async-cancellation" : "async-pending",
                              true,
                              RetentionPolicy.delete(2))) {
                        CompletableFuture<Void> entered = new CompletableFuture<>();
                        CompletableFuture<Void> release = new CompletableFuture<>();
                        Map<String, Object> before = s.stored();
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
                        CompletableFuture<Void> result =
                            s.asyncStore.persistEventAndSnapshot(event(1), snapshot(1));
                        try {
                          entered.get(10, TimeUnit.SECONDS);
                          assertFalse(result.isDone());
                          assertEquals(1, s.c.faults.pending(operation));
                          assertFalse(s.c.recorder.requestsFinished(operation).isDone());
                          assertFalse(s.c.closed());
                          s.assertSaved(event(1), snapshot(1));
                          s.observation.put(
                              "public_future_pending_before_release", !result.isDone());
                          s.observation.put(
                              "request_pending_before_release", s.c.faults.pending(operation));
                          if (cancel) {
                            assertTrue(result.cancel(true));
                            assertTrue(result.isCancelled());
                            assertFalse(s.c.recorder.requestsFinished(operation).isDone());
                            assertEquals(1, s.c.faults.pending(operation));
                          }
                        } finally {
                          release.complete(null);
                          if (!cancel) assertNull(result.get(10, TimeUnit.SECONDS));
                          s.complete(operation, null, 1, 1, before);
                        }
                        s.observation.put("public_future_cancelled", result.isCancelled());
                        s.assertRequest(s.requests(operation).get(0), event(1), snapshot(1));
                        assertFalse(s.c.closed());
                        s.assertSaved(event(1), snapshot(1));
                        assertNull(s.write(event(2), snapshot(2), 1, 1));
                      }
                    }));
  }

  private static final class Scene implements AutoCloseable {
    final DynamoDbTestContext c = fixture.createContext();
    final AtomicInteger eventSerializations = new AtomicInteger();
    final AtomicInteger snapshotSerializations = new AtomicInteger();
    final ObjectNode observation = DynamoDbJson.object();
    final ArrayNode operations = observation.putArray("operations");
    final Map<Integer, FaultRegistry.Fault> registered = new LinkedHashMap<>();
    final boolean asynchronous;
    final boolean history;
    final String name;
    final EventStoreConfig<byte[], byte[]> config;
    final DynamoDbTableConfig tables;
    EventStore<byte[], byte[]> syncStore;
    AsyncEventStore<byte[], byte[]> asyncStore;
    int nextOperation = 1;

    Scene(String name, boolean asynchronous, RetentionPolicy policy) {
      this.name = name;
      this.asynchronous = asynchronous;
      history = policy.keepCount().isPresent();
      observation.put("case", name).put("sdk_path", asynchronous ? "async" : "sync");
      try {
        DynamoDbConfigurationTables.create(c, policy);
        config =
            EventStoreConfig.<byte[], byte[]>builder()
                .payloadSerializer(
                    new FaultPayloadSerializer<>(serializer(eventSerializations), c.faults, false))
                .snapshotSerializer(
                    new FaultPayloadSerializer<>(
                        serializer(snapshotSerializations), c.faults, true))
                .build();
        tables = DynamoDbConfigurationTables.config(c).retentionPolicy(policy).build();
        FaultRegistry.Operation generation = c.faults.begin(0, false);
        if (asynchronous)
          asyncStore = DynamoDbEventStore.createAsync(c.async, tables, config).join();
        else syncStore = DynamoDbEventStore.create(c.client, tables, config);
        finishGeneration(generation, 2);
        observation.set(
            "configuration_items", DynamoDbJson.sdk(DynamoDbConfigurationFixture.stored(c)));
      } catch (Throwable failure) {
        c.close();
        throw failure;
      }
    }

    private static PayloadSerializer<byte[]> serializer(AtomicInteger calls) {
      return new PayloadSerializer<byte[]>() {
        public byte[] serialize(byte[] value) {
          calls.incrementAndGet();
          return value;
        }

        public byte[] deserialize(byte[] bytes) {
          return bytes;
        }
      };
    }

    void finishGeneration(FaultRegistry.Operation operation, int expected) {
      c.recorder.requestsFinished(operation).join();
      List<DynamoDbRequestRecorder.Request> actual = requests(operation);
      observation
          .withArray("generation_requests")
          .addAll(DynamoDbConfigurationFixture.requestsJson(actual));
      assertEquals(expected, actual.size());
      assertEquals(expected, actual.stream().mapToInt(r -> r.transmissions).sum());
      assertEquals("configuration-read", actual.get(0).phase);
      if (expected == 2) assertEquals("configuration-create", actual.get(1).phase);
      assertEquals(0, c.faults.pending(operation));
      assertEquals("passed", c.faults.finish(operation).status);
    }

    void register(String phase, FaultRegistry.Injection injection, FaultRegistry.Effect effect) {
      registered.put(nextOperation, c.faults.register(nextOperation, phase, 1, injection, effect));
    }

    void cancel(String[] codes, Long oldHead) {
      String[] targets = {"journal", "head", "current-snapshot", "history-snapshot"};
      Map<String, String> reasons = new LinkedHashMap<>();
      for (int i = 0; i < codes.length; i++) reasons.put(targets[i], codes[i]);
      register(
          "commit",
          FaultRegistry.Injection.REPLACE_REQUEST,
          DynamoDbFaultEffects.transactionCanceled(c.targets, reasons, oldHead, () -> {}));
    }

    Throwable call(EventEnvelope<byte[]> event, SnapshotEnvelope<byte[]> snapshot)
        throws Exception {
      if (asynchronous) {
        CompletableFuture<Void> result =
            assertDoesNotThrow(() -> asyncStore.persistEventAndSnapshot(event, snapshot));
        Throwable failure = result.handle((ignored, error) -> error).get(10, TimeUnit.SECONDS);
        if (failure != null) assertInstanceOf(EventStoreException.class, failure);
        return failure;
      }
      try {
        syncStore.persistEventAndSnapshot(event, snapshot);
        return null;
      } catch (RuntimeException failure) {
        return failure;
      }
    }

    Throwable write(
        EventEnvelope<byte[]> event,
        SnapshotEnvelope<byte[]> snapshot,
        int requests,
        int transmissions)
        throws Exception {
      Map<String, Object> before = stored();
      FaultRegistry.Operation operation = c.faults.begin(nextOperation++, true);
      Throwable failure = call(event, snapshot);
      complete(operation, failure, requests, transmissions, before);
      for (DynamoDbRequestRecorder.Request request : requests(operation))
        assertRequest(request, event, snapshot);
      if (failure != null) assertEquals(before, stored());
      return failure;
    }

    List<DynamoDbRequestRecorder.Request> requests(FaultRegistry.Operation operation) {
      List<DynamoDbRequestRecorder.Request> result = new ArrayList<>();
      for (DynamoDbRequestRecorder.Request request : c.recorder.requests())
        if (request.operation == operation.number) result.add(request);
      return result;
    }

    void complete(
        FaultRegistry.Operation operation,
        Throwable failure,
        int requests,
        int transmissions,
        Map<String, Object> before)
        throws Exception {
      c.recorder.requestsFinished(operation).get(10, TimeUnit.SECONDS);
      List<DynamoDbRequestRecorder.Request> actual = requests(operation);
      ObjectNode result = operations.addObject().put("operation", operation.number);
      result.put(
          "result", failure == null ? "success" : DynamoDbConfigurationFixture.category(failure));
      result
          .put("event_serializations", eventSerializations.get())
          .put("snapshot_serializations", snapshotSerializations.get());
      result.set("before", DynamoDbJson.sdk(before));
      result.set("after", DynamoDbJson.sdk(stored()));
      result.set("requests", DynamoDbConfigurationFixture.requestsJson(actual));
      result.put("pending", c.faults.pending(operation));
      result.put("request_terminal", c.recorder.requestsFinished(operation).isDone());
      if (failure instanceof ContractViolationException)
        result.put("rule", ((ContractViolationException) failure).rule());
      if (failure != null && failure.getCause() instanceof TransactionCanceledException)
        result.set(
            "actual_sdk_cancellation_reasons",
            DynamoDbJson.sdk(
                ((TransactionCanceledException) failure.getCause()).cancellationReasons()));
      FaultRegistry.Fault fault = registered.get(operation.number);
      result.put("fault_applications", fault == null ? 0 : c.faults.applications(fault));
      result.put("fault_reservations", fault == null ? 0 : c.faults.reservations(fault));
      if (fault != null) assertEquals(1, c.faults.applications(fault));
      assertEquals(0, result.path("fault_reservations").intValue());
      assertEquals(0, c.faults.pending(operation));
      assertEquals("passed", c.faults.finish(operation).status);
      assertEquals(requests, actual.size());
      assertEquals(transmissions, actual.stream().mapToInt(r -> r.transmissions).sum());
      for (DynamoDbRequestRecorder.Request request : actual) {
        assertEquals("TransactWriteItems", request.api);
        assertEquals("commit", request.phase);
        assertEquals(1, request.httpAttempts);
        assertEquals(
            request.original.path("TransactItems"), request.marshalled.path("TransactItems"));
        if (request.transmissions > 0) assertEquals(request.marshalled, request.transmitted);
      }
    }

    void assertRequest(
        DynamoDbRequestRecorder.Request request,
        EventEnvelope<byte[]> event,
        SnapshotEnvelope<byte[]> snapshot) {
      JsonNode actions = request.marshalled.path("TransactItems");
      assertEquals(history ? 4 : 3, actions.size());
      JsonNode journal = actions.get(0).path("Put");
      assertEquals(c.journal, journal.path("TableName").asText());
      assertEquals(DynamoDbJson.sdk(journal(event)), journal.path("Item"));
      JsonNode parsedJournal = DynamoDbRequestStructure.parse(journal).at("/condition/all");
      assertEquals(1, parsedJournal.size());
      assertEquals("aid", parsedJournal.get(0).path("attribute").asText());
      assertEquals("attribute_not_exists", parsedJournal.get(0).path("operator").asText());
      JsonNode head = actions.get(1).path(event.seqNr() == 1 ? "Put" : "Update");
      assertEquals(c.head, head.path("TableName").asText());
      assertEquals("ALL_OLD", head.path("ReturnValuesOnConditionCheckFailure").asText());
      JsonNode parsed = DynamoDbRequestStructure.parse(head);
      assertEquals(1, parsed.at("/condition/all").size());
      if (event.seqNr() == 1) {
        assertEquals(DynamoDbJson.sdk(head(event)), head.path("Item"));
        assertEquals("aid", parsed.at("/condition/all/0/attribute").asText());
        assertEquals("attribute_not_exists", parsed.at("/condition/all/0/operator").asText());
      } else {
        assertEquals("seq_nr", parsed.at("/condition/all/0/attribute").asText());
        assertEquals("eq", parsed.at("/condition/all/0/operator").asText());
        assertEquals(
            Long.toString(event.seqNr() - 1), parsed.at("/condition/all/0/argument/N").asText());
        assertEquals(
            DynamoDbJson.sdk(Map.of("aid", AttributeValue.fromS(AID.asString()))),
            head.path("Key"));
        assertEquals(
            Set.of("seq_nr", "events"),
            DynamoDbConfigurationFixture.stringsFromFields(parsed.at("/update/set")));
        assertEquals(DynamoDbJson.sdk(head(event).get("seq_nr")), parsed.at("/update/set/seq_nr"));
        assertEquals(DynamoDbJson.sdk(head(event).get("events")), parsed.at("/update/set/events"));
        assertTrue(parsed.at("/update/remove").isEmpty());
      }
      for (int i = 2; i < actions.size(); i++) {
        JsonNode put = actions.get(i).path("Put");
        assertEquals(c.snapshot, put.path("TableName").asText());
        assertEquals(DynamoDbJson.sdk(snapshotItem(event, snapshot, i == 3)), put.path("Item"));
        assertFalse(put.has("ConditionExpression"));
      }
    }

    List<Map<String, AttributeValue>> rows(String table) {
      List<Map<String, AttributeValue>> rows = new ArrayList<>();
      c.admin
          .queryPaginator(
              QueryRequest.builder()
                  .tableName(table)
                  .consistentRead(true)
                  .keyConditionExpression("aid = :aid")
                  .expressionAttributeValues(Map.of(":aid", AttributeValue.fromS(AID.asString())))
                  .build())
          .forEach(page -> rows.addAll(page.items()));
      return rows;
    }

    Map<String, AttributeValue> item(String table, String sortKey, long seq) {
      Map<String, AttributeValue> key = new LinkedHashMap<>();
      key.put("aid", AttributeValue.fromS(AID.asString()));
      if (sortKey != null) key.put(sortKey, AttributeValue.fromN(Long.toString(seq)));
      return c.admin
          .getItem(GetItemRequest.builder().tableName(table).consistentRead(true).key(key).build())
          .item();
    }

    Map<String, Object> stored() {
      return Map.of(
          "journal", rows(c.journal), "head", item(c.head, null, 0), "snapshot", rows(c.snapshot));
    }

    void assertSaved(EventEnvelope<byte[]> event, SnapshotEnvelope<byte[]> snapshot) {
      assertEquals(journal(event), item(c.journal, "seq_nr", event.seqNr()));
      assertEquals(head(event), item(c.head, null, 0));
      assertEquals(snapshotItem(event, snapshot, false), item(c.snapshot, "skey", 0));
      if (history)
        assertEquals(
            snapshotItem(event, snapshot, true), item(c.snapshot, "skey", snapshot.seqNr()));
      else assertTrue(item(c.snapshot, "skey", snapshot.seqNr()).isEmpty());
    }

    void assertEvents(List<EventEnvelope<byte[]>> expected) throws Exception {
      FaultRegistry.Operation operation = c.faults.begin(nextOperation++, false);
      List<EventEnvelope<byte[]>> actual =
          asynchronous
              ? asyncStore.getEventsByIdSinceSeqNr(AID, 0).get(10, TimeUnit.SECONDS)
              : syncStore.getEventsByIdSinceSeqNr(AID, 0);
      c.recorder.requestsFinished(operation).get(10, TimeUnit.SECONDS);
      ObjectNode read = observation.withArray("event_reads").addObject();
      read.set("requests", DynamoDbConfigurationFixture.requestsJson(requests(operation)));
      read.set(
          "restored",
          DynamoDbJson.mapper()
              .valueToTree(
                  actual.stream()
                      .map(
                          e ->
                              Map.of(
                                  "aid",
                                  e.aggregateId().asString(),
                                  "seq_nr",
                                  e.seqNr(),
                                  "occurred_at",
                                  e.occurredAt().toString(),
                                  "manifest",
                                  e.manifest(),
                                  "payload",
                                  e.payload()))
                      .toArray()));
      assertEquals(expected.size(), actual.size());
      for (int i = 0; i < expected.size(); i++) {
        EventEnvelope<byte[]> e = expected.get(i);
        EventEnvelope<byte[]> a = actual.get(i);
        assertEquals(e.aggregateId(), a.aggregateId());
        assertEquals(e.seqNr(), a.seqNr());
        assertEquals(e.occurredAt(), a.occurredAt());
        assertEquals(e.manifest(), a.manifest());
        assertArrayEquals(e.payload(), a.payload());
      }
      assertFalse(requests(operation).isEmpty());
      for (DynamoDbRequestRecorder.Request request : requests(operation)) {
        assertEquals("Query", request.api);
        assertEquals("read-events", request.phase);
        assertEquals(1, request.transmissions);
      }
      assertEquals(0, c.faults.pending(operation));
      assertEquals("passed", c.faults.finish(operation).status);
    }

    @Override
    public void close() throws Exception {
      try {
        DynamoDbConfigurationFixture.close(c);
      } finally {
        observation.put("resources_closed", c.closed());
        Path directory = Path.of("build/reports/dynamodb-snapshot-write");
        Files.createDirectories(directory);
        Files.writeString(
            directory.resolve(name + (asynchronous ? "-async" : "-sync") + ".json"),
            observation.toPrettyString());
      }
    }
  }
}
