package com.github.j5ik2o.event.store.adapter.java.memory;

import static com.github.j5ik2o.event.store.adapter.java.memory.MemoryTestFixtures.AID;
import static com.github.j5ik2o.event.store.adapter.java.memory.MemoryTestFixtures.event;
import static com.github.j5ik2o.event.store.adapter.java.memory.MemoryTestFixtures.snapshot;
import static com.github.j5ik2o.event.store.adapter.java.memory.MemoryTestFixtures.store;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.j5ik2o.event.store.adapter.java.core.EventEnvelope;
import com.github.j5ik2o.event.store.adapter.java.core.EventStore;
import com.github.j5ik2o.event.store.adapter.java.core.EventStoreConfig;
import com.github.j5ik2o.event.store.adapter.java.core.JsonPayloadSerializer;
import com.github.j5ik2o.event.store.adapter.java.core.PayloadSerializer;
import com.github.j5ik2o.event.store.adapter.java.core.SnapshotEnvelope;
import com.github.j5ik2o.event.store.adapter.java.core.SnapshotReadResult;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class MemoryReadIsolationTest {
  @Test
  void serializerOwnedBytesAndDeserializerMutationsCannotChangeStoredPayload() {
    PayloadSerializer<byte[]> serializer =
        new PayloadSerializer<byte[]>() {
          public byte[] serialize(byte[] value) {
            return value;
          }

          public byte[] deserialize(byte[] bytes) {
            byte[] result = bytes.clone();
            bytes[0] = 99;
            return result;
          }
        };
    EventStore<byte[], byte[]> store =
        MemoryEventStore.create(
            MemoryStorage.create(),
            EventStoreConfig.<byte[], byte[]>builder()
                .payloadSerializer(serializer)
                .snapshotSerializer(serializer)
                .build());
    byte[] eventInput = {1, 2};
    byte[] snapshotInput = {3, 4};
    store.persistEventAndSnapshot(
        EventEnvelope.<byte[]>builder()
            .aggregateId(AID)
            .seqNr(1)
            .occurredAt(Instant.EPOCH)
            .payload(eventInput)
            .build(),
        SnapshotEnvelope.<byte[]>builder().seqNr(1).aggregate(snapshotInput).build());

    eventInput[0] = 88;
    snapshotInput[0] = 88;
    store.getEventsByIdSinceSeqNr(AID, 0);
    store.getLatestSnapshotById(AID);

    assertArrayEquals(new byte[] {1, 2}, store.getEventsByIdSinceSeqNr(AID, 0).get(0).payload());
    assertArrayEquals(
        new byte[] {3, 4}, store.getLatestSnapshotById(AID).get().snapshot().get().aggregate());
  }

  @Test
  void eventDeserializationRunsOutsideLockAndUsesOneCapturedEventList() throws Exception {
    MemoryStorage storage = MemoryStorage.create();
    EventStore<String, String> writer = store(storage);
    writer.persistEvent(event(1));
    writer.persistEvent(event(2));
    ExecutorService executor = Executors.newSingleThreadExecutor();
    PayloadSerializer<String> delegate = JsonPayloadSerializer.of(String.class);
    PayloadSerializer<String> serializer =
        new PayloadSerializer<String>() {
          boolean appended;

          public byte[] serialize(String value) {
            return delegate.serialize(value);
          }

          public String deserialize(byte[] bytes) {
            if (!appended) {
              appended = true;
              try {
                executor.submit(() -> writer.persistEvent(event(3))).get(5, TimeUnit.SECONDS);
              } catch (Exception e) {
                throw new AssertionError(
                    "another thread must be able to append during deserialization", e);
              }
            }
            return delegate.deserialize(bytes);
          }
        };
    EventStore<String, String> reader =
        MemoryEventStore.create(
            storage,
            EventStoreConfig.<String, String>builder()
                .payloadSerializer(serializer)
                .snapshotSerializer(delegate)
                .build());
    try {
      List<EventEnvelope<String>> captured = reader.getEventsByIdSinceSeqNr(AID, 0);

      assertEquals(
          List.of(1L, 2L),
          captured.stream().map(EventEnvelope::seqNr).collect(Collectors.toList()));
      assertEquals(3, writer.getEventsByIdSinceSeqNr(AID, 0).size());
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  void eventReadIncludesBoundaryAndPreservesAscendingEnvelopes() {
    EventStore<String, String> store = store(MemoryStorage.create());
    store.persistEvent(event(1));
    store.persistEventAndSnapshot(event(2), snapshot(2));
    store.persistEvent(event(3));

    List<EventEnvelope<String>> events = store.getEventsByIdSinceSeqNr(AID, 2);

    assertEquals(
        List.of(2L, 3L), events.stream().map(EventEnvelope::seqNr).collect(Collectors.toList()));
    assertEquals("event-2", events.get(0).payload());
    assertEquals(AID, events.get(0).aggregateId());
    assertEquals("event", events.get(0).manifest());
    assertEquals(Instant.EPOCH, events.get(0).occurredAt());
    assertEquals(2, store.getLatestSnapshotById(AID).get().snapshot().get().seqNr());
    assertEquals(3, store.getLatestSnapshotById(AID).get().headSeqNr());
  }

  @Test
  void changesToInputsAndReturnedPayloadsCannotChangeStoredValues() {
    PayloadSerializer<JsonNode> serializer = JsonPayloadSerializer.of(JsonNode.class);
    EventStore<JsonNode, JsonNode> store =
        MemoryEventStore.create(
            MemoryStorage.create(),
            EventStoreConfig.<JsonNode, JsonNode>builder()
                .payloadSerializer(serializer)
                .snapshotSerializer(serializer)
                .build());
    ObjectNode payload = JsonNodeFactory.instance.objectNode().put("value", "original-event");
    ObjectNode aggregate = JsonNodeFactory.instance.objectNode().put("value", "original-snapshot");
    store.persistEventAndSnapshot(
        EventEnvelope.<JsonNode>builder()
            .aggregateId(AID)
            .seqNr(1)
            .occurredAt(Instant.EPOCH)
            .payload(payload)
            .build(),
        SnapshotEnvelope.<JsonNode>builder().seqNr(1).aggregate(aggregate).build());

    payload.put("value", "changed-input");
    aggregate.put("value", "changed-input");
    JsonNode readEvent = store.getEventsByIdSinceSeqNr(AID, 0).get(0).payload();
    JsonNode readSnapshot = store.getLatestSnapshotById(AID).get().snapshot().get().aggregate();
    assertEquals("original-event", readEvent.path("value").asText());
    assertEquals("original-snapshot", readSnapshot.path("value").asText());
    ((ObjectNode) readEvent).put("value", "changed-result");
    ((ObjectNode) readSnapshot).put("value", "changed-result");

    assertEquals(
        "original-event",
        store.getEventsByIdSinceSeqNr(AID, 0).get(0).payload().path("value").asText());
    assertEquals(
        "original-snapshot",
        store.getLatestSnapshotById(AID).get().snapshot().get().aggregate().path("value").asText());
  }

  @Test
  void deserializationAllowsAnotherThreadToAppendAndReadKeepsCapturedState() throws Exception {
    MemoryStorage storage = MemoryStorage.create();
    EventStore<String, String> writer = store(storage);
    writer.persistEventAndSnapshot(event(1), snapshot(1));
    ExecutorService executor = Executors.newSingleThreadExecutor();
    PayloadSerializer<String> delegate = JsonPayloadSerializer.of(String.class);
    PayloadSerializer<String> serializer =
        new PayloadSerializer<String>() {
          public byte[] serialize(String value) {
            return delegate.serialize(value);
          }

          public String deserialize(byte[] bytes) {
            try {
              executor
                  .submit(() -> writer.persistEventAndSnapshot(event(2), snapshot(2)))
                  .get(5, TimeUnit.SECONDS);
            } catch (Exception e) {
              throw new AssertionError(
                  "another thread must be able to append during deserialization", e);
            }
            return delegate.deserialize(bytes);
          }
        };
    EventStore<String, String> reader =
        MemoryEventStore.create(
            storage,
            EventStoreConfig.<String, String>builder()
                .payloadSerializer(delegate)
                .snapshotSerializer(serializer)
                .build());
    try {
      SnapshotReadResult<String> captured = reader.getLatestSnapshotById(AID).get();

      assertEquals(1, captured.headSeqNr());
      assertEquals(1, captured.snapshot().get().seqNr());
      assertEquals("snapshot-1", captured.snapshot().get().aggregate());
      assertEquals(2, writer.getLatestSnapshotById(AID).get().headSeqNr());
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  void jsonRootNullRoundTripsThroughBothEnvelopes() {
    PayloadSerializer<JsonNode> serializer = JsonPayloadSerializer.of(JsonNode.class);
    EventStore<JsonNode, JsonNode> store =
        MemoryEventStore.create(
            MemoryStorage.create(),
            EventStoreConfig.<JsonNode, JsonNode>builder()
                .payloadSerializer(serializer)
                .snapshotSerializer(serializer)
                .build());
    JsonNode value = JsonNodeFactory.instance.nullNode();

    store.persistEventAndSnapshot(
        EventEnvelope.<JsonNode>builder()
            .aggregateId(AID)
            .seqNr(1)
            .occurredAt(Instant.EPOCH)
            .payload(value)
            .build(),
        SnapshotEnvelope.<JsonNode>builder().seqNr(1).aggregate(value).build());

    assertTrue(store.getEventsByIdSinceSeqNr(AID, 0).get(0).payload().isNull());
    assertTrue(store.getLatestSnapshotById(AID).get().snapshot().get().aggregate().isNull());
  }
}
