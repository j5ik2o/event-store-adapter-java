package com.github.j5ik2o.event.store.adapter.java.sqlite;

import static com.github.j5ik2o.event.store.adapter.java.sqlite.SqliteTestFixtures.AID;
import static com.github.j5ik2o.event.store.adapter.java.sqlite.SqliteTestFixtures.config;
import static com.github.j5ik2o.event.store.adapter.java.sqlite.SqliteTestFixtures.event;
import static com.github.j5ik2o.event.store.adapter.java.sqlite.SqliteTestFixtures.initialized;
import static com.github.j5ik2o.event.store.adapter.java.sqlite.SqliteTestFixtures.settings;
import static com.github.j5ik2o.event.store.adapter.java.sqlite.SqliteTestFixtures.snapshot;
import static com.github.j5ik2o.event.store.adapter.java.sqlite.SqliteTestFixtures.store;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.j5ik2o.event.store.adapter.java.core.AsyncEventStore;
import com.github.j5ik2o.event.store.adapter.java.core.ContractViolationException;
import com.github.j5ik2o.event.store.adapter.java.core.EventStore;
import com.github.j5ik2o.event.store.adapter.java.core.EventStoreExceptions;
import com.github.j5ik2o.event.store.adapter.java.core.OptimisticLockException;
import com.github.j5ik2o.event.store.adapter.java.core.RetentionPolicy;
import com.github.j5ik2o.event.store.adapter.java.core.StorageException;
import java.nio.file.Path;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SqliteAsyncEventStoreTest {
  @TempDir Path directory;

  @Test
  void fourAsyncOperationsWorkAgainstTheSameFileAsTheSyncStore() {
    Path file = initialized(directory, RetentionPolicy.none());
    try (SqliteStorage storage = SqliteStorage.open(file, settings(RetentionPolicy.none()))) {
      AsyncEventStore<String, String> async = SqliteEventStore.createAsync(storage, config());
      EventStore<String, String> sync = store(storage);

      async.persistEvent(event(1)).join();
      async.persistEventAndSnapshot(event(2), snapshot(2)).join();

      assertEquals(2, async.getLatestSnapshotById(AID).join().get().headSeqNr());
      assertEquals(
          "snapshot-2", async.getLatestSnapshotById(AID).join().get().snapshot().get().aggregate());
      assertEquals(2, async.getEventsByIdSinceSeqNr(AID, 0).join().size());
      assertEquals(2, sync.getEventsByIdSinceSeqNr(AID, 0).size());
    }
  }

  @Test
  void optimisticLockAndContractViolationKeepTheirTypesThroughTheFuture() {
    Path file = initialized(directory, RetentionPolicy.none());
    try (SqliteStorage storage = SqliteStorage.open(file, settings(RetentionPolicy.none()))) {
      AsyncEventStore<String, String> async = SqliteEventStore.createAsync(storage, config());
      async.persistEvent(event(1)).join();

      Throwable conflict =
          EventStoreExceptions.unwrap(
              assertThrows(CompletionException.class, () -> async.persistEvent(event(1)).join()));
      Throwable gap =
          EventStoreExceptions.unwrap(
              assertThrows(CompletionException.class, () -> async.persistEvent(event(5)).join()));

      assertTrue(conflict instanceof OptimisticLockException);
      assertTrue(gap instanceof ContractViolationException);
    }
  }

  @Test
  void aClosedStorageFailsTheFutureWithAStorageError() {
    Path file = initialized(directory, RetentionPolicy.none());
    SqliteStorage storage = SqliteStorage.open(file, settings(RetentionPolicy.none()));
    AsyncEventStore<String, String> async = SqliteEventStore.createAsync(storage, config());
    storage.close();

    Throwable failure =
        EventStoreExceptions.unwrap(
            assertThrows(
                CompletionException.class, () -> async.getEventsByIdSinceSeqNr(AID, 0).join()));

    assertTrue(failure instanceof StorageException);
  }
}
