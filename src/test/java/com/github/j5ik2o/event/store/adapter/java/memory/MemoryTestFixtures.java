package com.github.j5ik2o.event.store.adapter.java.memory;

import com.github.j5ik2o.event.store.adapter.java.core.AggregateId;
import com.github.j5ik2o.event.store.adapter.java.core.EventEnvelope;
import com.github.j5ik2o.event.store.adapter.java.core.EventStore;
import com.github.j5ik2o.event.store.adapter.java.core.EventStoreConfig;
import com.github.j5ik2o.event.store.adapter.java.core.JsonPayloadSerializer;
import com.github.j5ik2o.event.store.adapter.java.core.SnapshotEnvelope;
import java.time.Instant;

final class MemoryTestFixtures {
  static final AggregateId AID = AggregateId.of("Order", "1");

  private MemoryTestFixtures() {}

  static EventStore<String, String> store(MemoryStorage storage) {
    return MemoryEventStore.create(storage, config());
  }

  static EventStoreConfig<String, String> config() {
    return EventStoreConfig.<String, String>builder()
        .payloadSerializer(JsonPayloadSerializer.of(String.class))
        .snapshotSerializer(JsonPayloadSerializer.of(String.class))
        .build();
  }

  static EventEnvelope<String> event(long seqNr) {
    return EventEnvelope.<String>builder()
        .aggregateId(AID)
        .seqNr(seqNr)
        .occurredAt(Instant.EPOCH)
        .manifest("event")
        .payload("event-" + seqNr)
        .build();
  }

  static SnapshotEnvelope<String> snapshot(long seqNr) {
    return SnapshotEnvelope.<String>builder()
        .seqNr(seqNr)
        .manifest("snapshot")
        .aggregate("snapshot-" + seqNr)
        .build();
  }
}
