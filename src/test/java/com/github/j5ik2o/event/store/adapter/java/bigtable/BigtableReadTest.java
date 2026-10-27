package com.github.j5ik2o.event.store.adapter.java.bigtable;

import static com.github.j5ik2o.event.store.adapter.java.bigtable.BigtableTestFixtures.AID;
import static com.github.j5ik2o.event.store.adapter.java.bigtable.BigtableTestFixtures.EMULATOR;
import static com.github.j5ik2o.event.store.adapter.java.bigtable.BigtableTestFixtures.deleteStore;
import static com.github.j5ik2o.event.store.adapter.java.bigtable.BigtableTestFixtures.event;
import static com.github.j5ik2o.event.store.adapter.java.bigtable.BigtableTestFixtures.headSeqNr;
import static com.github.j5ik2o.event.store.adapter.java.bigtable.BigtableTestFixtures.journalKey;
import static com.github.j5ik2o.event.store.adapter.java.bigtable.BigtableTestFixtures.noneStore;
import static com.github.j5ik2o.event.store.adapter.java.bigtable.BigtableTestFixtures.open;
import static com.github.j5ik2o.event.store.adapter.java.bigtable.BigtableTestFixtures.prefix;
import static com.github.j5ik2o.event.store.adapter.java.bigtable.BigtableTestFixtures.snapshot;
import static com.github.j5ik2o.event.store.adapter.java.bigtable.BigtableTestFixtures.store;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.j5ik2o.event.store.adapter.java.core.AggregateId;
import com.github.j5ik2o.event.store.adapter.java.core.EventEnvelope;
import com.github.j5ik2o.event.store.adapter.java.core.EventStore;
import com.github.j5ik2o.event.store.adapter.java.core.RetentionPolicy;
import com.github.j5ik2o.event.store.adapter.java.core.SnapshotReadResult;
import com.github.j5ik2o.event.store.adapter.java.core.StorageException;
import com.google.protobuf.ByteString;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/** BT-8 / BT-9 / BT-10: the head is read first and the journal range is [start, h). */
class BigtableReadTest {
  private static List<Long> seqNrs(List<EventEnvelope<String>> events) {
    return events.stream().map(EventEnvelope::seqNr).collect(Collectors.toList());
  }

  private static EventStore<String, String> withEvents(BigtableEmulator.Tables t, int count) {
    EventStore<String, String> store = store(open(t, RetentionPolicy.none()));
    for (long seq = 1; seq <= count; seq++) store.persistEvent(event(seq));
    return store;
  }

  @Test
  void anUnknownAggregateHasNoEventsAndNoSnapshotAndTouchesNothing() {
    BigtableEmulator.Tables t = noneStore();
    EventStore<String, String> store = store(open(t, RetentionPolicy.none()));

    assertEquals(List.of(), store.getEventsByIdSinceSeqNr(AID, 1));
    assertEquals(Optional.empty(), store.getLatestSnapshotById(AID));
    assertEquals(1, EMULATOR.rows(t.head).size(), "only the configuration row");
  }

  @Test
  void eventsFromStartIncludeTheJournalRowsAndTheTailFromTheHead() {
    BigtableEmulator.Tables t = noneStore();
    EventStore<String, String> store = withEvents(t, 4);

    assertEquals(List.of(1L, 2L, 3L, 4L), seqNrs(store.getEventsByIdSinceSeqNr(AID, 1)));
    assertEquals(List.of(3L, 4L), seqNrs(store.getEventsByIdSinceSeqNr(AID, 3)));
    assertEquals(
        List.of(1L, 2L, 3L, 4L), seqNrs(store.getEventsByIdSinceSeqNr(AID, 0)), "0 reads as 1");
    assertEquals("event-3", store.getEventsByIdSinceSeqNr(AID, 3).get(0).payload());
  }

  @Test
  void startAtTheHeadReturnsOnlyTheHeadEventWithoutReadingTheJournal() {
    BigtableEmulator.Tables t = noneStore();
    EventStore<String, String> store = withEvents(t, 3);
    EMULATOR.deleteRow(t.journal, journalKey("Order-1", 1));
    EMULATOR.deleteRow(t.journal, journalKey("Order-1", 2));

    assertEquals(List.of(3L), seqNrs(store.getEventsByIdSinceSeqNr(AID, 3)));
  }

  @Test
  void startBeyondTheHeadReturnsNothingWithoutReadingTheJournal() {
    BigtableEmulator.Tables t = noneStore();
    EventStore<String, String> store = withEvents(t, 3);
    EMULATOR.deleteRow(t.journal, journalKey("Order-1", 1));

    assertEquals(List.of(), store.getEventsByIdSinceSeqNr(AID, 4));
    assertEquals(List.of(), store.getEventsByIdSinceSeqNr(AID, 9_007_199_254_740_991L));
  }

