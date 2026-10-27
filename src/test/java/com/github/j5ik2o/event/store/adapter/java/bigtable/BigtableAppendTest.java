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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.j5ik2o.event.store.adapter.java.core.AggregateId;
import com.github.j5ik2o.event.store.adapter.java.core.ContractViolationException;
import com.github.j5ik2o.event.store.adapter.java.core.EventEnvelope;
import com.github.j5ik2o.event.store.adapter.java.core.EventStore;
import com.github.j5ik2o.event.store.adapter.java.core.EventStoreConfig;
import com.github.j5ik2o.event.store.adapter.java.core.OptimisticLockException;
import com.github.j5ik2o.event.store.adapter.java.core.RetentionPolicy;
import com.github.j5ik2o.event.store.adapter.java.core.SnapshotEnvelope;
import com.github.j5ik2o.event.store.adapter.java.core.StorageException;
import com.google.cloud.bigtable.data.v2.models.Row;
import com.google.cloud.bigtable.data.v2.models.RowCell;
import com.google.protobuf.ByteString;
import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/** BT-5 / BT-6 / BT-7 / BT-16: the commit point is the conditional update of the head row. */
class BigtableAppendTest {
  private static final String HEAD = "head";
  private static final String H1 = headSeqNr(1);
  private static final String H2 = headSeqNr(2);

  @Test
  void everyAppendCommitsTheHeadAndThenWritesItsOwnJournalRowAsTheTail() {
    BigtableEmulator.Tables t = noneStore();
    EventStore<String, String> store = store(open(t, RetentionPolicy.none()));

    store.persistEvent(event(1));

    assertEquals(H1, EMULATOR.text(t.head, prefix("Order-1"), HEAD, "seq_nr"));
    assertEquals("0", EMULATOR.text(t.head, prefix("Order-1"), HEAD, "has_snapshot"));
    assertNotNull(EMULATOR.row(t.journal, journalKey("Order-1", 1)), "the tail is written out");

    store.persistEvent(event(2));

    assertNotNull(EMULATOR.row(t.journal, journalKey("Order-1", 1)));
    assertNotNull(EMULATOR.row(t.journal, journalKey("Order-1", 2)));
    assertEquals(H2, EMULATOR.text(t.head, prefix("Order-1"), HEAD, "seq_nr"));
    assertEquals("0", EMULATOR.text(t.journal, journalKey("Order-1", 1), "event", "has_snapshot"));
    assertNull(
        EMULATOR.text(t.journal, journalKey("Order-1", 1), "history", "state"),
        "no retention means no history cells");
  }

  @Test
  void theStoredEnvelopesAreTheFixedBinaryFormatAndThePayloadIsOnlyTheSerializerOutput() {
    BigtableEmulator.Tables t = noneStore();
    EventStore<String, String> store = store(open(t, RetentionPolicy.none()));
    store.persistEvent(event(AID, 1, Instant.ofEpochSecond(1, 123_456_789)));

    ByteString stored = EMULATOR.bytes(t.head, prefix("Order-1"), HEAD, "last_event");

    BigtableEnvelope.Event decoded = BigtableEnvelope.decodeEvent(stored.toByteArray());
    assertEquals("Order-1", decoded.aid());
    assertEquals(1, decoded.seqNr());
    assertEquals(1_123_456_789L, decoded.occurredAtNs());
    assertEquals("event", decoded.manifest());
    assertEquals(
        "\"event-1\"", new String(decoded.payload(), java.nio.charset.StandardCharsets.UTF_8));
  }

  @Test
  void anEventWithASnapshotReplacesCurrentAndAnEventsOnlyAppendKeepsItWithFlagZero() {
    BigtableEmulator.Tables t = deleteStore(2);
    EventStore<String, String> store = store(open(t, RetentionPolicy.delete(2)));

    store.persistEventAndSnapshot(event(1), snapshot(1));
    assertEquals("1", EMULATOR.text(t.head, prefix("Order-1"), HEAD, "has_snapshot"));
    BigtableEnvelope.Snapshot s1 =
        BigtableEnvelope.decodeSnapshot(
            EMULATOR.bytes(t.head, prefix("Order-1"), "snapshot", "current").toByteArray());
    assertEquals(1, s1.seqNr());

    store.persistEvent(event(2));

    assertEquals("0", EMULATOR.text(t.head, prefix("Order-1"), HEAD, "has_snapshot"));
    BigtableEnvelope.Snapshot kept =
        BigtableEnvelope.decodeSnapshot(
            EMULATOR.bytes(t.head, prefix("Order-1"), "snapshot", "current").toByteArray());
    assertEquals(1, kept.seqNr(), "the current snapshot stays stored");
    assertEquals("active", EMULATOR.text(t.journal, journalKey("Order-1", 1), "history", "state"));
    assertEquals("1", EMULATOR.text(t.journal, journalKey("Order-1", 1), "event", "has_snapshot"));
  }

