package com.github.j5ik2o.event.store.adapter.java.bigtable;

import static com.github.j5ik2o.event.store.adapter.java.bigtable.BigtableTestFixtures.AID;
import static com.github.j5ik2o.event.store.adapter.java.bigtable.BigtableTestFixtures.EMULATOR;
import static com.github.j5ik2o.event.store.adapter.java.bigtable.BigtableTestFixtures.deleteStore;
import static com.github.j5ik2o.event.store.adapter.java.bigtable.BigtableTestFixtures.event;
import static com.github.j5ik2o.event.store.adapter.java.bigtable.BigtableTestFixtures.journalKey;
import static com.github.j5ik2o.event.store.adapter.java.bigtable.BigtableTestFixtures.open;
import static com.github.j5ik2o.event.store.adapter.java.bigtable.BigtableTestFixtures.snapshot;
import static com.github.j5ik2o.event.store.adapter.java.bigtable.BigtableTestFixtures.store;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.j5ik2o.event.store.adapter.java.core.AggregateId;
import com.github.j5ik2o.event.store.adapter.java.core.EventStore;
import com.github.j5ik2o.event.store.adapter.java.core.OptimisticLockException;
import com.github.j5ik2o.event.store.adapter.java.core.RetentionPolicy;
import com.google.cloud.bigtable.data.v2.models.Row;
import com.google.protobuf.ByteString;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/** BT-11 / BT-12 / BT-13: retention retires history in one conditional update and keeps events. */
class BigtableRetentionTest {
  private static String state(BigtableEmulator.Tables t, String aid, long seqNr) {
    return EMULATOR.text(t.journal, journalKey(aid, seqNr), "history", "state");
  }

  private static boolean hasHistoryEnvelope(BigtableEmulator.Tables t, String aid, long seqNr) {
    return EMULATOR.cellCount(t.journal, journalKey(aid, seqNr), "history", "envelope") > 0;
  }

  /** Every journal row is either active with its history or retired without it, never between. */
  private static void assertHistoryRowsAreConsistent(BigtableEmulator.Tables t) {
    for (Row row : EMULATOR.rows(t.journal)) {
      if (row.getKey().size() <= 1) continue;
      String state = EMULATOR.text(t.journal, row.getKey(), "history", "state");
      boolean envelope = EMULATOR.cellCount(t.journal, row.getKey(), "history", "envelope") > 0;
      if ("active".equals(state)) assertTrue(envelope, "active without history " + row.getKey());
      if ("retired".equals(state)) assertTrue(!envelope, "retired with history " + row.getKey());
      assertEquals(1, EMULATOR.cellCount(t.journal, row.getKey(), "event", "envelope"));
    }
  }

  @Test
  void theNewestNSnapshotsStayAndOlderOnesAreRetiredWhileEventsAreKept() {
    BigtableEmulator.Tables t = deleteStore(2);
    EventStore<String, String> store = store(open(t, RetentionPolicy.delete(2)));
    for (long i = 1; i <= 4; i++) store.persistEventAndSnapshot(event(i), snapshot(i));

    assertEquals("retired", state(t, "Order-1", 1));
    assertEquals("retired", state(t, "Order-1", 2));
    assertEquals("active", state(t, "Order-1", 3));
    assertEquals("active", state(t, "Order-1", 4), "the newest tail is written out too");
    assertTrue(!hasHistoryEnvelope(t, "Order-1", 1));
    assertTrue(!hasHistoryEnvelope(t, "Order-1", 2));
    assertTrue(hasHistoryEnvelope(t, "Order-1", 3));
    assertTrue(hasHistoryEnvelope(t, "Order-1", 4));
    for (long i = 1; i <= 3; i++) {
      assertEquals(1, EMULATOR.cellCount(t.journal, journalKey("Order-1", i), "event", "envelope"));
    }
    assertEquals(4, store.getEventsByIdSinceSeqNr(AID, 1).size());
    assertHistoryRowsAreConsistent(t);
  }

  @Test
  void theBoundaryBetweenNAndNPlusOneSnapshotsRetiresExactlyTheOldest() {
    BigtableEmulator.Tables t = deleteStore(2);
    EventStore<String, String> store = store(open(t, RetentionPolicy.delete(2)));

    store.persistEventAndSnapshot(event(1), snapshot(1));
    store.persistEventAndSnapshot(event(2), snapshot(2));
    assertEquals("active", state(t, "Order-1", 1));
    assertEquals("active", state(t, "Order-1", 2));

    store.persistEventAndSnapshot(event(3), snapshot(3));
    assertEquals("retired", state(t, "Order-1", 1));
    assertEquals("active", state(t, "Order-1", 2));
  }

