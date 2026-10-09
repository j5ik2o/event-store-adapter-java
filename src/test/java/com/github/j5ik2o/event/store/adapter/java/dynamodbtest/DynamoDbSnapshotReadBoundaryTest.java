package com.github.j5ik2o.event.store.adapter.java.dynamodbtest;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.j5ik2o.event.store.adapter.java.core.*;
import com.github.j5ik2o.event.store.adapter.java.dynamodb.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.extension.RegisterExtension;
import software.amazon.awssdk.services.dynamodb.model.*;

class DynamoDbSnapshotReadBoundaryTest {
  @RegisterExtension
  static final DynamoDbConfigurationFixture configuration = new DynamoDbConfigurationFixture();

  private static final DynamoDbSnapshotReadFixture fixture =
      new DynamoDbSnapshotReadFixture(configuration);
  private static final AggregateId ID = AggregateId.of("売買", "x-\"é");
  private static final PayloadSerializer<byte[]> BYTES =
      new PayloadSerializer<byte[]>() {
        public byte[] serialize(byte[] value) {
          return value;
        }

        public byte[] deserialize(byte[] value) {
          return value;
        }
      };
  private static final PayloadSerializer<byte[]> SNAPSHOTS =
      new PayloadSerializer<byte[]>() {
        public byte[] serialize(byte[] value) {
          byte[] stored = new byte[value.length + 1];
          stored[0] = 42;
          System.arraycopy(value, 0, stored, 1, value.length);
          return stored;
        }

        public byte[] deserialize(byte[] value) {
          assertEquals(42, value[0]);
          return Arrays.copyOfRange(value, 1, value.length);
        }
      };

  private interface Scenario {
    void run(DynamoDbSnapshotReadFixture.Scene<byte[], byte[]> s) throws Exception;
  }

  private Stream<DynamicTest> both(String name, Scenario action) {
    return Stream.of(false, true)
        .map(
            async ->
                DynamicTest.dynamicTest(
                    name + (async ? " async" : " sync"),
                    () -> {
                      try (DynamoDbSnapshotReadFixture.Scene<byte[], byte[]> s =
                          fixture.scene(name, async, BYTES, SNAPSHOTS)) {
                        action.run(s);
                      }
                    }));
  }

  private static EventEnvelope<byte[]> event(long seq) {
    return EventEnvelope.<byte[]>builder()
        .aggregateId(ID)
        .seqNr(seq)
        .occurredAt(Instant.ofEpochSecond(0, -1))
        .manifest("event-" + seq)
        .payload(new byte[] {(byte) seq, 0, (byte) 255})
        .build();
  }

  private static SnapshotEnvelope<byte[]> snapshot(long seq) {
    return SnapshotEnvelope.<byte[]>builder()
        .seqNr(seq)
        .manifest("snapshot-非解釈é")
        .aggregate(new byte[] {(byte) seq, (byte) 255, 0})
        .build();
  }

  private static void assertSnapshot(
      SnapshotEnvelope<byte[]> expected, SnapshotEnvelope<byte[]> actual) {
    assertEquals(expected.seqNr(), actual.seqNr());
    assertEquals(expected.manifest(), actual.manifest());
    assertArrayEquals(expected.aggregate(), actual.aggregate());
  }

  private static void assertStored(
      DynamoDbSnapshotReadFixture.Scene<byte[], byte[]> s, SnapshotReadResult<byte[]> result) {
    Map<String, AttributeValue> head = s.stored("head", ID), current = s.stored("snapshot", ID);
    s.last().set("stored_head", DynamoDbJson.sdk(head));
    s.last().set("stored_current", DynamoDbJson.sdk(current));
    assertEquals(Long.parseLong(head.get("seq_nr").n()), result.headSeqNr());
    SnapshotEnvelope<byte[]> snapshot = result.snapshot().orElseThrow();
    assertEquals(Long.parseLong(current.get("seq_nr").n()), snapshot.seqNr());
    assertEquals(current.get("manifest").s(), snapshot.manifest());
    assertArrayEquals(
        SNAPSHOTS.deserialize(current.get("payload").b().asByteArray()), snapshot.aggregate());
  }