  @Test
  void withoutRetentionNoHistoryCellIsEverCreated() {
    BigtableEmulator.Tables t = noneStore();
    EventStore<String, String> store = store(open(t, RetentionPolicy.none()));

    store.persistEventAndSnapshot(event(1), snapshot(1));
    store.persistEventAndSnapshot(event(2), snapshot(2));
    store.persistEventAndSnapshot(event(3), snapshot(3));

    for (Row row : EMULATOR.rows(t.journal)) {
      assertTrue(row.getCells("history").isEmpty(), "history cells in " + row.getKey());
    }
  }

  @Test
  void sequenceAtOrBelowTheHeadIsAnOptimisticLockWithoutTheHeadNumberOrDriverText() {
    BigtableEmulator.Tables t = noneStore();
    EventStore<String, String> store = store(open(t, RetentionPolicy.none()));
    store.persistEvent(event(1));
    store.persistEvent(event(2));

    OptimisticLockException duplicate =
        assertThrows(OptimisticLockException.class, () -> store.persistEvent(event(2)));
    OptimisticLockException stale =
        assertThrows(OptimisticLockException.class, () -> store.persistEvent(event(1)));

    for (OptimisticLockException e : List.of(duplicate, stale)) {
      assertTrue(e.getMessage().contains("Order-1"), e.getMessage());
      assertFalse(e.getMessage().contains(t.head), e.getMessage());
      assertFalse(e.getMessage().contains(H2), e.getMessage());
    }
    assertEquals(H2, EMULATOR.text(t.head, prefix("Order-1"), HEAD, "seq_nr"));
  }

  @Test
  void aSecondFirstEventForAnExistingAggregateIsAnOptimisticLock() {
    BigtableEmulator.Tables t = noneStore();
    EventStore<String, String> store = store(open(t, RetentionPolicy.none()));
    store.persistEvent(event(1));

    assertThrows(OptimisticLockException.class, () -> store.persistEvent(event(1)));
  }

  @Test
  void skippingASequenceNumberIsAContractViolationW8AndWritesNothing() {
    BigtableEmulator.Tables t = noneStore();
    EventStore<String, String> store = store(open(t, RetentionPolicy.none()));

    ContractViolationException first =
        assertThrows(ContractViolationException.class, () -> store.persistEvent(event(3)));
    store.persistEvent(event(1));
    ContractViolationException later =
        assertThrows(ContractViolationException.class, () -> store.persistEvent(event(3)));

    assertEquals("W-8", first.rule());
    assertEquals("W-8", later.rule());
    assertEquals(H1, EMULATOR.text(t.head, prefix("Order-1"), HEAD, "seq_nr"));
    assertEquals(
        1, EMULATOR.rows(t.head).size() - 1, "only the head of Order-1 beside the config row");
  }

  @Test
  void aFailureToWriteOutThePreviousEventIsAStorageErrorAndTheHeadDoesNotAdvance() {
    BigtableEmulator.Tables t = noneStore();
    int[] calls = {0};
    BigtableStorageHooks hooks =
        new BigtableStorageHooks() {
          @Override
          public void journalWriteTail(AggregateId aggregateId, long seqNr) {
            // The tail write-out of the first append fails, so row 1 is still missing.
            throw new StorageException("tail failure");
          }

          @Override
          public void journalWritePrevious(AggregateId aggregateId, long seqNr) {
            calls[0]++;
            throw new StorageException("STORAGE_SENTINEL");
          }
        };
    EventStore<String, String> store = store(open(t, RetentionPolicy.none(), hooks));
    store.persistEvent(event(1));
    assertNull(EMULATOR.row(t.journal, journalKey("Order-1", 1)));

    assertThrows(StorageException.class, () -> store.persistEvent(event(2)));

    assertEquals(1, calls[0], "the write-out path was really taken");
    assertEquals(H1, EMULATOR.text(t.head, prefix("Order-1"), HEAD, "seq_nr"));
    assertNull(EMULATOR.row(t.journal, journalKey("Order-1", 1)));
  }

  @Test
  void aFailureToWriteOutTheNewTailAfterTheCommitDoesNotFailTheAppend() {
    BigtableEmulator.Tables t = noneStore();
    int[] calls = {0};
    BigtableStorageHooks hooks =
        new BigtableStorageHooks() {
          @Override
          public void journalWriteTail(AggregateId aggregateId, long seqNr) {
            calls[0]++;
            throw new StorageException("STORAGE_SENTINEL");
          }
        };
    EventStore<String, String> store = store(open(t, RetentionPolicy.none(), hooks));

    store.persistEvent(event(1));
    store.persistEvent(event(2));

    assertEquals(2, calls[0]);
    assertEquals(H2, EMULATOR.text(t.head, prefix("Order-1"), HEAD, "seq_nr"));
    assertEquals(2, store.getEventsByIdSinceSeqNr(AID, 1).size());
  }