  @Test
  void aKeepCountOfOneRetiresEverythingButTheHead() {
    BigtableEmulator.Tables t = deleteStore(1);
    EventStore<String, String> store = store(open(t, RetentionPolicy.delete(1)));
    for (long i = 1; i <= 3; i++) store.persistEventAndSnapshot(event(i), snapshot(i));

    assertEquals("retired", state(t, "Order-1", 1));
    assertEquals("retired", state(t, "Order-1", 2));
    assertHistoryRowsAreConsistent(t);
  }

  @Test
  void eventsOnlyAppendsAfterSnapshotsNeverLoseHistory() {
    BigtableEmulator.Tables t = deleteStore(2);
    EventStore<String, String> store = store(open(t, RetentionPolicy.delete(2)));
    store.persistEventAndSnapshot(event(1), snapshot(1));
    store.persistEventAndSnapshot(event(2), snapshot(2));

    store.persistEvent(event(3));
    store.persistEvent(event(4));

    assertEquals("active", state(t, "Order-1", 1));
    assertEquals("active", state(t, "Order-1", 2));
    assertTrue(hasHistoryEnvelope(t, "Order-1", 1) && hasHistoryEnvelope(t, "Order-1", 2));
    assertNull(state(t, "Order-1", 3), "an events-only row has no history state");
    assertEquals(
        2, store.getLatestSnapshotById(AID).orElseThrow().snapshot().orElseThrow().seqNr());
    assertHistoryRowsAreConsistent(t);
  }

  @Test
  void aLaterSnapshotAfterEventsOnlyRowsRetiresOnlyTheOldActiveHistory() {
    BigtableEmulator.Tables t = deleteStore(1);
    EventStore<String, String> store = store(open(t, RetentionPolicy.delete(1)));
    store.persistEventAndSnapshot(event(1), snapshot(1));
    store.persistEvent(event(2));
    store.persistEvent(event(3));

    store.persistEventAndSnapshot(event(4), snapshot(4));

    assertEquals("retired", state(t, "Order-1", 1));
    assertNull(state(t, "Order-1", 2));
    assertNull(state(t, "Order-1", 3));
    assertHistoryRowsAreConsistent(t);
  }

  @Test
  void retentionOfOneAggregateNeverTouchesAnother() {
    BigtableEmulator.Tables t = deleteStore(1);
    AggregateId other = AggregateId.of("Order", "2");
    EventStore<String, String> store = store(open(t, RetentionPolicy.delete(1)));
    store.persistEventAndSnapshot(event(other, 1), snapshot(1));
    store.persistEventAndSnapshot(event(other, 2), snapshot(2));
    for (long i = 1; i <= 3; i++) store.persistEventAndSnapshot(event(i), snapshot(i));

    assertEquals("retired", state(t, "Order-2", 1));
    assertEquals("active", state(t, "Order-2", 2));
    assertEquals("retired", state(t, "Order-1", 1));
    assertEquals("retired", state(t, "Order-1", 2));
    assertEquals("active", state(t, "Order-1", 3));
  }

  @Test
  void aRetiredRowStaysRetiredWithOneStateCellAtTimestampZeroAfterLaterRetention() {
    BigtableEmulator.Tables t = deleteStore(1);
    EventStore<String, String> store = store(open(t, RetentionPolicy.delete(1)));
    for (long i = 1; i <= 5; i++) store.persistEventAndSnapshot(event(i), snapshot(i));

    for (long i = 1; i <= 4; i++) {
      assertEquals("retired", state(t, "Order-1", i));
      assertEquals(1, EMULATOR.cellCount(t.journal, journalKey("Order-1", i), "history", "state"));
      Row row = EMULATOR.row(t.journal, journalKey("Order-1", i));
      assertEquals(
          0L, row.getCells("history", ByteString.copyFromUtf8("state")).get(0).getTimestamp());
    }
    assertHistoryRowsAreConsistent(t);
  }