  private static void assertReplay(
      DynamoDbSnapshotReadFixture.Scene<byte[], byte[]> s,
      SnapshotReadResult<byte[]> result,
      List<Long> expected) {
    long start = result.snapshot().map(v -> v.seqNr() + 1).orElse(1L);
    List<EventEnvelope<byte[]>> events = s.readEvents(ID, start);
    assertEquals(
        expected,
        events.stream().map(EventEnvelope::seqNr).collect(java.util.stream.Collectors.toList()));
    for (EventEnvelope<byte[]> event : events)
      assertArrayEquals(event(event.seqNr()).payload(), event.payload());
    assertEquals(1, s.lastRequests().size());
    DynamoDbRequestRecorder.Request query = s.lastRequests().get(0);
    assertEquals("Query", query.api);
    assertEquals("read-events", query.phase);
    assertTrue(query.transmitted.path("ConsistentRead").booleanValue());
    assertTrue(query.transmitted.path("ScanIndexForward").booleanValue());
    assertEquals(
        Long.toString(start),
        query.transmitted.at("/ExpressionAttributeValues/:seq_nr/N").asText());
    assertEquals(ID.asString(), query.transmitted.at("/ExpressionAttributeValues/:aid/S").asText());
    s.last().put("replay_start_seq_nr", start);
  }

  @TestFactory
  Stream<DynamicTest> sameStoreReadsMissingHeadOnlyCurrentAndEventOnlyAdvanceFromActualWrites() {
    return both(
        "continuous-save-and-restore",
        s -> {
          assertTrue(s.read(ID).isEmpty());
          s.assertBatch(ID, 1);
          s.write(event(1));
          SnapshotReadResult<byte[]> headOnly = s.read(ID).orElseThrow();
          assertEquals(1, headOnly.headSeqNr());
          assertTrue(headOnly.snapshot().isEmpty());
          s.assertBatch(ID, 1);
          s.write(event(2), snapshot(2));
          SnapshotReadResult<byte[]> current = s.read(ID).orElseThrow();
          s.assertBatch(ID, 1);
          assertSnapshot(snapshot(2), current.snapshot().orElseThrow());
          assertStored(s, current);
          Map<String, AttributeValue> before = s.stored("snapshot", ID);
          s.write(event(3));
          s.write(event(4));
          SnapshotReadResult<byte[]> behind = s.read(ID).orElseThrow();
          s.assertBatch(ID, 1);
          assertEquals(4, behind.headSeqNr());
          assertSnapshot(snapshot(2), behind.snapshot().orElseThrow());
          assertStored(s, behind);
          assertEquals(before, s.stored("snapshot", ID));
          assertReplay(s, behind, List.of(3L, 4L));
        });
  }

  @TestFactory
  Stream<DynamicTest>
      actualCommitInterleavingReturnsOldHeadAndNewCurrentThenReplaysFromReturnedSnapshot() {
    return both(
        "non-atomic-ahead-of-head",
        s -> {
          s.write(event(1), snapshot(1));
          assertSnapshot(snapshot(1), s.read(ID).orElseThrow().snapshot().orElseThrow());
          Map<String, AttributeValue> oldHead = s.stored("head", ID);
          s.observation.set("head_before_interleave", DynamoDbJson.sdk(oldHead));
          s.register(
              "read-snapshot",
              1,
              FaultRegistry.Injection.REPLACE_RESPONSE,
              DynamoDbFaultEffects.readInterleave(
                  s.c.admin,
                  s.c.head,
                  () -> {
                    EventStore<byte[], byte[]> writer =
                        DynamoDbEventStore.create(
                            s.c.admin, DynamoDbConfigurationTables.config(s.c).build(), s.config);
                    writer.persistEventAndSnapshot(event(2), snapshot(2));
                    s.observation.set(
                        "current_after_interleave", DynamoDbJson.sdk(s.stored("snapshot", ID)));
                  }));
          SnapshotReadResult<byte[]> mixed = s.read(ID).orElseThrow();
          s.assertBatch(ID, 1);
          assertEquals(Long.parseLong(oldHead.get("seq_nr").n()), mixed.headSeqNr());
          Map<String, AttributeValue> actualCurrent = s.stored("snapshot", ID);
          assertEquals(
              Long.parseLong(actualCurrent.get("seq_nr").n()),
              mixed.snapshot().orElseThrow().seqNr());
          assertArrayEquals(
              SNAPSHOTS.deserialize(actualCurrent.get("payload").b().asByteArray()),
              mixed.snapshot().orElseThrow().aggregate());
          assertTrue(mixed.snapshot().orElseThrow().seqNr() > mixed.headSeqNr());
          s.write(event(3));
          assertReplay(s, mixed, List.of(3L));
          SnapshotReadResult<byte[]> fresh = s.read(ID).orElseThrow();
          assertStored(s, fresh);
          assertEquals(3, fresh.headSeqNr());
          assertEquals(2, fresh.snapshot().orElseThrow().seqNr());
        });
  }