  @Test
  void aFailureJustBeforeTheConditionalUpdateLeavesTheHeadUnchangedAndTheAppendCanBeRepeated() {
    BigtableEmulator.Tables t = noneStore();
    boolean[] fail = {true};
    BigtableStorageHooks hooks =
        new BigtableStorageHooks() {
          @Override
          public void beforeCommit(AggregateId aggregateId, long seqNr) {
            if (fail[0]) throw new StorageException("injected commit failure");
          }
        };
    EventStore<String, String> store = store(open(t, RetentionPolicy.none(), hooks));

    assertThrows(StorageException.class, () -> store.persistEvent(event(1)));
    assertNull(EMULATOR.row(t.head, prefix("Order-1")));
    fail[0] = false;
    store.persistEvent(event(1));

    assertEquals(H1, EMULATOR.text(t.head, prefix("Order-1"), HEAD, "seq_nr"));
  }

  @Test
  void aLostConditionalUpdateResponseIsAStorageErrorOfUnknownOutcomeAndAResendIsAnOptimisticLock() {
    BigtableEmulator.Tables t = noneStore();
    boolean[] lose = {true};
    BigtableStorageHooks hooks =
        new BigtableStorageHooks() {
          @Override
          public void afterCommit(AggregateId aggregateId, long seqNr) {
            if (lose[0]) {
              lose[0] = false;
              throw new StorageException("response lost");
            }
          }
        };
    EventStore<String, String> store = store(open(t, RetentionPolicy.none(), hooks));

    assertThrows(StorageException.class, () -> store.persistEvent(event(1)));
    OptimisticLockException resend =
        assertThrows(OptimisticLockException.class, () -> store.persistEvent(event(1)));

    assertTrue(resend.getMessage().contains("Order-1"));
    assertEquals(1, store.getEventsByIdSinceSeqNr(AID, 1).size());
  }

  @Test
  void afterEveryAppendEachReplacedColumnHoldsExactlyOneCellAtTimestampZero() {
    BigtableEmulator.Tables t = deleteStore(2);
    EventStore<String, String> store = store(open(t, RetentionPolicy.delete(2)));
    for (long seq = 1; seq <= 4; seq++) {
      store.persistEventAndSnapshot(event(seq), snapshot(seq));
    }

    Row head = EMULATOR.row(t.head, prefix("Order-1"));
    for (String[] column :
        new String[][] {
          {"head", "seq_nr"},
          {"head", "last_event"},
          {"head", "has_snapshot"},
          {"snapshot", "current"}
        }) {
      List<RowCell> cells = head.getCells(column[0], ByteString.copyFromUtf8(column[1]));
      assertEquals(1, cells.size(), column[0] + ":" + column[1]);
      assertEquals(0L, cells.get(0).getTimestamp(), column[0] + ":" + column[1]);
    }
    for (long seq = 1; seq <= 3; seq++) {
      Row row = EMULATOR.row(t.journal, journalKey("Order-1", seq));
      if (row == null) continue;
      for (RowCell cell : row.getCells()) {
        assertEquals(
            0L, cell.getTimestamp(), "journal " + seq + " " + cell.getQualifier().toStringUtf8());
      }
      assertEquals(1, row.getCells("event", ByteString.copyFromUtf8("envelope")).size());
    }
  }

  @Test
  void aConditionThatMatchesOnlyAnOlderCellVersionDoesNotCommit() {
    BigtableEmulator.Tables t = noneStore();
    ByteString head = prefix("Order-1");
    boolean[] armed = {true};
    BigtableStorageHooks hooks =
        new BigtableStorageHooks() {
          @Override
          public void beforeCommit(AggregateId aggregateId, long seqNr) {
            if (armed[0] && seqNr == 2) {
              armed[0] = false;
              // A newer cell (timestamp 1000 microseconds) now shadows the version the writer read
              // (timestamp 0).
              EMULATOR
                  .data()
                  .mutateRow(
                      com.google.cloud.bigtable.data.v2.models.RowMutation.create(t.head, head)
                          .setCell(
                              HEAD,
                              ByteString.copyFromUtf8("seq_nr"),
                              1000L,
                              ByteString.copyFromUtf8(headSeqNr(5))));
            }
          }
        };
    EventStore<String, String> store = store(open(t, RetentionPolicy.none(), hooks));
    store.persistEvent(event(1));

    assertThrows(OptimisticLockException.class, () -> store.persistEvent(event(2)));

    assertEquals(headSeqNr(5), EMULATOR.text(t.head, head, HEAD, "seq_nr"));
    assertEquals(
        BigtableEnvelope.decodeEvent(EMULATOR.bytes(t.head, head, HEAD, "last_event").toByteArray())
            .seqNr(),
        1,
        "the envelope was not replaced");
  }

