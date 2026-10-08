package com.github.j5ik2o.event.store.adapter.java.dynamodbtest;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.j5ik2o.event.store.adapter.java.core.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.extension.RegisterExtension;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.dynamodb.model.*;

class DynamoDbEventReadBoundaryTest {
  @RegisterExtension
  static final DynamoDbConfigurationFixture configuration = new DynamoDbConfigurationFixture();

  private static final DynamoDbEventReadFixture fixture =
      new DynamoDbEventReadFixture(configuration);
  private static final AggregateId AID = AggregateId.of("売買", "x-\"é");
  private static final long MAX_SEQ = (1L << 53) - 1;
  private static final PayloadSerializer<byte[]> BYTES =
      new PayloadSerializer<byte[]>() {
        public byte[] serialize(byte[] value) {
          return value;
        }

        public byte[] deserialize(byte[] value) {
          return value;
        }
      };

  private interface Scenario {
    void run(DynamoDbEventReadFixture.Scene<byte[]> scene) throws Exception;
  }

  private Stream<DynamicTest> both(String name, Scenario action) {
    return Stream.of(false, true)
        .map(
            async ->
                DynamicTest.dynamicTest(
                    name + (async ? " async" : " sync"),
                    () -> {
                      try (DynamoDbEventReadFixture.Scene<byte[]> scene =
                          fixture.scene(name, async, BYTES)) {
                        action.run(scene);
                      }
                    }));
  }

  private static EventEnvelope<byte[]> event(
      AggregateId id, long seq, long nanos, String manifest, byte[] payload) {
    return EventEnvelope.<byte[]>builder()
        .aggregateId(id)
        .seqNr(seq)
        .occurredAt(Instant.ofEpochSecond(0, nanos))
        .manifest(manifest)
        .payload(payload)
        .build();
  }

  private static EventEnvelope<byte[]> event(long seq) {
    return event(AID, seq, -1, "書式é", new byte[] {0, 1, (byte) 255});
  }

  private static void assertEvents(
      List<EventEnvelope<byte[]>> expected, List<EventEnvelope<byte[]>> actual) {
    assertEquals(expected.size(), actual.size());
    for (int i = 0; i < expected.size(); i++) {
      EventEnvelope<byte[]> e = expected.get(i), a = actual.get(i);
      assertEquals(e.aggregateId(), a.aggregateId());
      assertEquals(e.seqNr(), a.seqNr());
      assertEquals(e.occurredAt(), a.occurredAt());
      assertEquals(e.manifest(), a.manifest());
      assertArrayEquals(e.payload(), a.payload());
    }
  }

  @TestFactory
  Stream<DynamicTest> realWritesReadEmptyInclusiveOrderedAndIsolatedResults() {
    return both(
        "empty-inclusive-isolated",
        s -> {
          assertTrue(s.read(AID, 0).isEmpty());
          s.assertQuery(AID, 0, 1);
          List<EventEnvelope<byte[]>> expected = List.of(event(1), event(2), event(3));
          for (EventEnvelope<byte[]> event : expected) s.write(event);
          AggregateId other = AggregateId.of("売買", "x-\"é-other");
          EventEnvelope<byte[]> otherEvent = event(other, 1, 1, "", new byte[0]);
          s.write(otherEvent);
          for (long start : new long[] {0, 1, 2, 3, 4, MAX_SEQ}) {
            List<EventEnvelope<byte[]>> selected = new ArrayList<>();
            for (EventEnvelope<byte[]> event : expected)
              if (event.seqNr() >= start) selected.add(event);
            assertEvents(selected, s.read(AID, start));
            s.assertQuery(AID, start, 1);
          }
          assertEvents(List.of(otherEvent), s.read(other, 0));
          s.assertQuery(other, 0, 1);
          for (long invalid : new long[] {-1, MAX_SEQ + 1}) {
            Throwable error =
                EventStoreExceptions.unwrap(
                    assertThrows(RuntimeException.class, () -> s.read(AID, invalid)));
            assertInstanceOf(ContractViolationException.class, error);
            assertEquals("T-9", ((ContractViolationException) error).rule());
            assertTrue(s.lastRequests().isEmpty());
          }
          assertThrows(RuntimeException.class, () -> s.read(null, 0));
          assertTrue(s.lastRequests().isEmpty());
        });
  }