  @Test
  void aMissingJournalRowInsideTheRangeIsAStorageError() {
    BigtableEmulator.Tables t = noneStore();
    EventStore<String, String> store = withEvents(t, 4);
    EMULATOR.deleteRow(t.journal, journalKey("Order-1", 2));

    assertThrows(StorageException.class, () -> store.getEventsByIdSinceSeqNr(AID, 1));
    assertEquals(List.of(3L, 4L), seqNrs(store.getEventsByIdSinceSeqNr(AID, 3)), "outside the gap");
  }

  @Test
  void aGapAtTheStartOfTheRangeOrAtItsEndIsAlsoAStorageError() {
    BigtableEmulator.Tables t = noneStore();
    EventStore<String, String> store = withEvents(t, 4);
    EMULATOR.deleteRow(t.journal, journalKey("Order-1", 1));
    EMULATOR.deleteRow(t.journal, journalKey("Order-1", 3));

    assertThrows(StorageException.class, () -> store.getEventsByIdSinceSeqNr(AID, 1));
    assertThrows(StorageException.class, () -> store.getEventsByIdSinceSeqNr(AID, 2));
  }

  @Test
  void journalRowsAtOrAboveTheFetchedHeadAreNeverMixedIntoTheResult() {
    BigtableEmulator.Tables t = noneStore();
    EventStore<String, String> plain = withEvents(t, 3);
    // A row above the head (a copy of the head event under the key of 4) must never be read.
    EMULATOR.put(
        t.journal,
        journalKey("Order-1", 4),
        "event",
        "envelope",
        EMULATOR.bytes(t.head, prefix("Order-1"), "head", "last_event"));

    assertEquals(List.of(1L, 2L, 3L), seqNrs(plain.getEventsByIdSinceSeqNr(AID, 1)));
  }

  @Test
  void aHeadThatAdvancesDuringTheReadDoesNotChangeTheResult() {
    BigtableEmulator.Tables t = noneStore();
    EventStore<String, String> writer = withEvents(t, 3);
    BigtableStorageHooks advance =
        new BigtableStorageHooks() {
          boolean done;

          @Override
          public void beforeReadEvents(AggregateId aggregateId) {
            if (done) return;
            done = true;
            writer.persistEvent(event(4));
          }
        };
    EventStore<String, String> reader = store(open(t, RetentionPolicy.none(), advance));

    assertEquals(List.of(1L, 2L, 3L), seqNrs(reader.getEventsByIdSinceSeqNr(AID, 1)));
    assertEquals(List.of(1L, 2L, 3L, 4L), seqNrs(reader.getEventsByIdSinceSeqNr(AID, 1)));
  }

  @Test
  void aSnapshotAndTheHeadNumberComeFromTheSameHeadRow() {
    BigtableEmulator.Tables t = deleteStore(2);
    EventStore<String, String> store = store(open(t, RetentionPolicy.delete(2)));
    store.persistEventAndSnapshot(event(1), snapshot(1));
    store.persistEvent(event(2));
    store.persistEvent(event(3));

    SnapshotReadResult<String> result = store.getLatestSnapshotById(AID).orElseThrow();

    assertEquals(3, result.headSeqNr());
    assertEquals(1, result.snapshot().orElseThrow().seqNr());
    assertEquals("snapshot-1", result.snapshot().orElseThrow().aggregate());
  }

  @Test
  void anAggregateWithoutASnapshotReturnsTheHeadNumberAndNoSnapshot() {
    BigtableEmulator.Tables t = noneStore();
    EventStore<String, String> store = withEvents(t, 2);

    SnapshotReadResult<String> result = store.getLatestSnapshotById(AID).orElseThrow();

    assertEquals(2, result.headSeqNr());
    assertFalse(result.snapshot().isPresent());
  }

  @Test
  void aSnapshotReadNeverTouchesTheJournal() {
    BigtableEmulator.Tables t = deleteStore(1);
    EventStore<String, String> store = store(open(t, RetentionPolicy.delete(1)));
    store.persistEventAndSnapshot(event(1), snapshot(1));
    store.persistEventAndSnapshot(event(2), snapshot(2));
    EMULATOR.rows(t.journal).stream()
        .filter(r -> r.getKey().size() > 1)
        .forEach(r -> EMULATOR.deleteRow(t.journal, r.getKey()));

    assertEquals(2, store.getLatestSnapshotById(AID).orElseThrow().headSeqNr());
  }