  // ----- BT-16: the envelope limit -----

  private static BigtableStorage limited(BigtableEmulator.Tables t, int limit) {
    return BigtableStorage.open(
        EMULATOR.data(),
        EMULATOR.admin(),
        BigtableStorageConfig.builder()
            .journalTableId(t.journal)
            .headTableId(t.head)
            .envelopeLimitBytes(limit)
            .build());
  }

  /**
   * An event whose serialized envelope is exactly {@code size} bytes (aid Order-1, manifest
   * "event").
   */
  private static EventEnvelope<String> eventOfEnvelopeSize(long seqNr, int size) {
    int overhead = 27 + "Order-1".length() + "event".length() + 2; // + two JSON quotes
    return EventEnvelope.<String>builder()
        .aggregateId(AID)
        .seqNr(seqNr)
        .occurredAt(Instant.EPOCH)
        .manifest("event")
        .payload("x".repeat(size - overhead))
        .build();
  }

  @Test
  void anEnvelopeExactlyAtTheLimitIsAccepted() {
    BigtableEmulator.Tables t = EMULATOR.createStore(null, 1024);
    EventStore<String, String> store = store(limited(t, 1024));

    store.persistEvent(eventOfEnvelopeSize(1, 1024));

    assertEquals(H1, EMULATOR.text(t.head, prefix("Order-1"), HEAD, "seq_nr"));
    assertEquals(1024, EMULATOR.bytes(t.head, prefix("Order-1"), HEAD, "last_event").size());
  }

  @Test
  void anEnvelopeOneByteOverTheLimitIsRejectedBeforeAnyWriteWithRuleBt16AndTheSequenceNumber() {
    BigtableEmulator.Tables t = EMULATOR.createStore(null, 1024);
    EventStore<String, String> store = store(limited(t, 1024));
    store.persistEvent(event(1));
    String headBefore = EMULATOR.text(t.head, prefix("Order-1"), HEAD, "seq_nr");
    int journalRowsBefore = EMULATOR.rows(t.journal).size();

    ContractViolationException e =
        assertThrows(
            ContractViolationException.class,
            () -> store.persistEvent(eventOfEnvelopeSize(2, 1025)));

    assertEquals("BT-16", e.rule());
    assertEquals(2L, e.seqNr().getAsLong());
    assertTrue(e.getMessage().contains("BT-16") && e.getMessage().contains("2"), e.getMessage());
    assertEquals(headBefore, EMULATOR.text(t.head, prefix("Order-1"), HEAD, "seq_nr"));
    assertEquals(journalRowsBefore, EMULATOR.rows(t.journal).size());
  }

  @Test
  void aSnapshotEnvelopeOverTheLimitIsRejectedEvenWhenTheEventFits() {
    BigtableEmulator.Tables t = EMULATOR.createStore(null, 1024);
    EventStore<String, String> store = store(limited(t, 1024));
    SnapshotEnvelope<String> big =
        SnapshotEnvelope.<String>builder()
            .seqNr(1)
            .manifest("snapshot")
            .aggregate("y".repeat(1024))
            .build();

    ContractViolationException e =
        assertThrows(
            ContractViolationException.class, () -> store.persistEventAndSnapshot(event(1), big));

    assertEquals("BT-16", e.rule());
    assertNull(EMULATOR.row(t.head, prefix("Order-1")));
  }

  @Test
  void theLimitIsCheckedWithoutReadingTheHead() {
    BigtableEmulator.Tables t = EMULATOR.createStore(null, 1024);
    boolean[] headRead = {false};
    BigtableStorageHooks hooks =
        new BigtableStorageHooks() {
          @Override
          public void beforeCommit(AggregateId aggregateId, long seqNr) {
            headRead[0] = true;
          }
        };
    EventStoreConfig<String, String> cfg = BigtableTestFixtures.config();
    EventStore<String, String> store =
        BigtableEventStore.create(
            BigtableStorage.open(
                EMULATOR.data(),
                EMULATOR.admin(),
                BigtableStorageConfig.builder()
                    .journalTableId(t.journal)
                    .headTableId(t.head)
                    .envelopeLimitBytes(1024)
                    .build(),
                hooks),
            cfg);

    assertThrows(
        ContractViolationException.class, () -> store.persistEvent(eventOfEnvelopeSize(1, 1025)));

    assertFalse(headRead[0], "nothing reached the commit step");
    assertEquals(0, EMULATOR.rows(t.head).size() - 1);
    assertEquals(
        List.of(),
        EMULATOR.rows(t.journal).stream()
            .filter(r -> r.getKey().size() > 1)
            .map(r -> r.getKey())
            .collect(Collectors.toList()));
  }
}