  @TestFactory
  Stream<DynamicTest> maximumSequenceIsWrittenByProductAndReadInclusively() {
    return both(
        "maximum-sequence",
        s -> {
          s.write(event(1));
          s.c.admin.updateItem(
              UpdateItemRequest.builder()
                  .tableName(s.c.head)
                  .key(Map.of("aid", AttributeValue.fromS(AID.asString())))
                  .updateExpression("SET seq_nr = :seq")
                  .expressionAttributeValues(
                      Map.of(":seq", AttributeValue.fromN(Long.toString(MAX_SEQ - 1))))
                  .build());
          EventEnvelope<byte[]> maximum = event(MAX_SEQ);
          s.write(maximum);
          assertEvents(List.of(maximum), s.read(AID, MAX_SEQ));
          s.assertQuery(AID, MAX_SEQ, 1);
          assertEvents(List.of(event(1), maximum), s.read(AID, 0));
          s.assertQuery(AID, 0, 1);
        });
  }

  @TestFactory
  Stream<DynamicTest> signedNanosecondEndpointsAdjacentValuesAndManifestAreRestoredExactly() {
    return both(
        "signed-time-and-manifest",
        s -> {
          List<EventEnvelope<byte[]>> expected = new ArrayList<>();
          for (long nanos :
              new long[] {
                Long.MIN_VALUE, Long.MIN_VALUE + 1, -1, 0, 1, Long.MAX_VALUE - 1, Long.MAX_VALUE
              }) {
            EventEnvelope<byte[]> event =
                event(
                    AID,
                    expected.size() + 1,
                    nanos,
                    expected.isEmpty() ? "" : "非解釈é\u0301",
                    new byte[] {0, (byte) 255, (byte) expected.size()});
            expected.add(event);
            s.write(event);
          }
          assertEvents(expected, s.read(AID, 0));
          s.assertQuery(AID, 0, 1);
        });
  }

  @TestFactory
  Stream<DynamicTest> actualQueriesReadAllPagesOverOneMegabyteWithRealCursors() {
    return both(
        "over-one-megabyte",
        s -> {
          List<EventEnvelope<byte[]>> expected = large(s);
          assertEvents(expected, s.read(AID, 0));
          assertTrue(s.pages.size() >= 2);
          s.assertQuery(AID, 0, s.pages.size());
          assertEquals(5, s.pages.stream().mapToInt(p -> p.items().size()).sum());
        });
  }

  private static List<EventEnvelope<byte[]>> large(DynamoDbEventReadFixture.Scene<byte[]> s) {
    List<EventEnvelope<byte[]>> expected = new ArrayList<>();
    for (int seq = 1; seq <= 5; seq++) {
      byte[] bytes = new byte[320000];
      new Random(seq).nextBytes(bytes);
      EventEnvelope<byte[]> event = event(AID, seq, seq, "page-" + seq, bytes);
      expected.add(event);
      s.write(event);
    }
    return expected;
  }

  @TestFactory
  Stream<DynamicTest> savedAttributeCorruptionFailsAsStorageAndCorrectedRowReadsOnSameStore() {
    return both(
        "saved-attribute-corruption",
        s -> {
          s.write(event(1));
          Map<String, AttributeValue> key =
              Map.of(
                  "aid", AttributeValue.fromS(AID.asString()), "seq_nr", AttributeValue.fromN("1"));
          Map<String, AttributeValue> saved =
              s.c
                  .admin
                  .getItem(
                      GetItemRequest.builder()
                          .tableName(s.c.journal)
                          .key(key)
                          .consistentRead(true)
                          .build())
                  .item();
          List<Consumer<Map<String, AttributeValue>>> corruptions =
              List.of(
                  row -> row.remove("manifest"),
                  row -> row.put("manifest", AttributeValue.fromN("1")),
                  row -> row.remove("payload"),
                  row -> row.put("payload", AttributeValue.fromS("wrong")),
                  row -> row.remove("occurred_at"),
                  row -> row.put("occurred_at", AttributeValue.fromS("wrong")),
                  row -> row.put("occurred_at", AttributeValue.fromN("0.5")),
                  row -> row.put("occurred_at", AttributeValue.fromN("9223372036854775808")),
                  row -> row.put("occurred_at", AttributeValue.fromN("-9223372036854775809")));
          for (Consumer<Map<String, AttributeValue>> corrupt : corruptions) {
            Map<String, AttributeValue> row = new LinkedHashMap<>(saved);
            corrupt.accept(row);
            s.c.admin.putItem(PutItemRequest.builder().tableName(s.c.journal).item(row).build());
            s.observation.withArray("corrupted_rows").add(DynamoDbJson.sdk(row));
            Throwable failure =
                EventStoreExceptions.unwrap(
                    assertThrows(RuntimeException.class, () -> s.read(AID, 0)));
            assertInstanceOf(StorageException.class, failure);
            s.assertQuery(AID, 0, 1);
            s.c.admin.putItem(PutItemRequest.builder().tableName(s.c.journal).item(saved).build());
            assertEvents(List.of(event(1)), s.read(AID, 0));
            s.assertQuery(AID, 0, 1);
          }
        });
  }

