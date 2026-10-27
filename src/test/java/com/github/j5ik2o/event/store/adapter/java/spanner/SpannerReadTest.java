package com.github.j5ik2o.event.store.adapter.java.spanner;

import static com.github.j5ik2o.event.store.adapter.java.spanner.SpannerTestFixtures.AID;
import static com.github.j5ik2o.event.store.adapter.java.spanner.SpannerTestFixtures.EMULATOR;
import static com.github.j5ik2o.event.store.adapter.java.spanner.SpannerTestFixtures.deleteStore;
import static com.github.j5ik2o.event.store.adapter.java.spanner.SpannerTestFixtures.event;
import static com.github.j5ik2o.event.store.adapter.java.spanner.SpannerTestFixtures.noneStore;
import static com.github.j5ik2o.event.store.adapter.java.spanner.SpannerTestFixtures.open;
import static com.github.j5ik2o.event.store.adapter.java.spanner.SpannerTestFixtures.snapshot;
import static com.github.j5ik2o.event.store.adapter.java.spanner.SpannerTestFixtures.store;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.j5ik2o.event.store.adapter.java.core.AggregateId;
import com.github.j5ik2o.event.store.adapter.java.core.EventEnvelope;
import com.github.j5ik2o.event.store.adapter.java.core.EventStore;
import com.github.j5ik2o.event.store.adapter.java.core.SnapshotReadResult;
import com.github.j5ik2o.event.store.adapter.java.core.StorageException;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class SpannerReadTest {
  @Test
  void anAggregateWithoutAHeadReadsAsNoSnapshotAndNoEvents() {
    EventStore<String, String> store = store(open(noneStore(), SpannerRetentionPolicy.none()));

    assertFalse(store.getLatestSnapshotById(AID).isPresent());
    assertTrue(store.getEventsByIdSinceSeqNr(AID, 0).isEmpty());
  }

  @Test
  void withoutASnapshotOnlyTheHeadPositionIsReturned() {
    EventStore<String, String> store = store(open(noneStore(), SpannerRetentionPolicy.none()));
    store.persistEvent(event(1));
    store.persistEvent(event(2));

    SnapshotReadResult<String> result = store.getLatestSnapshotById(AID).orElseThrow();

    assertEquals(2, result.headSeqNr());
    assertFalse(result.snapshot().isPresent());
  }

  @Test
  void theLatestSnapshotAndTheHeadPositionAreReadTogether() {
    EventStore<String, String> store = store(open(noneStore(), SpannerRetentionPolicy.none()));
    store.persistEventAndSnapshot(event(1), snapshot(1));
    store.persistEvent(event(2));
    store.persistEvent(event(3));

    SnapshotReadResult<String> result = store.getLatestSnapshotById(AID).orElseThrow();

    assertEquals(3, result.headSeqNr());
    assertEquals(1, result.snapshot().orElseThrow().seqNr());
    assertEquals("snapshot-1", result.snapshot().orElseThrow().aggregate());
  }

  @Test
  void eventsSinceSeqNrAreReturnedInAscendingOrderAndZeroStartsFromOne() {
    EventStore<String, String> store = store(open(noneStore(), SpannerRetentionPolicy.none()));
    for (int i = 1; i <= 4; i++) store.persistEvent(event(i));

    List<EventEnvelope<String>> since3 = store.getEventsByIdSinceSeqNr(AID, 3);
    List<EventEnvelope<String>> all = store.getEventsByIdSinceSeqNr(AID, 0);

    assertEquals(2, since3.size());
    assertEquals(3, since3.get(0).seqNr());
    assertEquals(4, since3.get(1).seqNr());
    assertEquals(4, all.size());
    assertEquals(1, all.get(0).seqNr());
    assertEquals("event-1", all.get(0).payload());
  }

  @Test
  void aStartAfterTheHeadReturnsNoEvents() {
    EventStore<String, String> store = store(open(noneStore(), SpannerRetentionPolicy.none()));
    store.persistEvent(event(1));

    assertTrue(store.getEventsByIdSinceSeqNr(AID, 2).isEmpty());
  }

  @Test
  void aMissingJournalRowInsideTheRangeIsAStorageError() {
    String prefix = noneStore();
    EventStore<String, String> store = store(open(prefix, SpannerRetentionPolicy.none()));
    for (int i = 1; i <= 3; i++) store.persistEvent(event(i));
    EMULATOR.execute("DELETE FROM " + prefix + "_journal WHERE seq_nr = 2");

    assertThrows(StorageException.class, () -> store.getEventsByIdSinceSeqNr(AID, 1));
  }

  @Test
  void aReopenedStorageSeesTheSameDataAndReadsOneAggregateOnly() {
    String prefix = deleteStore(2);
    AggregateId other = AggregateId.of("Order", "2");
    EventStore<String, String> writer = store(open(prefix, SpannerRetentionPolicy.delete(2)));
    writer.persistEventAndSnapshot(event(1), snapshot(1));
    writer.persistEvent(event(other, 1));

    EventStore<String, String> reopened = store(open(prefix, SpannerRetentionPolicy.delete(2)));

    Optional<SnapshotReadResult<String>> snapshot = reopened.getLatestSnapshotById(AID);
    assertEquals(1, snapshot.orElseThrow().headSeqNr());
    assertEquals(1, reopened.getEventsByIdSinceSeqNr(AID, 0).size());
    assertEquals(1, reopened.getEventsByIdSinceSeqNr(other, 0).size());
  }

  @Test
  void aWriteCommittedWhileTheSnapshotIsBeingReadIsNotMixedIntoThePair() {
    String prefix = noneStore();
    EventStore<String, String> writer = store(open(prefix, SpannerRetentionPolicy.none()));
    writer.persistEventAndSnapshot(event(1), snapshot(1));
    boolean[] fired = {false};
    SpannerStorageHooks hooks =
        new SpannerStorageHooks() {
          @Override
          public void beforeReadSnapshot(AggregateId aggregateId) {
            if (!fired[0]) {
              fired[0] = true;
              writer.persistEventAndSnapshot(event(2), snapshot(2));
            }
          }
        };
    EventStore<String, String> reader = store(open(prefix, SpannerRetentionPolicy.none(), hooks));

    SnapshotReadResult<String> result = reader.getLatestSnapshotById(AID).orElseThrow();

    assertTrue(fired[0], "the injected concurrent write must have run");
    // One strong read-only transaction: head and snapshot come from the same point in time.
    assertEquals(result.headSeqNr(), result.snapshot().orElseThrow().seqNr());
  }
}
