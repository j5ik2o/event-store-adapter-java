package com.github.j5ik2o.event.store.adapter.java.bigtable;

import com.github.j5ik2o.event.store.adapter.java.core.AggregateId;
import com.github.j5ik2o.event.store.adapter.java.core.EventEnvelope;
import com.github.j5ik2o.event.store.adapter.java.core.EventStore;
import com.github.j5ik2o.event.store.adapter.java.core.EventStoreConfig;
import com.github.j5ik2o.event.store.adapter.java.core.JsonPayloadSerializer;
import com.github.j5ik2o.event.store.adapter.java.core.RetentionPolicy;
import com.github.j5ik2o.event.store.adapter.java.core.SnapshotEnvelope;
import com.google.protobuf.ByteString;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

/** Shared helpers for Bigtable tests. Every test runs against the real emulator. */
final class BigtableTestFixtures {
  static final AggregateId AID = AggregateId.of("Order", "1");
  static final BigtableEmulator EMULATOR = BigtableEmulator.shared();

  private BigtableTestFixtures() {}

  /**
   * The normative head seq_nr cell: 20 ASCII digits, zero padded. Built here with ASCII '0' and
   * independent of the library, and of the default locale.
   */
  static String headSeqNr(long n) {
    String digits = Long.toString(n);
    StringBuilder out = new StringBuilder(20);
    for (int i = digits.length(); i < 20; i++) out.append('0');
    return out.append(digits).toString();
  }

  /** A store whose configuration rows agree with a client that has no retention. */
  static BigtableEmulator.Tables noneStore() {
    return EMULATOR.createStore(null);
  }

  static BigtableEmulator.Tables deleteStore(int keep) {
    return EMULATOR.createStore(Integer.toString(keep));
  }

  static BigtableStorageConfig settings(BigtableEmulator.Tables t, RetentionPolicy policy) {
    return BigtableStorageConfig.builder()
        .journalTableId(t.journal)
        .headTableId(t.head)
        .retentionPolicy(policy)
        .build();
  }

  static BigtableStorage open(BigtableEmulator.Tables t, RetentionPolicy policy) {
    return BigtableStorage.open(EMULATOR.data(), EMULATOR.admin(), settings(t, policy));
  }

  static BigtableStorage open(
      BigtableEmulator.Tables t, RetentionPolicy policy, BigtableStorageHooks hooks) {
    return BigtableStorage.open(EMULATOR.data(), EMULATOR.admin(), settings(t, policy), hooks);
  }

  static EventStoreConfig<String, String> config() {
    return EventStoreConfig.<String, String>builder()
        .payloadSerializer(JsonPayloadSerializer.of(String.class))
        .snapshotSerializer(JsonPayloadSerializer.of(String.class))
        .build();
  }

  static EventStore<String, String> store(BigtableStorage storage) {
    return BigtableEventStore.create(storage, config());
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

  /**
   * The row key prefix of an aggregate, built here from the normative formula (FNV-1a 64, 16-bit
   * length, UTF-8) and not through the library's key class, so the tests keep an independent
   * oracle.
   */
  static ByteString prefix(String aid) {
    byte[] a = aid.getBytes(StandardCharsets.UTF_8);
    long h = 0xcbf29ce484222325L;
    for (byte b : a) {
      h ^= (b & 0xffL);
      h *= 0x100000001b3L;
    }
    byte[] out = new byte[8 + 2 + a.length];
    for (int i = 0; i < 8; i++) out[i] = (byte) (h >>> (56 - 8 * i));
    out[8] = (byte) (a.length >>> 8);
    out[9] = (byte) a.length;
    System.arraycopy(a, 0, out, 10, a.length);
    return ByteString.copyFrom(out);
  }

  static ByteString journalKey(String aid, long seqNr) {
    byte[] n = new byte[8];
    for (int i = 0; i < 8; i++) n[i] = (byte) (seqNr >>> (56 - 8 * i));
    return prefix(aid).concat(ByteString.copyFrom(n));
  }
}
