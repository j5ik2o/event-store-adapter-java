package com.github.j5ik2o.event.store.adapter.java.sqlite;

import static com.github.j5ik2o.event.store.adapter.java.sqlite.SqliteTestFixtures.AID;
import static com.github.j5ik2o.event.store.adapter.java.sqlite.SqliteTestFixtures.event;
import static com.github.j5ik2o.event.store.adapter.java.sqlite.SqliteTestFixtures.execute;
import static com.github.j5ik2o.event.store.adapter.java.sqlite.SqliteTestFixtures.initialized;
import static com.github.j5ik2o.event.store.adapter.java.sqlite.SqliteTestFixtures.settings;
import static com.github.j5ik2o.event.store.adapter.java.sqlite.SqliteTestFixtures.snapshot;
import static com.github.j5ik2o.event.store.adapter.java.sqlite.SqliteTestFixtures.store;
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
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SqliteReadTest {
  @TempDir Path directory;

  @Test
  void unknownAggregateReadsEmptyResults() {
    Path file = initialized(directory, RetentionPolicy.none());
    try (SqliteStorage storage = SqliteStorage.open(file, settings(RetentionPolicy.none()))) {
      EventStore<String, String> store = store(storage);

      assertTrue(store.getEventsByIdSinceSeqNr(AID, 0).isEmpty());
      assertFalse(store.getLatestSnapshotById(AID).isPresent());
    }
  }

  @Test
  void headWithoutSnapshotReturnsTheHeadAndNoSnapshot() {
    Path file = initialized(directory, RetentionPolicy.none());
    try (SqliteStorage storage = SqliteStorage.open(file, settings(RetentionPolicy.none()))) {
      EventStore<String, String> store = store(storage);
      store.persistEvent(event(1));
      store.persistEvent(event(2));

      SnapshotReadResult<String> result = store.getLatestSnapshotById(AID).get();

      assertEquals(2, result.headSeqNr());
      assertFalse(result.snapshot().isPresent());
    }
  }

  @Test
  void headWithSnapshotReturnsBoth() {
    Path file = initialized(directory, RetentionPolicy.none());
    try (SqliteStorage storage = SqliteStorage.open(file, settings(RetentionPolicy.none()))) {
      EventStore<String, String> store = store(storage);
      store.persistEvent(event(1));
      store.persistEventAndSnapshot(event(2), snapshot(2));
      store.persistEvent(event(3));

      SnapshotReadResult<String> result = store.getLatestSnapshotById(AID).get();

      assertEquals(3, result.headSeqNr());
      assertEquals(2, result.snapshot().get().seqNr());
      assertEquals("snapshot-2", result.snapshot().get().aggregate());
    }
  }

  @Test
  void eventsAreReadFromTheRequestedSequenceInOrderAndSeqZeroStartsAtOne() {
    Path file = initialized(directory, RetentionPolicy.none());
    try (SqliteStorage storage = SqliteStorage.open(file, settings(RetentionPolicy.none()))) {
      EventStore<String, String> store = store(storage);
      for (long seq = 1; seq <= 4; seq++) {
        store.persistEvent(event(seq));
      }

      assertEquals(4, store.getEventsByIdSinceSeqNr(AID, 0).size());
      List<EventEnvelope<String>> since3 = store.getEventsByIdSinceSeqNr(AID, 3);
      assertEquals(2, since3.size());
      assertEquals(3, since3.get(0).seqNr());
      assertEquals("event-4", since3.get(1).payload());
      assertEquals(AID, since3.get(0).aggregateId());
      assertTrue(store.getEventsByIdSinceSeqNr(AID, 5).isEmpty());
    }
  }

  @Test
  void snapshotReadKeepsTheStartingHeadAndSnapshotPairWhenAnotherStoreCommitsMidRead() {
    Path file = initialized(directory, RetentionPolicy.none());
    try (SqliteStorage writer = SqliteStorage.open(file, settings(RetentionPolicy.none()))) {
      store(writer).persistEventAndSnapshot(event(1), snapshot(1));
      SqliteStorageHooks hooks =
          new SqliteStorageHooks() {
            @Override
            public void beforeReadSnapshot(AggregateId aggregateId) {
              store(writer).persistEventAndSnapshot(event(2), snapshot(2));
            }
          };
      try (SqliteStorage reader =
          SqliteStorage.open(file, settings(RetentionPolicy.none()), hooks)) {
        SnapshotReadResult<String> result = store(reader).getLatestSnapshotById(AID).get();

        assertEquals(1, result.headSeqNr());
        assertEquals(1, result.snapshot().get().seqNr());
      }
      assertEquals(2, store(writer).getLatestSnapshotById(AID).get().headSeqNr());
    }
  }

  @Test
  void eventReadDoesNotIncludeAnEventCommittedByAnotherStoreAfterTheHeadWasRead() {
    Path file = initialized(directory, RetentionPolicy.none());
    try (SqliteStorage writer = SqliteStorage.open(file, settings(RetentionPolicy.none()))) {
      store(writer).persistEvent(event(1));
      SqliteStorageHooks hooks =
          new SqliteStorageHooks() {
            @Override
            public void beforeReadEvents(AggregateId aggregateId) {
              store(writer).persistEvent(event(2));
            }
          };
      try (SqliteStorage reader =
          SqliteStorage.open(file, settings(RetentionPolicy.none()), hooks)) {
        List<EventEnvelope<String>> events = store(reader).getEventsByIdSinceSeqNr(AID, 0);

        assertEquals(1, events.size());
        assertEquals(1, events.get(0).seqNr());
      }
      assertEquals(2, store(writer).getEventsByIdSinceSeqNr(AID, 0).size());
    }
  }

  @Test
  void journalRowsBeyondTheHeadAreNotReturned() {
    Path file = initialized(directory, RetentionPolicy.none());
    try (SqliteStorage storage = SqliteStorage.open(file, settings(RetentionPolicy.none()))) {
      EventStore<String, String> store = store(storage);
      store.persistEvent(event(1));
      store.persistEvent(event(2));
      execute(
          file,
          "INSERT INTO journal (aid, seq_nr, occurred_at_ns, manifest, payload) VALUES ('Order-1', 3, 0, 'event', x'2265223022')");

      assertEquals(2, store.getEventsByIdSinceSeqNr(AID, 0).size());
    }
  }

  @Test
  void aGapInTheRequestedRangeIsAStorageErrorNotAShortSuccess() {
    Path file = initialized(directory, RetentionPolicy.none());
    try (SqliteStorage storage = SqliteStorage.open(file, settings(RetentionPolicy.none()))) {
      EventStore<String, String> store = store(storage);
      store.persistEvent(event(1));
      store.persistEvent(event(2));
      store.persistEvent(event(3));
      execute(file, "DELETE FROM journal WHERE seq_nr = 2");

      assertThrows(StorageException.class, () -> store.getEventsByIdSinceSeqNr(AID, 0));
      assertThrows(StorageException.class, () -> store.getEventsByIdSinceSeqNr(AID, 2));
      assertEquals(1, store.getEventsByIdSinceSeqNr(AID, 3).size());
    }
  }

  @Test
  void readOnlyOutsideTheGapStillSucceedsWhenTheGapIsBeforeTheRequestedStart() {
    Path file = initialized(directory, RetentionPolicy.none());
    try (SqliteStorage storage = SqliteStorage.open(file, settings(RetentionPolicy.none()))) {
      EventStore<String, String> store = store(storage);
      store.persistEvent(event(1));
      store.persistEvent(event(2));
      execute(file, "DELETE FROM journal WHERE seq_nr = 1");

      Optional<SnapshotReadResult<String>> snapshot = store.getLatestSnapshotById(AID);

      assertEquals(2, snapshot.get().headSeqNr());
      assertEquals(1, store.getEventsByIdSinceSeqNr(AID, 2).size());
    }
  }
}
