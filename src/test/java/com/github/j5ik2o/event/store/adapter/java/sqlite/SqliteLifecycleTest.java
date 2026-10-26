package com.github.j5ik2o.event.store.adapter.java.sqlite;

import static com.github.j5ik2o.event.store.adapter.java.sqlite.SqliteTestFixtures.AID;
import static com.github.j5ik2o.event.store.adapter.java.sqlite.SqliteTestFixtures.config;
import static com.github.j5ik2o.event.store.adapter.java.sqlite.SqliteTestFixtures.event;
import static com.github.j5ik2o.event.store.adapter.java.sqlite.SqliteTestFixtures.initialized;
import static com.github.j5ik2o.event.store.adapter.java.sqlite.SqliteTestFixtures.settings;
import static com.github.j5ik2o.event.store.adapter.java.sqlite.SqliteTestFixtures.snapshot;
import static com.github.j5ik2o.event.store.adapter.java.sqlite.SqliteTestFixtures.store;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.github.j5ik2o.event.store.adapter.java.core.ConfigurationException;
import com.github.j5ik2o.event.store.adapter.java.core.EventEnvelope;
import com.github.j5ik2o.event.store.adapter.java.core.EventStore;
import com.github.j5ik2o.event.store.adapter.java.core.RetentionPolicy;
import com.github.j5ik2o.event.store.adapter.java.core.StorageException;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SqliteLifecycleTest {
  @TempDir Path directory;

  @Test
  void dataWrittenBeforeCloseIsReadableAfterReopeningTheSameFile() {
    Path file = initialized(directory, RetentionPolicy.delete(2));
    EventEnvelope<String> written = event(1);
    try (SqliteStorage storage = SqliteStorage.open(file, settings(RetentionPolicy.delete(2)))) {
      store(storage).persistEventAndSnapshot(written, snapshot(1));
      store(storage).persistEvent(event(2));
    }

    try (SqliteStorage reopened = SqliteStorage.open(file, settings(RetentionPolicy.delete(2)))) {
      EventStore<String, String> store = store(reopened);
      List<EventEnvelope<String>> events = store.getEventsByIdSinceSeqNr(AID, 0);

      assertEquals(2, events.size());
      assertEquals(written.payload(), events.get(0).payload());
      assertEquals(written.occurredAt(), events.get(0).occurredAt());
      assertEquals(written.manifest(), events.get(0).manifest());
      assertEquals(2, store.getLatestSnapshotById(AID).get().headSeqNr());
      assertEquals(
          "snapshot-1", store.getLatestSnapshotById(AID).get().snapshot().get().aggregate());
    }
  }

  @Test
  void closeIsIdempotent() {
    Path file = initialized(directory, RetentionPolicy.none());
    SqliteStorage storage = SqliteStorage.open(file, settings(RetentionPolicy.none()));

    storage.close();

    assertDoesNotThrow(storage::close);
  }

  @Test
  void operationsAfterCloseAreStorageErrors() {
    Path file = initialized(directory, RetentionPolicy.none());
    SqliteStorage storage = SqliteStorage.open(file, settings(RetentionPolicy.none()));
    EventStore<String, String> store = store(storage);
    storage.close();

    assertThrows(StorageException.class, () -> store.persistEvent(event(1)));
    assertThrows(StorageException.class, () -> store.getEventsByIdSinceSeqNr(AID, 0));
    assertThrows(StorageException.class, () -> store.getLatestSnapshotById(AID));
  }

  @Test
  void nullStorageOrConfigurationIsAConfigurationError() {
    Path file = initialized(directory, RetentionPolicy.none());
    try (SqliteStorage storage = SqliteStorage.open(file, settings(RetentionPolicy.none()))) {
      assertThrows(ConfigurationException.class, () -> SqliteEventStore.create(null, config()));
      assertThrows(ConfigurationException.class, () -> SqliteEventStore.create(storage, null));
      assertThrows(
          ConfigurationException.class, () -> SqliteEventStore.createAsync(null, config()));
      assertThrows(ConfigurationException.class, () -> SqliteEventStore.createAsync(storage, null));
    }
    assertThrows(
        ConfigurationException.class,
        () -> SqliteStorage.initialize(null, settings(RetentionPolicy.none())));
    assertThrows(ConfigurationException.class, () -> SqliteStorage.initialize(file, null));
    assertThrows(
        ConfigurationException.class,
        () -> SqliteStorage.open(null, settings(RetentionPolicy.none())));
    assertThrows(ConfigurationException.class, () -> SqliteStorage.open(file, null));
  }
}
