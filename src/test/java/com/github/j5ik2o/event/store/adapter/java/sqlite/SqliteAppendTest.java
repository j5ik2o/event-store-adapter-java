package com.github.j5ik2o.event.store.adapter.java.sqlite;

import static com.github.j5ik2o.event.store.adapter.java.sqlite.SqliteTestFixtures.AID;
import static com.github.j5ik2o.event.store.adapter.java.sqlite.SqliteTestFixtures.count;
import static com.github.j5ik2o.event.store.adapter.java.sqlite.SqliteTestFixtures.event;
import static com.github.j5ik2o.event.store.adapter.java.sqlite.SqliteTestFixtures.execute;
import static com.github.j5ik2o.event.store.adapter.java.sqlite.SqliteTestFixtures.initialized;
import static com.github.j5ik2o.event.store.adapter.java.sqlite.SqliteTestFixtures.longs;
import static com.github.j5ik2o.event.store.adapter.java.sqlite.SqliteTestFixtures.settings;
import static com.github.j5ik2o.event.store.adapter.java.sqlite.SqliteTestFixtures.snapshot;
import static com.github.j5ik2o.event.store.adapter.java.sqlite.SqliteTestFixtures.store;
import static com.github.j5ik2o.event.store.adapter.java.sqlite.SqliteTestFixtures.string;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.j5ik2o.event.store.adapter.java.core.AggregateId;
import com.github.j5ik2o.event.store.adapter.java.core.ContractViolationException;
import com.github.j5ik2o.event.store.adapter.java.core.EventEnvelope;
import com.github.j5ik2o.event.store.adapter.java.core.EventStore;
import com.github.j5ik2o.event.store.adapter.java.core.OptimisticLockException;
import com.github.j5ik2o.event.store.adapter.java.core.RetentionPolicy;
import com.github.j5ik2o.event.store.adapter.java.core.StorageException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SqliteAppendTest {
  @TempDir Path directory;

  private static SqliteStorageHooks failBeforeCommit() {
    return new SqliteStorageHooks() {
      @Override
      public void beforeCommit(AggregateId aggregateId) {
        throw new StorageException("injected commit failure");
      }
    };
  }

  @Test
  void firstEventCreatesJournalAndHeadAndLaterEventsAdvanceTheHead() {
    Path file = initialized(directory, RetentionPolicy.none());
    try (SqliteStorage storage = SqliteStorage.open(file, settings(RetentionPolicy.none()))) {
      EventStore<String, String> store = store(storage);

      store.persistEvent(event(1));
      store.persistEvent(event(2));
    }

    assertEquals(List.of(1L, 2L), longs(file, "SELECT seq_nr FROM journal ORDER BY seq_nr"));
    assertEquals(List.of(2L), longs(file, "SELECT seq_nr FROM head"));
    assertEquals(1, count(file, "head"));
    assertEquals(0, count(file, "snapshot_current"));
  }

  @Test
  void eventAndSnapshotWriteTheCurrentSnapshotAndNoHistoryWithoutRetention() {
    Path file = initialized(directory, RetentionPolicy.none());
    try (SqliteStorage storage = SqliteStorage.open(file, settings(RetentionPolicy.none()))) {
      store(storage).persistEventAndSnapshot(event(1), snapshot(1));
      store(storage).persistEventAndSnapshot(event(2), snapshot(2));
    }

    assertEquals(List.of(2L), longs(file, "SELECT seq_nr FROM snapshot_current"));
    assertEquals(0, count(file, "snapshot_history"));
  }

  @Test
  void sequenceAtOrBelowTheHeadIsAnOptimisticLockWithOnlyAidSeqAndHeadInTheMessage() {
    Path file = initialized(directory, RetentionPolicy.none());
    try (SqliteStorage storage = SqliteStorage.open(file, settings(RetentionPolicy.none()))) {
      EventStore<String, String> store = store(storage);
      store.persistEvent(event(1));
      store.persistEvent(event(2));

      OptimisticLockException duplicate =
          assertThrows(OptimisticLockException.class, () -> store.persistEvent(event(2)));
      OptimisticLockException stale =
          assertThrows(OptimisticLockException.class, () -> store.persistEvent(event(1)));

      assertEquals(
          "optimistic lock conflict: aid=Order-1, seq_nr=2, head_seq_nr=2", duplicate.getMessage());
      assertEquals(
          "optimistic lock conflict: aid=Order-1, seq_nr=1, head_seq_nr=2", stale.getMessage());
    }
    assertEquals(2, count(file, "journal"));
  }

  @Test
  void firstEventForAnExistingAggregateIsAnOptimisticLock() {
    Path file = initialized(directory, RetentionPolicy.none());
    try (SqliteStorage storage = SqliteStorage.open(file, settings(RetentionPolicy.none()))) {
      EventStore<String, String> store = store(storage);
      store.persistEvent(event(1));

      assertThrows(OptimisticLockException.class, () -> store.persistEvent(event(1)));
    }
  }

  @Test
  void skippingASequenceNumberIsAContractViolationAndWritesNothing() {
    Path file = initialized(directory, RetentionPolicy.none());
    try (SqliteStorage storage = SqliteStorage.open(file, settings(RetentionPolicy.none()))) {
      EventStore<String, String> store = store(storage);

      ContractViolationException first =
          assertThrows(ContractViolationException.class, () -> store.persistEvent(event(3)));
      store.persistEvent(event(1));
      ContractViolationException later =
          assertThrows(ContractViolationException.class, () -> store.persistEvent(event(3)));

      assertEquals("W-8", first.rule());
      assertEquals("W-8", later.rule());
    }
    assertEquals(List.of(1L), longs(file, "SELECT seq_nr FROM journal"));
  }

  @Test
  void failureBeforeCommitLeavesNoRowInAnyTable() {
    Path file = initialized(directory, RetentionPolicy.delete(2));
    try (SqliteStorage storage =
        SqliteStorage.open(file, settings(RetentionPolicy.delete(2)), failBeforeCommit())) {
      assertThrows(
          StorageException.class,
          () -> store(storage).persistEventAndSnapshot(event(1), snapshot(1)));
    }

    for (String table : List.of("journal", "head", "snapshot_current", "snapshot_history")) {
      assertEquals(0, count(file, table), table);
    }
  }

  @Test
  void aStoreStillWorksAfterAFailedAppendBecauseTheTransactionWasRolledBack() {
    Path file = initialized(directory, RetentionPolicy.none());
    boolean[] fail = {true};
    SqliteStorageHooks hooks =
        new SqliteStorageHooks() {
          @Override
          public void beforeCommit(AggregateId aggregateId) {
            if (fail[0]) {
              throw new StorageException("injected");
            }
          }
        };
    try (SqliteStorage storage =
        SqliteStorage.open(file, settings(RetentionPolicy.none()), hooks)) {
      assertThrows(StorageException.class, () -> store(storage).persistEvent(event(1)));
      fail[0] = false;

      store(storage).persistEvent(event(1));
    }
    assertEquals(List.of(1L), longs(file, "SELECT seq_nr FROM journal"));
  }

  @Test
  void primaryKeyDuplicateInTheJournalAfterTheHeadCheckIsAnOptimisticLock() {
    Path file = initialized(directory, RetentionPolicy.none());
    // A journal row without a head row: the head check passes, the journal insert collides.
    execute(
        file,
        "INSERT INTO journal (aid, seq_nr, occurred_at_ns, manifest, payload) VALUES ('Order-1', 1, 0, 'event', x'00')");
    try (SqliteStorage storage = SqliteStorage.open(file, settings(RetentionPolicy.none()))) {
      assertThrows(OptimisticLockException.class, () -> store(storage).persistEvent(event(1)));
    }
    assertEquals(0, count(file, "head"));
    assertEquals(1, count(file, "journal"));
  }

  @Test
  void primaryKeyDuplicateInTheSnapshotHistoryIsAStorageErrorAndRollsBackTheEvent() {
    Path file = initialized(directory, RetentionPolicy.delete(2));
    execute(
        file,
        "INSERT INTO snapshot_history (aid, seq_nr, manifest, payload) VALUES ('Order-1', 1, 'snapshot', x'00')");
    try (SqliteStorage storage = SqliteStorage.open(file, settings(RetentionPolicy.delete(2)))) {
      StorageException e =
          assertThrows(
              StorageException.class,
              () -> store(storage).persistEventAndSnapshot(event(1), snapshot(1)));
      assertFalse(e instanceof StorageException && e.getCause() instanceof OptimisticLockException);
    }
    assertEquals(0, count(file, "journal"));
    assertEquals(0, count(file, "head"));
    assertEquals(0, count(file, "snapshot_current"));
  }

  @Test
  void occurredAtIsStoredAsIntegerNanosecondsAndRoundTripsAtTheBoundaries() {
    Path file = initialized(directory, RetentionPolicy.none());
    Instant[] instants = {
      Instant.ofEpochSecond(0, -1),
      Instant.ofEpochSecond(0, Long.MIN_VALUE),
      Instant.ofEpochSecond(0, Long.MAX_VALUE),
      Instant.EPOCH,
      Instant.ofEpochSecond(1_700_000_000L, 123_456_789)
    };
    try (SqliteStorage storage = SqliteStorage.open(file, settings(RetentionPolicy.none()))) {
      EventStore<String, String> store = store(storage);
      for (int i = 0; i < instants.length; i++) {
        store.persistEvent(event(AID, i + 1, instants[i]));
      }

      List<EventEnvelope<String>> read = store.getEventsByIdSinceSeqNr(AID, 0);
      for (int i = 0; i < instants.length; i++) {
        assertEquals(instants[i], read.get(i).occurredAt());
      }
    }
    assertEquals(
        "integer", string(file, "SELECT typeof(occurred_at_ns) FROM journal WHERE seq_nr = 2"));
    assertEquals(
        String.valueOf(Long.MIN_VALUE),
        string(file, "SELECT occurred_at_ns FROM journal WHERE seq_nr = 2"));
    assertEquals(
        String.valueOf(Long.MAX_VALUE),
        string(file, "SELECT occurred_at_ns FROM journal WHERE seq_nr = 3"));
    assertEquals("-1", string(file, "SELECT occurred_at_ns FROM journal WHERE seq_nr = 1"));
    assertEquals("integer", string(file, "SELECT typeof(event_occurred_at_ns) FROM head"));
  }

  @Test
  void aSequenceNumberOfTwoToThe53MinusOneCanBeAppended() {
    Path file = initialized(directory, RetentionPolicy.none());
    execute(
        file,
        "INSERT INTO head (aid, type_name, seq_nr, event_occurred_at_ns, event_manifest, event_payload) VALUES ('Order-1', 'Order', 9007199254740990, 0, 'event', x'00')");
    try (SqliteStorage storage = SqliteStorage.open(file, settings(RetentionPolicy.none()))) {
      store(storage).persistEvent(event(9007199254740991L));
    }
    assertEquals(List.of(9007199254740991L), longs(file, "SELECT seq_nr FROM head"));
  }

  @Test
  void storedColumnTypesAreTextIntegerTextAndBlob() {
    Path file = initialized(directory, RetentionPolicy.delete(1));
    try (SqliteStorage storage = SqliteStorage.open(file, settings(RetentionPolicy.delete(1)))) {
      store(storage).persistEventAndSnapshot(event(1), snapshot(1));
    }

    assertEquals(
        "text,integer,integer,text,blob",
        string(
            file,
            "SELECT typeof(aid) || ',' || typeof(seq_nr) || ',' || typeof(occurred_at_ns) || ',' || typeof(manifest) || ',' || typeof(payload) FROM journal"));
    assertEquals(
        "text,integer,text,blob",
        string(
            file,
            "SELECT typeof(aid) || ',' || typeof(seq_nr) || ',' || typeof(manifest) || ',' || typeof(payload) FROM snapshot_history"));
    assertEquals("\"event-1\"", string(file, "SELECT CAST(payload AS TEXT) FROM journal"));
    assertTrue(
        "\"snapshot-1\""
            .equals(string(file, "SELECT CAST(payload AS TEXT) FROM snapshot_current")));
    assertEquals("Order-1", string(file, "SELECT aid FROM journal"));
    assertEquals("Order", string(file, "SELECT type_name FROM head"));
    assertEquals(
        "\"event-1\"".getBytes(StandardCharsets.UTF_8).length,
        Long.parseLong(string(file, "SELECT length(payload) FROM journal")));
  }

  @Test
  void likeCharactersInAnAidValueDoNotMatchOtherAggregates() {
    Path file = initialized(directory, RetentionPolicy.none());
    AggregateId percent = AggregateId.of("Order", "1%");
    AggregateId twelve = AggregateId.of("Order", "12");
    try (SqliteStorage storage = SqliteStorage.open(file, settings(RetentionPolicy.none()))) {
      EventStore<String, String> store = store(storage);
      store.persistEvent(event(percent, 1));
      store.persistEvent(event(twelve, 1));
      store.persistEvent(event(twelve, 2));

      List<EventEnvelope<String>> events = store.getEventsByIdSinceSeqNr(percent, 1);

      assertEquals(1, events.size());
      assertEquals(percent, events.get(0).aggregateId());
    }
  }

  @Test
  void underscoreAidThatWasNeverWrittenDoesNotMatchAnotherAggregate() {
    Path file = initialized(directory, RetentionPolicy.none());
    AggregateId twelve = AggregateId.of("Order", "12");
    AggregateId underscore = AggregateId.of("Order", "1_");
    try (SqliteStorage storage = SqliteStorage.open(file, settings(RetentionPolicy.none()))) {
      EventStore<String, String> store = store(storage);
      store.persistEvent(event(AggregateId.of("Order", "1%"), 1));
      store.persistEvent(event(twelve, 1));
      store.persistEvent(event(twelve, 2));

      assertTrue(store.getEventsByIdSinceSeqNr(underscore, 1).isEmpty());
      assertFalse(store.getLatestSnapshotById(underscore).isPresent());
    }
  }
}
