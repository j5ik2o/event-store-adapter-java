package com.github.j5ik2o.event.store.adapter.java.spanner;

import static com.github.j5ik2o.event.store.adapter.java.spanner.SpannerTestFixtures.AID;
import static com.github.j5ik2o.event.store.adapter.java.spanner.SpannerTestFixtures.EMULATOR;
import static com.github.j5ik2o.event.store.adapter.java.spanner.SpannerTestFixtures.deleteStore;
import static com.github.j5ik2o.event.store.adapter.java.spanner.SpannerTestFixtures.event;
import static com.github.j5ik2o.event.store.adapter.java.spanner.SpannerTestFixtures.hashPrefixLiteral;
import static com.github.j5ik2o.event.store.adapter.java.spanner.SpannerTestFixtures.noneStore;
import static com.github.j5ik2o.event.store.adapter.java.spanner.SpannerTestFixtures.open;
import static com.github.j5ik2o.event.store.adapter.java.spanner.SpannerTestFixtures.snapshot;
import static com.github.j5ik2o.event.store.adapter.java.spanner.SpannerTestFixtures.store;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.j5ik2o.event.store.adapter.java.core.AggregateId;
import com.github.j5ik2o.event.store.adapter.java.core.ContractViolationException;
import com.github.j5ik2o.event.store.adapter.java.core.EventEnvelope;
import com.github.j5ik2o.event.store.adapter.java.core.EventStore;
import com.github.j5ik2o.event.store.adapter.java.core.OptimisticLockException;
import com.github.j5ik2o.event.store.adapter.java.core.StorageException;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class SpannerAppendTest {
  private static SpannerStorageHooks failBeforeCommit() {
    return new SpannerStorageHooks() {
      @Override
      public void beforeCommit(AggregateId aggregateId) {
        throw new StorageException("injected commit failure");
      }
    };
  }

  @Test
  void firstEventCreatesJournalAndHeadAndLaterEventsAdvanceTheHead() {
    String prefix = noneStore();
    SpannerStorage storage = open(prefix, SpannerRetentionPolicy.none());
    EventStore<String, String> store = store(storage);

    store.persistEvent(event(1));
    store.persistEvent(event(2));

    assertEquals(
        List.of(1L, 2L),
        EMULATOR.longs("SELECT seq_nr FROM " + prefix + "_journal ORDER BY seq_nr"));
    assertEquals(List.of(2L), EMULATOR.longs("SELECT seq_nr FROM " + prefix + "_head"));
    assertEquals(0, EMULATOR.count(prefix, "snapshot_current"));
  }

  @Test
  void eventAndSnapshotWriteTheCurrentSnapshotAndNoHistoryWithoutRetention() {
    String prefix = noneStore();
    SpannerStorage storage = open(prefix, SpannerRetentionPolicy.none());

    store(storage).persistEventAndSnapshot(event(1), snapshot(1));
    store(storage).persistEventAndSnapshot(event(2), snapshot(2));

    assertEquals(List.of(2L), EMULATOR.longs("SELECT seq_nr FROM " + prefix + "_snapshot_current"));
    assertEquals(0, EMULATOR.count(prefix, "snapshot_history"));
  }

  @Test
  void withRetentionTheHistoryRowIsInsertedWithANullExpiry() {
    String prefix = deleteStore(5);
    SpannerStorage storage = open(prefix, SpannerRetentionPolicy.delete(5));

    store(storage).persistEventAndSnapshot(event(1), snapshot(1));

    assertEquals(
        List.of(1L),
        EMULATOR.longs(
            "SELECT seq_nr FROM " + prefix + "_snapshot_history WHERE expired_at IS NULL"));
  }

  @Test
  void sequenceAtOrBelowTheHeadIsAnOptimisticLockWithoutDriverTextInTheMessage() {
    String prefix = noneStore();
    SpannerStorage storage = open(prefix, SpannerRetentionPolicy.none());
    EventStore<String, String> store = store(storage);
    store.persistEvent(event(1));
    store.persistEvent(event(2));

    OptimisticLockException duplicate =
        assertThrows(OptimisticLockException.class, () -> store.persistEvent(event(2)));
    OptimisticLockException stale =
        assertThrows(OptimisticLockException.class, () -> store.persistEvent(event(1)));

    for (OptimisticLockException e : List.of(duplicate, stale)) {
      assertTrue(e.getMessage().contains("Order-1"), e.getMessage());
      assertFalse(e.getMessage().contains("ALREADY_EXISTS"), e.getMessage());
      assertFalse(e.getMessage().contains(prefix), e.getMessage());
    }
    assertEquals(2, EMULATOR.count(prefix, "journal"));
  }

  @Test
  void firstEventForAnExistingAggregateIsAnOptimisticLock() {
    String prefix = noneStore();
    EventStore<String, String> store = store(open(prefix, SpannerRetentionPolicy.none()));
    store.persistEvent(event(1));

    assertThrows(OptimisticLockException.class, () -> store.persistEvent(event(1)));
  }

  @Test
  void skippingASequenceNumberIsAContractViolationW8AndWritesNothing() {
    String prefix = noneStore();
    EventStore<String, String> store = store(open(prefix, SpannerRetentionPolicy.none()));

    ContractViolationException first =
        assertThrows(ContractViolationException.class, () -> store.persistEvent(event(3)));
    store.persistEvent(event(1));
    ContractViolationException later =
        assertThrows(ContractViolationException.class, () -> store.persistEvent(event(3)));

    assertEquals("W-8", first.rule());
    assertEquals("W-8", later.rule());
    assertEquals(List.of(1L), EMULATOR.longs("SELECT seq_nr FROM " + prefix + "_journal"));
  }

  @Test
  void failureBeforeCommitLeavesNoRowInAnyOfTheFourTables() {
    String prefix = deleteStore(2);
    SpannerStorage storage = open(prefix, SpannerRetentionPolicy.delete(2), failBeforeCommit());

    assertThrows(
        StorageException.class,
        () -> store(storage).persistEventAndSnapshot(event(1), snapshot(1)));

    for (String table : List.of("journal", "head", "snapshot_current", "snapshot_history")) {
      assertEquals(0, EMULATOR.count(prefix, table), table);
    }
  }

  @Test
  void aStoreStillWorksAfterAFailedAppend() {
    String prefix = noneStore();
    boolean[] fail = {true};
    SpannerStorageHooks hooks =
        new SpannerStorageHooks() {
          @Override
          public void beforeCommit(AggregateId aggregateId) {
            if (fail[0]) throw new StorageException("injected");
          }
        };
    SpannerStorage storage = open(prefix, SpannerRetentionPolicy.none(), hooks);
    assertThrows(StorageException.class, () -> store(storage).persistEvent(event(1)));
    fail[0] = false;

    store(storage).persistEvent(event(1));

    assertEquals(List.of(1L), EMULATOR.longs("SELECT seq_nr FROM " + prefix + "_journal"));
  }

  @Test
  void primaryKeyDuplicateInTheJournalAfterTheHeadCheckIsAnOptimisticLock() {
    String prefix = noneStore();
    // A journal row without a head row: the head check passes and the journal insert collides.
    EMULATOR.execute(
        "INSERT INTO "
            + prefix
            + "_journal (hash_prefix, aid, seq_nr, occurred_at_ns, manifest, payload) VALUES ("
            + hashPrefixLiteral(AID)
            + ", 'Order-1', 1, 0, 'event', b'x')");
    EventStore<String, String> store = store(open(prefix, SpannerRetentionPolicy.none()));

    assertThrows(OptimisticLockException.class, () -> store.persistEvent(event(1)));
    assertEquals(0, EMULATOR.count(prefix, "head"));
    assertEquals(1, EMULATOR.count(prefix, "journal"));
  }

  @Test
  void primaryKeyDuplicateInTheSnapshotHistoryIsAStorageErrorAndRollsBackTheEvent() {
    String prefix = deleteStore(2);
    EMULATOR.execute(
        "INSERT INTO "
            + prefix
            + "_snapshot_history (hash_prefix, aid, seq_nr, manifest, payload) VALUES ("
            + hashPrefixLiteral(AID)
            + ", 'Order-1', 1, 'snapshot', b'x')");
    EventStore<String, String> store = store(open(prefix, SpannerRetentionPolicy.delete(2)));

    StorageException e =
        assertThrows(
            StorageException.class, () -> store.persistEventAndSnapshot(event(1), snapshot(1)));

    assertFalse(e.getCause() instanceof OptimisticLockException);
    assertEquals(0, EMULATOR.count(prefix, "journal"));
    assertEquals(0, EMULATOR.count(prefix, "head"));
    assertEquals(0, EMULATOR.count(prefix, "snapshot_current"));
  }

  @Test
  void occurredAtIsStoredAsSignedInt64NanosecondsAndRoundTripsAtTheBoundaries() {
    String prefix = noneStore();
    Instant[] instants = {
      Instant.ofEpochSecond(0, -1),
      Instant.ofEpochSecond(0, Long.MIN_VALUE),
      Instant.ofEpochSecond(0, Long.MAX_VALUE),
      Instant.EPOCH,
      Instant.ofEpochSecond(1_700_000_000L, 123_456_789)
    };
    EventStore<String, String> store = store(open(prefix, SpannerRetentionPolicy.none()));
    for (int i = 0; i < instants.length; i++) {
      store.persistEvent(event(AID, i + 1, instants[i]));
    }

    List<EventEnvelope<String>> read = store.getEventsByIdSinceSeqNr(AID, 0);

    for (int i = 0; i < instants.length; i++) {
      assertEquals(instants[i], read.get(i).occurredAt());
    }
    assertEquals(
        String.valueOf(Long.MIN_VALUE),
        EMULATOR.string(
            "SELECT CAST(occurred_at_ns AS STRING) FROM " + prefix + "_journal WHERE seq_nr = 2"));
    assertEquals(
        String.valueOf(Long.MAX_VALUE),
        EMULATOR.string(
            "SELECT CAST(occurred_at_ns AS STRING) FROM " + prefix + "_journal WHERE seq_nr = 3"));
    assertEquals(
        "-1",
        EMULATOR.string(
            "SELECT CAST(occurred_at_ns AS STRING) FROM " + prefix + "_journal WHERE seq_nr = 1"));
  }

  @Test
  void aSequenceNumberOfTwoToThe53MinusOneCanBeAppended() {
    String prefix = noneStore();
    EMULATOR.execute(
        "INSERT INTO "
            + prefix
            + "_head (hash_prefix, aid, type_name, seq_nr, event_occurred_at_ns, event_manifest,"
            + " event_payload) VALUES ("
            + hashPrefixLiteral(AID)
            + ", 'Order-1', 'Order', 9007199254740990, 0, 'event', b'x')");

    store(open(prefix, SpannerRetentionPolicy.none())).persistEvent(event(9007199254740991L));

    assertEquals(
        List.of(9007199254740991L), EMULATOR.longs("SELECT seq_nr FROM " + prefix + "_head"));
  }

  @Test
  void rowsAreKeyedByTheEightByteHashPrefixAndTheAidText() {
    String prefix = noneStore();
    store(open(prefix, SpannerRetentionPolicy.none())).persistEvent(event(1));

    assertEquals(
        List.of(8L), EMULATOR.longs("SELECT BYTE_LENGTH(hash_prefix) FROM " + prefix + "_head"));
    assertEquals("Order-1", EMULATOR.string("SELECT aid FROM " + prefix + "_journal"));
    assertEquals("Order", EMULATOR.string("SELECT type_name FROM " + prefix + "_head"));
    assertEquals(
        List.of(1L),
        EMULATOR.longs(
            "SELECT COUNT(*) FROM "
                + prefix
                + "_head WHERE hash_prefix = "
                + hashPrefixLiteral(AID)
                + " AND aid = 'Order-1'"));
  }

  @Test
  void aidsAreComparedExactlyAndTwoAggregatesNeverShareHistory() {
    String prefix = noneStore();
    AggregateId one = AggregateId.of("Order", "1");
    AggregateId twelve = AggregateId.of("Order", "12");
    EventStore<String, String> store = store(open(prefix, SpannerRetentionPolicy.none()));
    store.persistEvent(event(one, 1));
    store.persistEvent(event(twelve, 1));
    store.persistEvent(event(twelve, 2));

    assertEquals(1, store.getEventsByIdSinceSeqNr(one, 1).size());
    assertEquals(2, store.getEventsByIdSinceSeqNr(twelve, 1).size());
  }
}