  @TestFactory
  Stream<DynamicTest>
      partialResponsesAndRetryExhaustionHaveSymmetricRequestsTransmissionsAndApplications() {
    return Stream.of("head", "snapshot")
        .flatMap(
            pending ->
                Stream.of(false, true)
                    .flatMap(
                        exhausted ->
                            both(
                                "partial-" + pending + (exhausted ? "-exhausted" : "-success"),
                                s -> {
                                  s.write(event(1), snapshot(1));
                                  String table = DynamoDbConfigurationFixture.table(s.c, pending);
                                  s.register(
                                      "read-snapshot",
                                      exhausted ? -1 : 1,
                                      FaultRegistry.Injection.REPLACE_RESPONSE,
                                      DynamoDbFaultEffects.partialBatchGet(
                                          s.c.admin,
                                          Map.of(
                                              table,
                                              List.of(
                                                  DynamoDbSnapshotReadFixture.dataKey(
                                                      pending, ID.asString())))));
                                  if (exhausted) {
                                    assertInstanceOf(
                                        StorageException.class,
                                        EventStoreExceptions.unwrap(
                                            assertThrows(
                                                RuntimeException.class, () -> s.read(ID))));
                                  } else {
                                    SnapshotReadResult<byte[]> result = s.read(ID).orElseThrow();
                                    assertEquals(1, result.headSeqNr());
                                    assertSnapshot(snapshot(1), result.snapshot().orElseThrow());
                                    assertStored(s, result);
                                  }
                                  int count = exhausted ? 4 : 2;
                                  s.assertBatch(ID, count);
                                  for (int i = 1; i < count; i++) {
                                    JsonNode remaining =
                                        s.lastRequests().get(i).transmitted.path("RequestItems");
                                    assertEquals(
                                        Set.of(table),
                                        DynamoDbConfigurationFixture.stringsFromFields(remaining));
                                  }
                                  assertEquals(
                                      exhausted ? List.of(50L, 100L, 200L) : List.of(50L), s.waits);
                                  assertEquals(
                                      exhausted ? 4 : 1,
                                      s.last().at("/faults/0/applications").intValue());
                                  assertEquals(0, s.last().at("/faults/0/reservations").intValue());
                                  assertEquals(
                                      count,
                                      s.lastRequests().stream()
                                          .mapToInt(r -> r.transmissions)
                                          .sum());
                                  assertEquals(
                                      count,
                                      s.lastRequests().stream()
                                          .mapToInt(r -> r.httpAttempts)
                                          .sum());
                                })));
  }

