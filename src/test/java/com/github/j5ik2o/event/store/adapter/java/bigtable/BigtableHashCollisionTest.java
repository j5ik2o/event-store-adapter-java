package com.github.j5ik2o.event.store.adapter.java.bigtable;

import static com.github.j5ik2o.event.store.adapter.java.bigtable.BigtableTestFixtures.EMULATOR;
import static com.github.j5ik2o.event.store.adapter.java.bigtable.BigtableTestFixtures.event;
import static com.github.j5ik2o.event.store.adapter.java.bigtable.BigtableTestFixtures.open;
import static com.github.j5ik2o.event.store.adapter.java.bigtable.BigtableTestFixtures.snapshot;
import static com.github.j5ik2o.event.store.adapter.java.bigtable.BigtableTestFixtures.store;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.github.j5ik2o.event.store.adapter.java.core.AggregateId;
import com.github.j5ik2o.event.store.adapter.java.core.EventEnvelope;
import com.github.j5ik2o.event.store.adapter.java.core.EventStore;
import com.github.j5ik2o.event.store.adapter.java.core.RetentionPolicy;
import com.google.protobuf.ByteString;
import java.util.List;
import java.util.OptionalLong;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * BT-3: even when hashes collide, the P of a different aid is never a prefix match, so ranges and
 * retention never mix aggregates. The collision is forced through the hash hook.
 */
class BigtableHashCollisionTest {
  private static final List<AggregateId> COLLIDING =
      List.of(
          AggregateId.of("A", "1"),
          AggregateId.of("A", "12"),
          AggregateId.of("A", "1\u0000"),
          AggregateId.of("A", "ユーザー"));

  private static BigtableStorageHooks sameHash() {
    return new BigtableStorageHooks() {
      @Override
      public OptionalLong aggregateHash(String aid) {
        return OptionalLong.of(0x0123456789abcdefL);
      }
    };
  }

  @Test
  void readingOneAggregateReturnsOnlyItsOwnEventsWhenAllHashesCollide() {
    BigtableEmulator.Tables t = BigtableTestFixtures.noneStore();
    EventStore<String, String> store = store(open(t, RetentionPolicy.none(), sameHash()));
    for (AggregateId id : COLLIDING) {
      for (long seq = 1; seq <= 3; seq++) {
        store.persistEvent(event(id, seq));
      }
    }

    for (AggregateId id : COLLIDING) {
      List<EventEnvelope<String>> events = store.getEventsByIdSinceSeqNr(id, 1);
      assertEquals(List.of(1L, 2L, 3L), seqNrs(events), id.asString());
      for (EventEnvelope<String> e : events) {
        assertEquals(id, e.aggregateId());
      }
    }
  }

  @Test
  void theCollidingAggregatesShareTheHashButHaveDistinctHeadRows() {
    BigtableEmulator.Tables t = BigtableTestFixtures.noneStore();
    EventStore<String, String> store = store(open(t, RetentionPolicy.none(), sameHash()));
    for (AggregateId id : COLLIDING) {
      store.persistEvent(event(id, 1));
    }

    List<ByteString> keys =
        EMULATOR.rows(t.head).stream()
            .map(r -> r.getKey())
            .filter(k -> k.size() > 1)
            .collect(Collectors.toList());
    assertEquals(COLLIDING.size(), keys.size());
    for (ByteString key : keys) {
      assertEquals(
          ByteString.copyFrom(
              new byte[] {
                0x01, 0x23, 0x45, 0x67, (byte) 0x89, (byte) 0xab, (byte) 0xcd, (byte) 0xef
              }),
          key.substring(0, 8));
    }
  }

  @Test
  void retentionOfOneCollidingAggregateNeverRetiresTheHistoryOfAnother() {
    BigtableEmulator.Tables t = BigtableTestFixtures.deleteStore(2);
    EventStore<String, String> store = store(open(t, RetentionPolicy.delete(2), sameHash()));
    AggregateId first = COLLIDING.get(0);
    for (long seq = 1; seq <= 3; seq++) {
      store.persistEventAndSnapshot(event(first, seq), snapshot(seq));
    }
    // With keep 2 and head at 3, history 1 is retired and history 2 is still active.
    assertEquals("retired", state(t, first, 1));
    assertEquals("active", state(t, first, 2));

    for (AggregateId id : COLLIDING.subList(1, COLLIDING.size())) {
      for (long seq = 1; seq <= 5; seq++) {
        store.persistEventAndSnapshot(event(id, seq), snapshot(seq));
      }
    }

    assertEquals("retired", state(t, first, 1));
    assertEquals("active", state(t, first, 2));
    assertEquals(
        1, EMULATOR.cellCount(t.journal, journalKeyWithHash(first, 2), "history", "envelope"));
    for (AggregateId id : COLLIDING.subList(1, COLLIDING.size())) {
      assertEquals("retired", state(t, id, 1), id.asString());
      assertEquals("retired", state(t, id, 2), id.asString());
      assertEquals("retired", state(t, id, 3), id.asString());
      assertEquals("active", state(t, id, 4), id.asString());
    }
    assertEquals(List.of(1L, 2L, 3L), seqNrs(store.getEventsByIdSinceSeqNr(first, 1)));
  }

  private static String state(BigtableEmulator.Tables t, AggregateId id, long seqNr) {
    return EMULATOR.text(t.journal, journalKeyWithHash(id, seqNr), "history", "state");
  }

  private static ByteString journalKeyWithHash(AggregateId id, long seqNr) {
    ByteString prefix = BigtableKey.prefix(0x0123456789abcdefL, id.asString());
    return BigtableKey.journalRowKey(prefix, seqNr);
  }

  private static List<Long> seqNrs(List<EventEnvelope<String>> events) {
    return events.stream().map(EventEnvelope::seqNr).collect(Collectors.toList());
  }
}