  @Test
  void reopeningTheStorageShowsTheSameData() {
    BigtableEmulator.Tables t = noneStore();
    withEvents(t, 3);

    EventStore<String, String> reopened = store(open(t, RetentionPolicy.none()));

    assertEquals(List.of(1L, 2L, 3L), seqNrs(reopened.getEventsByIdSinceSeqNr(AID, 1)));
  }

  @Test
  void aSecondStoreSeesAppendsMadeByTheFirstAndAppendsContinueFromThere() {
    BigtableEmulator.Tables t = noneStore();
    EventStore<String, String> first = store(open(t, RetentionPolicy.none()));
    EventStore<String, String> second = store(open(t, RetentionPolicy.none()));
    first.persistEvent(event(1));
    first.persistEvent(event(2));

    second.persistEvent(event(3));

    assertEquals(List.of(1L, 2L, 3L), seqNrs(first.getEventsByIdSinceSeqNr(AID, 1)));
  }

  @Test
  void manyLargeRowsAreReassembledInOrderWithoutDuplicatesOrLoss() {
    BigtableEmulator.Tables t = noneStore();
    EventStore<String, String> store = store(open(t, RetentionPolicy.none()));
    String big = "z".repeat(300_000);
    for (long seq = 1; seq <= 12; seq++) {
      store.persistEvent(
          EventEnvelope.<String>builder()
              .aggregateId(AID)
              .seqNr(seq)
              .occurredAt(Instant.EPOCH)
              .manifest("event")
              .payload(big + seq)
              .build());
    }

    List<EventEnvelope<String>> events = store.getEventsByIdSinceSeqNr(AID, 1);

    assertEquals(
        java.util.stream.LongStream.rangeClosed(1, 12).boxed().collect(Collectors.toList()),
        seqNrs(events));
    for (EventEnvelope<String> e : events) {
      assertTrue(e.payload().endsWith(Long.toString(e.seqNr())));
      assertEquals(300_000 + Long.toString(e.seqNr()).length(), e.payload().length());
    }
  }

  // ----- head verification (BT-7 step 1) -----

  private static void putHead(
      BigtableEmulator.Tables t, String qualifier, String family, ByteString v) {
    EMULATOR.put(t.head, prefix("Order-1"), family, qualifier, v);
  }

  private static void assertHeadRejected(BigtableEmulator.Tables t) {
    assertHeadRejected(t, RetentionPolicy.none());
  }

  private static void assertHeadRejected(BigtableEmulator.Tables t, RetentionPolicy policy) {
    EventStore<String, String> store = store(open(t, policy));
    assertThrows(StorageException.class, () -> store.getEventsByIdSinceSeqNr(AID, 1));
    assertThrows(StorageException.class, () -> store.getLatestSnapshotById(AID));
    assertThrows(StorageException.class, () -> store.persistEvent(event(2)));
  }

  @Test
  void aHeadRowMissingARequiredCellIsAStorageErrorAndNotANewAggregate() {
    BigtableEmulator.Tables t = noneStore();
    EMULATOR.put(
        t.head, prefix("Order-1"), "head", "seq_nr", ByteString.copyFromUtf8(headSeqNr(1)));

    assertHeadRejected(t);
  }

  @Test
  void aHeadSeqNrThatIsNotTwentyAsciiDigitsOrIsOutOfRangeIsAStorageError() {
    for (String bad :
        List.of(
            "1",
            "0000000000000000000a",
            "00000000000000000000",
            "9007199254740992".concat("0000"))) {
      BigtableEmulator.Tables t = noneStore();
      withEvents(t, 1);
      putHead(t, "seq_nr", "head", ByteString.copyFromUtf8(bad));

      assertHeadRejected(t);
    }
  }

  @Test
  void aHeadClaimingASnapshotWithoutOneOrWithTheWrongNumberIsAStorageError() {
    BigtableEmulator.Tables t = deleteStore(2);
    EventStore<String, String> store = store(open(t, RetentionPolicy.delete(2)));
    store.persistEvent(event(1));
    putHead(t, "has_snapshot", "head", ByteString.copyFromUtf8("1"));
    assertHeadRejected(t, RetentionPolicy.delete(2));

    BigtableEmulator.Tables other = deleteStore(2);
    EventStore<String, String> otherStore = store(open(other, RetentionPolicy.delete(2)));
    otherStore.persistEventAndSnapshot(event(1), snapshot(1));
    otherStore.persistEvent(event(2));
    // has_snapshot=1 requires current.seq_nr == h, but current still holds snapshot 1 while h is 2
    putHead(other, "has_snapshot", "head", ByteString.copyFromUtf8("1"));
    assertThrows(StorageException.class, () -> otherStore.getLatestSnapshotById(AID));
  }
}