  @TestFactory
  Stream<DynamicTest> realMalformedStoredValuesFailAsStorageBeforeDeserializing() {
    Map<String, Consumer<Map<String, AttributeValue>>> mutations = new LinkedHashMap<>();
    mutations.put("missing-seq", item -> item.remove("seq_nr"));
    mutations.put("type-seq", item -> item.put("seq_nr", AttributeValue.fromS("1")));
    mutations.put("fraction-seq", item -> item.put("seq_nr", AttributeValue.fromN("1.5")));
    mutations.put("negative-seq", item -> item.put("seq_nr", AttributeValue.fromN("-1")));
    mutations.put(
        "above-max-seq", item -> item.put("seq_nr", AttributeValue.fromN("9007199254740992")));
    mutations.put("missing-manifest", item -> item.remove("manifest"));
    mutations.put("type-manifest", item -> item.put("manifest", AttributeValue.fromN("1")));
    mutations.put("missing-payload", item -> item.remove("payload"));
    mutations.put("type-payload", item -> item.put("payload", AttributeValue.fromS("bytes")));
    mutations.put("snapshot-negative-seq", item -> item.put("seq_nr", AttributeValue.fromN("-1")));
    return mutations.entrySet().stream()
        .flatMap(
            mutation ->
                both(
                    "stored-" + mutation.getKey(),
                    s -> {
                      s.write(event(1), snapshot(1));
                      String role =
                          mutation.getKey().contains("seq")
                                  && !mutation.getKey().startsWith("snapshot-")
                              ? "head"
                              : "snapshot";
                      Map<String, AttributeValue> corrupt = new LinkedHashMap<>(s.stored(role, ID));
                      mutation.getValue().accept(corrupt);
                      s.c.admin.putItem(
                          PutItemRequest.builder()
                              .tableName(DynamoDbConfigurationFixture.table(s.c, role))
                              .item(corrupt)
                              .build());
                      s.observation.set(
                          "corrupted_saved_item", DynamoDbJson.sdk(s.stored(role, ID)));
                      assertInstanceOf(
                          StorageException.class,
                          EventStoreExceptions.unwrap(
                              assertThrows(RuntimeException.class, () -> s.read(ID))));
                      s.assertBatch(ID, 1);
                    }));
  }

  @TestFactory
  Stream<DynamicTest>
      deserializeFailureAndSdkFailureHaveDirectClassificationsAndRealRequestCounts() {
    return Stream.of("deserialize-snapshot", "read-snapshot")
        .flatMap(
            phase ->
                both(
                    "failure-" + phase,
                    s -> {
                      s.write(event(1), snapshot(1));
                      s.register(
                          phase,
                          1,
                          FaultRegistry.Injection.REPLACE_REQUEST,
                          phase.equals("deserialize-snapshot")
                              ? DynamoDbFaultEffects.serializationError(
                                  new SerializationException("decode"))
                              : DynamoDbFaultEffects.sdkError("InternalServerError"));
                      Throwable failure;
                      if (s.asynchronous) {
                        FaultRegistry.Operation operation =
                            s.c.faults.begin(s.nextOperation++, false);
                        failure =
                            s.asyncStore
                                .getLatestSnapshotById(ID)
                                .handle((value, error) -> error)
                                .join();
                        s.finish(operation, failure, null);
                      } else failure = assertThrows(RuntimeException.class, () -> s.read(ID));
                      if (phase.equals("deserialize-snapshot"))
                        assertInstanceOf(SerializationException.class, failure);
                      else assertInstanceOf(StorageException.class, failure);
                      s.assertBatch(ID, 1);
                      assertEquals(
                          phase.equals("deserialize-snapshot") ? 1 : 0,
                          s.lastRequests().get(0).transmissions);
                      assertEquals(1, s.last().at("/faults/0/applications").intValue());
                    }));
  }

  @TestFactory
  Stream<DynamicTest>
      asynchronousPendingAndCancellationWaitForActualRequestTerminationBeforeClosingResources() {
    return Stream.of(false, true)
        .map(
            cancel ->
                DynamicTest.dynamicTest(
                    "pending cancellation " + cancel,
                    () -> {
                      try (DynamoDbSnapshotReadFixture.Scene<byte[], byte[]> s =
                          fixture.scene(
                              cancel ? "async-cancellation" : "async-pending",
                              true,
                              BYTES,
                              SNAPSHOTS)) {
                        s.write(event(1), snapshot(1));
                        CompletableFuture<Void> entered = new CompletableFuture<>(),
                            release = new CompletableFuture<>();
                        s.register(
                            "read-snapshot",
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
                        CompletableFuture<Optional<SnapshotReadResult<byte[]>>> result =
                            s.asyncStore.getLatestSnapshotById(ID);
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
                            assertSnapshot(
                                snapshot(1),
                                result
                                    .get(10, TimeUnit.SECONDS)
                                    .orElseThrow()
                                    .snapshot()
                                    .orElseThrow());
                          s.finish(operation, null, null);
                        }
                        s.observation.put("future_cancelled", result.isCancelled());
                        assertSnapshot(
                            snapshot(1), s.read(ID).orElseThrow().snapshot().orElseThrow());
                        s.assertBatch(ID, 1);
                      }
                    }));
  }
}