  @Test
  void aDelayedWriterCannotReviveHistoryThatRetentionAlreadyRetired() {
    BigtableEmulator.Tables t = deleteStore(1);
    EventStore<String, String> fast = store(open(t, RetentionPolicy.delete(1)));
    fast.persistEventAndSnapshot(event(1), snapshot(1));
    boolean[] ran = {false};
    BigtableStorageHooks delayed =
        new BigtableStorageHooks() {
          @Override
          public void journalWritePrevious(AggregateId aggregateId, long seqNr) {
            if (ran[0]) return;
            ran[0] = true;
            // While this writer is stalled before writing row 1, other writers move on and
            // retention retires row 1.
            fast.persistEventAndSnapshot(event(2), snapshot(2));
            fast.persistEventAndSnapshot(event(3), snapshot(3));
          }
        };
    EventStore<String, String> slow = store(open(t, RetentionPolicy.delete(1), delayed));

    assertThrows(
        OptimisticLockException.class, () -> slow.persistEventAndSnapshot(event(2), snapshot(2)));

    assertTrue(ran[0], "the delayed write-out path was really taken");
    assertEquals("retired", state(t, "Order-1", 1));
    assertTrue(!hasHistoryEnvelope(t, "Order-1", 1), "history was not recreated");
    assertHistoryRowsAreConsistent(t);
  }

  @Test
  void aRetentionFailureLeavesTheCommittedAppendAndTheHistoryIntact() {
    BigtableEmulator.Tables t = deleteStore(1);
    List<List<Long>> calls = new ArrayList<>();
    BigtableStorageHooks hooks =
        new BigtableStorageHooks() {
          @Override
          public void beforeRetentionDelete(AggregateId aggregateId, List<Long> seqNrs) {
            calls.add(new ArrayList<>(seqNrs));
            throw new IllegalStateException("injected retention failure");
          }
        };
    EventStore<String, String> store = store(open(t, RetentionPolicy.delete(1), hooks));
    store.persistEventAndSnapshot(event(1), snapshot(1));

    store.persistEventAndSnapshot(event(2), snapshot(2));

    assertEquals(List.of(List.of(1L)), calls, "the retention path really ran once with row 1");
    assertEquals("active", state(t, "Order-1", 1));
    assertTrue(hasHistoryEnvelope(t, "Order-1", 1));
    assertEquals(
        List.of(1L, 2L),
        store.getEventsByIdSinceSeqNr(AID, 1).stream()
            .map(e -> e.seqNr())
            .collect(Collectors.toList()));
  }

  @Test
  void theNextSnapshotWriteRepeatsTheRetentionThatFailedBefore() {
    BigtableEmulator.Tables t = deleteStore(1);
    boolean[] fail = {true};
    BigtableStorageHooks hooks =
        new BigtableStorageHooks() {
          @Override
          public void beforeRetentionDelete(AggregateId aggregateId, List<Long> seqNrs) {
            if (fail[0]) throw new IllegalStateException("injected retention failure");
          }
        };
    EventStore<String, String> store = store(open(t, RetentionPolicy.delete(1), hooks));
    store.persistEventAndSnapshot(event(1), snapshot(1));
    store.persistEventAndSnapshot(event(2), snapshot(2));
    assertEquals("active", state(t, "Order-1", 1));
    fail[0] = false;

    store.persistEventAndSnapshot(event(3), snapshot(3));

    assertEquals("retired", state(t, "Order-1", 1));
    assertEquals("retired", state(t, "Order-1", 2));
  }

  @Test
  void aCandidateThatAnotherWriterAlreadyRetiredIsLeftAlone() {
    BigtableEmulator.Tables t = deleteStore(1);
    EventStore<String, String> store =
        store(
            open(
                t,
                RetentionPolicy.delete(1),
                new BigtableStorageHooks() {
                  @Override
                  public void beforeRetentionDelete(AggregateId aggregateId, List<Long> seqNrs) {
                    // Another maintenance run retires row 1 between the scan and the update.
                    EMULATOR
                        .data()
                        .checkAndMutateRow(
                            com.google.cloud.bigtable.data.v2.models.ConditionalRowMutation.create(
                                    t.journal, journalKey("Order-1", 1))
                                .condition(
                                    com.google.cloud.bigtable.data.v2.models.Filters.FILTERS.pass())
                                .then(
                                    com.google.cloud.bigtable.data.v2.models.Mutation.create()
                                        .deleteCells("history", ByteString.copyFromUtf8("envelope"))
                                        .deleteCells("history", ByteString.copyFromUtf8("state"))
                                        .setCell(
                                            "history",
                                            ByteString.copyFromUtf8("state"),
                                            0L,
                                            ByteString.copyFromUtf8("retired"))));
                  }
                }));
    store.persistEventAndSnapshot(event(1), snapshot(1));

    store.persistEventAndSnapshot(event(2), snapshot(2));

    assertEquals("retired", state(t, "Order-1", 1));
    assertEquals(1, EMULATOR.cellCount(t.journal, journalKey("Order-1", 1), "history", "state"));
    assertHistoryRowsAreConsistent(t);
  }
}