  @TestFactory
  Stream<DynamicTest> sdkErrorResponsesAreClassifiedWithoutTransmittingOrAdditionalReads() {
    return both(
        "sdk-error-responses",
        s -> {
          s.write(event(1));
          for (String code :
              List.of("InternalServerError", "ProvisionedThroughputExceededException")) {
            FaultRegistry.Effect effect = DynamoDbFaultEffects.sdkError(code);
            s.register(
                "read-events",
                1,
                FaultRegistry.Injection.REPLACE_REQUEST,
                new FaultRegistry.Effect() {
                  public boolean supports(FaultRegistry.Injection injection) {
                    return effect.supports(injection);
                  }

                  public HttpReply reply(DynamoDbRequestRecorder.Request request) {
                    HttpReply reply = effect.reply(request);
                    s.observation
                        .withArray("injected_http_errors")
                        .add(DynamoDbJson.read(reply.body()));
                    return reply;
                  }
                });
            Throwable failure =
                EventStoreExceptions.unwrap(
                    assertThrows(RuntimeException.class, () -> s.read(AID, 0)));
            assertInstanceOf(StorageException.class, failure);
            assertInstanceOf(DynamoDbException.class, failure.getCause());
            assertEquals(
                code, ((DynamoDbException) failure.getCause()).awsErrorDetails().errorCode());
            assertEquals(1, s.lastRequests().size());
            assertEquals(0, s.lastRequests().get(0).transmissions);
            assertEquals(1, s.lastRequests().get(0).httpAttempts);
            assertEvents(List.of(event(1)), s.read(AID, 0));
            s.assertQuery(AID, 0, 1);
          }
        });
  }

  @TestFactory
  Stream<DynamicTest> laterPageFailureReturnsNoPartialSuccessAndNextReadCompletes() {
    return both(
        "later-page-failure",
        s -> {
          List<EventEnvelope<byte[]>> expected = large(s);
          AtomicInteger pages = new AtomicInteger();
          s.register(
              "read-events",
              -1,
              FaultRegistry.Injection.REPLACE_RESPONSE,
              DynamoDbFaultEffects.response(
                  response -> {
                    s.observation
                        .withArray("responses_before_failure")
                        .add(DynamoDbJson.sdk(response));
                    if (pages.incrementAndGet() == 2)
                      throw SdkClientException.create("page response failure");
                    return response;
                  }));
          Throwable failure =
              EventStoreExceptions.unwrap(
                  assertThrows(RuntimeException.class, () -> s.read(AID, 0)));
          assertInstanceOf(StorageException.class, failure);
          assertEquals(2, pages.get());
          assertEquals(2, s.lastRequests().size());
          for (DynamoDbRequestRecorder.Request request : s.lastRequests())
            assertEquals(1, request.transmissions);
          assertEvents(expected, s.read(AID, 0));
          s.assertQuery(AID, 0, s.pages.size());
        });
  }

  @TestFactory
  Stream<DynamicTest> deserializeFaultAndJavaNullReturnSerializationAndAllowNextRead() {
    return Stream.of(false, true)
        .map(
            async ->
                DynamicTest.dynamicTest(
                    "deserialize-failures " + async,
                    () -> {
                      AtomicInteger mode = new AtomicInteger();
                      PayloadSerializer<byte[]> serializer =
                          new PayloadSerializer<byte[]>() {
                            public byte[] serialize(byte[] value) {
                              return value;
                            }

                            public byte[] deserialize(byte[] value) {
                              if (mode.get() == 1)
                                throw new IllegalStateException("restore failure");
                              if (mode.get() == 2) return null;
                              return value;
                            }
                          };
                      try (DynamoDbEventReadFixture.Scene<byte[]> s =
                          fixture.scene("deserialize-failures", async, serializer)) {
                        s.write(event(1));
                        SerializationException classified =
                            new SerializationException("injected deserialize");
                        s.register(
                            "deserialize-event",
                            1,
                            FaultRegistry.Injection.REPLACE_REQUEST,
                            DynamoDbFaultEffects.serializationError(classified));
                        Throwable failure =
                            EventStoreExceptions.unwrap(
                                assertThrows(RuntimeException.class, () -> s.read(AID, 0)));
                        assertSame(classified, failure);
                        s.assertQuery(AID, 0, 1);
                        for (int failureMode : List.of(1, 2)) {
                          mode.set(failureMode);
                          assertInstanceOf(
                              SerializationException.class,
                              EventStoreExceptions.unwrap(
                                  assertThrows(RuntimeException.class, () -> s.read(AID, 0))));
                          s.assertQuery(AID, 0, 1);
                          mode.set(0);
                          assertEvents(List.of(event(1)), s.read(AID, 0));
                          s.assertQuery(AID, 0, 1);
                        }
                      }
                    }));
  }

