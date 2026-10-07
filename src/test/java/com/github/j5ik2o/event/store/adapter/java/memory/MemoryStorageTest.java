package com.github.j5ik2o.event.store.adapter.java.memory;

import static com.github.j5ik2o.event.store.adapter.java.memory.MemoryTestFixtures.AID;
import static com.github.j5ik2o.event.store.adapter.java.memory.MemoryTestFixtures.event;
import static com.github.j5ik2o.event.store.adapter.java.memory.MemoryTestFixtures.snapshot;
import static com.github.j5ik2o.event.store.adapter.java.memory.MemoryTestFixtures.store;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.j5ik2o.event.store.adapter.java.core.ConfigurationException;
import com.github.j5ik2o.event.store.adapter.java.core.ErrorCategory;
import com.github.j5ik2o.event.store.adapter.java.core.EventStore;
import com.github.j5ik2o.event.store.adapter.java.core.RetentionPolicy;
import org.junit.jupiter.api.Test;

class MemoryStorageTest {
  @Test
  void explicitlySharedStorageExposesWritesToAnotherStore() {
    MemoryStorage storage = MemoryStorage.create();
    EventStore<String, String> writer = store(storage);
    EventStore<String, String> reader = store(storage);

    writer.persistEventAndSnapshot(event(1), snapshot(1));

    assertEquals("event-1", reader.getEventsByIdSinceSeqNr(AID, 0).get(0).payload());
    assertEquals(
        "snapshot-1", reader.getLatestSnapshotById(AID).get().snapshot().get().aggregate());
  }

  @Test
  void separatelyCreatedStorageDoesNotShareEvenTheSameAggregateId() {
    EventStore<String, String> first = store(MemoryStorage.create());
    EventStore<String, String> second = store(MemoryStorage.create());

    first.persistEvent(event(1));

    assertTrue(second.getEventsByIdSinceSeqNr(AID, 0).isEmpty());
    assertTrue(second.getLatestSnapshotById(AID).isEmpty());
    assertDoesNotThrow(() -> second.persistEvent(event(1)));
  }

  @Test
  void ttlPolicyIsRejectedAtStorageCreation() {
    RetentionPolicy policy = RetentionPolicy.ttl(1, 0);

    ConfigurationException failure =
        assertThrows(ConfigurationException.class, () -> MemoryStorage.create(policy));

    assertEquals(ErrorCategory.CONFIGURATION, failure.category());
  }
}
