package com.github.j5ik2o.event.store.adapter.java.sqlite;

import static com.github.j5ik2o.event.store.adapter.java.sqlite.SqliteTestFixtures.AID;
import static com.github.j5ik2o.event.store.adapter.java.sqlite.SqliteTestFixtures.count;
import static com.github.j5ik2o.event.store.adapter.java.sqlite.SqliteTestFixtures.event;
import static com.github.j5ik2o.event.store.adapter.java.sqlite.SqliteTestFixtures.initialized;
import static com.github.j5ik2o.event.store.adapter.java.sqlite.SqliteTestFixtures.longs;
import static com.github.j5ik2o.event.store.adapter.java.sqlite.SqliteTestFixtures.settings;
import static com.github.j5ik2o.event.store.adapter.java.sqlite.SqliteTestFixtures.snapshot;
import static com.github.j5ik2o.event.store.adapter.java.sqlite.SqliteTestFixtures.store;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.j5ik2o.event.store.adapter.java.core.EventStore;
import com.github.j5ik2o.event.store.adapter.java.core.EventStoreConfig;
import com.github.j5ik2o.event.store.adapter.java.core.JsonPayloadSerializer;
import com.github.j5ik2o.event.store.adapter.java.core.RetentionFailure;
import com.github.j5ik2o.event.store.adapter.java.core.RetentionFailureListener;
import com.github.j5ik2o.event.store.adapter.java.core.RetentionMode;
import com.github.j5ik2o.event.store.adapter.java.core.RetentionPolicy;
import com.github.j5ik2o.event.store.adapter.java.core.StorageException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SqliteRetentionTest {
  private static final String HISTORY = "SELECT seq_nr FROM snapshot_history ORDER BY seq_nr";

  @TempDir Path directory;

  private static void writeSnapshots(EventStore<String, String> store, int n) {
    for (long seq = 1; seq <= n; seq++) {
      store.persistEventAndSnapshot(event(seq), snapshot(seq));
    }
  }

  @Test
  void deleteTwoKeepsTheNewestTwoHistoryRowsAndLeavesJournalHeadAndCurrentUntouched() {
    Path file = initialized(directory, RetentionPolicy.delete(2));
    try (SqliteStorage storage = SqliteStorage.open(file, settings(RetentionPolicy.delete(2)))) {
      writeSnapshots(store(storage), 4);
    }

    assertEquals(List.of(3L, 4L), longs(file, HISTORY));
    assertEquals(4, count(file, "journal"));
    assertEquals(List.of(4L), longs(file, "SELECT seq_nr FROM head"));
    assertEquals(List.of(4L), longs(file, "SELECT seq_nr FROM snapshot_current"));
  }

  @Test
  void deleteOneKeepsOnlyTheNewestHistoryRow() {
    Path file = initialized(directory, RetentionPolicy.delete(1));
    try (SqliteStorage storage = SqliteStorage.open(file, settings(RetentionPolicy.delete(1)))) {
      writeSnapshots(store(storage), 3);
    }

    assertEquals(List.of(3L), longs(file, HISTORY));
  }

  @Test
  void noRetentionNeverCreatesHistoryRows() {
    Path file = initialized(directory, RetentionPolicy.none());
    try (SqliteStorage storage = SqliteStorage.open(file, settings(RetentionPolicy.none()))) {
      writeSnapshots(store(storage), 3);
    }

    assertEquals(0, count(file, "snapshot_history"));
  }

  @Test
  void failureBeforeDeleteKeepsTheCommittedAppendNotifiesOnceAndTheNextSnapshotWriteCleansUp() {
    Path file = initialized(directory, RetentionPolicy.delete(1));
    boolean[] fail = {false};
    SqliteStorageHooks hooks =
        new SqliteStorageHooks() {
          @Override
          public void beforeRetentionDelete(
              com.github.j5ik2o.event.store.adapter.java.core.AggregateId aggregateId,
              List<Long> seqNrs) {
            if (fail[0]) {
              throw new StorageException("injected retention failure");
            }
          }
        };
    List<RetentionFailure> notifications = new ArrayList<>();
    try (SqliteStorage storage =
        SqliteStorage.open(file, settings(RetentionPolicy.delete(1)), hooks)) {
      EventStore<String, String> store =
          SqliteEventStore.create(storage, configuration(notifications::add));
      store.persistEventAndSnapshot(event(1), snapshot(1));
      fail[0] = true;

      assertDoesNotThrow(() -> store.persistEventAndSnapshot(event(2), snapshot(2)));

      assertEquals(1, notifications.size());
      assertEquals(AID, notifications.get(0).aggregateId());
      assertEquals(RetentionMode.DELETE, notifications.get(0).mode());
      assertEquals(List.of(1L, 2L), longs(file, HISTORY));
      assertEquals(2, store.getLatestSnapshotById(AID).get().headSeqNr());
      assertEquals(2, store.getEventsByIdSinceSeqNr(AID, 0).size());

      fail[0] = false;
      store.persistEventAndSnapshot(event(3), snapshot(3));

      assertEquals(List.of(3L), longs(file, HISTORY));
      assertEquals(1, notifications.size());
    }
  }

  @Test
  void aListenerExceptionDoesNotFailTheAppend() {
    Path file = initialized(directory, RetentionPolicy.delete(1));
    int[] deleteCalls = {0};
    int[] listenerCalls = {0};
    SqliteStorageHooks hooks =
        new SqliteStorageHooks() {
          @Override
          public void beforeRetentionDelete(
              com.github.j5ik2o.event.store.adapter.java.core.AggregateId aggregateId,
              List<Long> seqNrs) {
            deleteCalls[0]++;
            throw new StorageException("injected retention failure");
          }
        };
    try (SqliteStorage storage =
        SqliteStorage.open(file, settings(RetentionPolicy.delete(1)), hooks)) {
      EventStore<String, String> store =
          SqliteEventStore.create(
              storage,
              configuration(
                  failure -> {
                    listenerCalls[0]++;
                    throw new IllegalStateException("listener failure");
                  }));
      store.persistEventAndSnapshot(event(1), snapshot(1));

      assertDoesNotThrow(() -> store.persistEventAndSnapshot(event(2), snapshot(2)));
    }
    assertEquals(1, deleteCalls[0]);
    assertEquals(1, listenerCalls[0]);
    assertEquals(List.of(2L), longs(file, "SELECT seq_nr FROM head"));
    assertEquals(List.of(1L, 2L), longs(file, HISTORY));
  }

  @Test
  void eventOnlyAppendDoesNotRunRetention() {
    Path file = initialized(directory, RetentionPolicy.delete(1));
    int[] deleteCalls = {0};
    SqliteStorageHooks hooks =
        new SqliteStorageHooks() {
          @Override
          public void beforeRetentionDelete(
              com.github.j5ik2o.event.store.adapter.java.core.AggregateId aggregateId,
              List<Long> seqNrs) {
            deleteCalls[0]++;
          }
        };
    try (SqliteStorage storage =
        SqliteStorage.open(file, settings(RetentionPolicy.delete(1)), hooks)) {
      writeSnapshots(store(storage), 2);
      int afterSnapshots = deleteCalls[0];
      store(storage).persistEvent(event(3));

      assertEquals(afterSnapshots, deleteCalls[0]);
      assertTrue(afterSnapshots >= 1);
    }
  }

  private static EventStoreConfig<String, String> configuration(RetentionFailureListener listener) {
    return EventStoreConfig.<String, String>builder()
        .payloadSerializer(JsonPayloadSerializer.of(String.class))
        .snapshotSerializer(JsonPayloadSerializer.of(String.class))
        .retentionFailureListener(listener)
        .build();
  }
}