  @TestFactory
  Stream<DynamicTest> malformedJsonBinaryFailsInRealDeserializeOnBothPaths() {
    return Stream.of(false, true)
        .map(
            async ->
                DynamicTest.dynamicTest(
                    "malformed-json " + async,
                    () -> {
                      try (DynamoDbEventReadFixture.Scene<JsonNode> s =
                          fixture.scene(
                              "malformed-json", async, JsonPayloadSerializer.of(JsonNode.class))) {
                        EventEnvelope<JsonNode> event =
                            EventEnvelope.<JsonNode>builder()
                                .aggregateId(AID)
                                .seqNr(1)
                                .occurredAt(Instant.EPOCH)
                                .payload(DynamoDbJson.object())
                                .build();
                        s.write(event);
                        Map<String, AttributeValue> key =
                            Map.of(
                                "aid",
                                AttributeValue.fromS(AID.asString()),
                                "seq_nr",
                                AttributeValue.fromN("1"));
                        s.c.admin.updateItem(
                            UpdateItemRequest.builder()
                                .tableName(s.c.journal)
                                .key(key)
                                .updateExpression("SET payload = :payload")
                                .expressionAttributeValues(
                                    Map.of(
                                        ":payload",
                                        AttributeValue.fromB(SdkBytes.fromUtf8String("{"))))
                                .build());
                        assertInstanceOf(
                            SerializationException.class,
                            EventStoreExceptions.unwrap(
                                assertThrows(RuntimeException.class, () -> s.read(AID, 0))));
                        s.assertQuery(AID, 0, 1);
                      }
                    }));
  }

  @TestFactory
  Stream<DynamicTest> asynchronousPendingAndCancellationKeepRequestAliveUntilRealTermination() {
    return Stream.of(false, true)
        .map(
            cancel ->
                DynamicTest.dynamicTest(
                    "pending " + cancel,
                    () -> {
                      try (DynamoDbEventReadFixture.Scene<byte[]> s =
                          fixture.scene(
                              cancel ? "async-cancellation" : "async-pending", true, BYTES)) {
                        s.write(event(1));
                        CompletableFuture<Void> entered = new CompletableFuture<>(),
                            release = new CompletableFuture<>();
                        s.register(
                            "read-events",
                            1,
                            FaultRegistry.Injection.REPLACE_RESPONSE,
                            DynamoDbFaultEffects.response(
                                response -> {
                                  s.observation.set(
                                      "actual_pending_response", DynamoDbJson.sdk(response));
                                  entered.complete(null);
                                  release.join();
                                  return response;
                                }));
                        FaultRegistry.Operation operation =
                            s.c.faults.begin(s.nextOperation++, false);
                        CompletableFuture<List<EventEnvelope<byte[]>>> result =
                            s.asyncStore.getEventsByIdSinceSeqNr(AID, 0);
                        try {
                          entered.get(10, TimeUnit.SECONDS);
                          assertFalse(result.isDone());
                          assertEquals(1, s.c.faults.pending(operation));
                          assertFalse(s.c.recorder.requestsFinished(operation).isDone());
                          assertFalse(s.c.closed());
                          s.observation
                              .put("future_pending_before_release", !result.isDone())
                              .put("request_pending_before_release", s.c.faults.pending(operation));
                          if (cancel) {
                            assertTrue(result.cancel(true));
                            assertFalse(s.c.recorder.requestsFinished(operation).isDone());
                          }
                        } finally {
                          release.complete(null);
                          if (!cancel)
                            assertEvents(List.of(event(1)), result.get(10, TimeUnit.SECONDS));
                          s.finish(operation, null, null);
                        }
                        s.observation.put("future_cancelled", result.isCancelled());
                        assertEvents(List.of(event(1)), s.read(AID, 0));
                        s.assertQuery(AID, 0, 1);
                      }
                    }));
  }
}
