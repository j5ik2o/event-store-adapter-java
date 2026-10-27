package com.github.j5ik2o.event.store.adapter.java.spanner;

import com.github.j5ik2o.event.store.adapter.java.core.AggregateId;
import com.github.j5ik2o.event.store.adapter.java.core.EventEnvelope;
import com.github.j5ik2o.event.store.adapter.java.core.EventStore;
import com.github.j5ik2o.event.store.adapter.java.core.EventStoreConfig;
import com.github.j5ik2o.event.store.adapter.java.core.JsonPayloadSerializer;
import com.github.j5ik2o.event.store.adapter.java.core.SnapshotEnvelope;
import java.time.Instant;

/** Shared helpers for Spanner tests. Every test runs against the real emulator. */
final class SpannerTestFixtures {
  static final AggregateId AID = AggregateId.of("Order", "1");
  static final SpannerEmulator EMULATOR = SpannerEmulator.shared();

  private SpannerTestFixtures() {}

  /** A store whose configuration row agrees with a client that has no retention. */
  static String noneStore() {
    return EMULATOR.createStore(null, SpannerEmulator.ConfigRow.matching(null, "delete", null));
  }

  static String deleteStore(int keep) {
    return EMULATOR.createStore(null, SpannerEmulator.ConfigRow.matching(keep, "delete", null));
  }

  static String ttlStore(int keep, long graceDays) {
    return EMULATOR.createStore(
        (int) graceDays, SpannerEmulator.ConfigRow.matching(keep, "ttl", graceDays));
  }

  static SpannerStorageConfig settings(String prefix, SpannerRetentionPolicy policy) {
    return SpannerStorageConfig.builder().tablePrefix(prefix).retentionPolicy(policy).build();
  }

  static SpannerStorage open(String prefix, SpannerRetentionPolicy policy) {
    return SpannerStorage.open(EMULATOR.client(), settings(prefix, policy));
  }

  static SpannerStorage open(
      String prefix, SpannerRetentionPolicy policy, SpannerStorageHooks hooks) {
    return SpannerStorage.open(EMULATOR.client(), settings(prefix, policy), hooks);
  }

  static EventStoreConfig<String, String> config() {
    return EventStoreConfig.<String, String>builder()
        .payloadSerializer(JsonPayloadSerializer.of(String.class))
        .snapshotSerializer(JsonPayloadSerializer.of(String.class))
        .build();
  }

  static EventStore<String, String> store(SpannerStorage storage) {
    return SpannerEventStore.create(storage, config());
  }

  static EventEnvelope<String> event(long seqNr) {
    return event(AID, seqNr, Instant.EPOCH);
  }

  static EventEnvelope<String> event(AggregateId id, long seqNr) {
    return event(id, seqNr, Instant.EPOCH);
  }

  static EventEnvelope<String> event(AggregateId id, long seqNr, Instant occurredAt) {
    return EventEnvelope.<String>builder()
        .aggregateId(id)
        .seqNr(seqNr)
        .occurredAt(occurredAt)
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

  /** The primary-key hash prefix of an aggregate as a GoogleSQL FROM_HEX literal. */
  static String hashPrefixLiteral(AggregateId id) {
    StringBuilder hex = new StringBuilder();
    for (byte b : SpannerKey.of(id).hashPrefix()) {
      hex.append(String.format("%02x", b));
    }
    return "FROM_HEX('" + hex + "')";
  }
}
