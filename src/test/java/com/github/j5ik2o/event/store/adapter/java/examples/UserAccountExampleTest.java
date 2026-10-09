package com.github.j5ik2o.event.store.adapter.java.examples;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.j5ik2o.event.store.adapter.java.core.AggregateId;
import com.github.j5ik2o.event.store.adapter.java.core.AsyncEventStore;
import com.github.j5ik2o.event.store.adapter.java.core.EventEnvelope;
import com.github.j5ik2o.event.store.adapter.java.core.EventStore;
import com.github.j5ik2o.event.store.adapter.java.core.SnapshotReadResult;
import com.github.j5ik2o.event.store.adapter.java.memory.MemoryEventStore;
import com.github.j5ik2o.event.store.adapter.java.memory.MemoryStorage;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class UserAccountExampleTest {
  private static final AggregateId ID = AggregateId.of("UserAccount", "example-1");

  @Test
  void synchronousPublicFactoryRestoresAnEventOnlyAccount() {
    EventStore<String, String> store =
        MemoryEventStore.create(MemoryStorage.create(), UserAccountExample.config());
    assertTrue(UserAccountExample.restore(store, ID).isEmpty());

    EventEnvelope<String> created = UserAccountExample.event(ID, 1, "Alice");
    store.persistEvent(created);

    SnapshotReadResult<String> result = store.getLatestSnapshotById(ID).orElseThrow();
    assertEquals(1, result.headSeqNr());
    assertTrue(result.snapshot().isEmpty());
    assertEquals(Optional.of("Alice"), UserAccountExample.restore(store, ID));
    assertEvent(created, store.getEventsByIdSinceSeqNr(ID, 1));
  }

  @Test
  void synchronousPublicFactoryRestoresSnapshotThenLaterEvents() {
    EventStore<String, String> store =
        MemoryEventStore.create(MemoryStorage.create(), UserAccountExample.config());
    store.persistEvent(UserAccountExample.event(ID, 1, "Alice"));

    store.persistEventAndSnapshot(
        UserAccountExample.event(ID, 2, "Bob"), UserAccountExample.snapshot(2, "Bob"));
    assertEquals(Optional.of("Bob"), UserAccountExample.restore(store, ID));
    store.persistEvent(UserAccountExample.event(ID, 3, "Carol"));

    SnapshotReadResult<String> result = store.getLatestSnapshotById(ID).orElseThrow();
    assertEquals(3, result.headSeqNr());
    assertEquals(2, result.snapshot().orElseThrow().seqNr());
    assertEquals("user-state", result.snapshot().orElseThrow().manifest());
    assertEquals(Optional.of("Carol"), UserAccountExample.restore(store, ID));
    assertEquals(1, store.getEventsByIdSinceSeqNr(ID, 3).size());
  }

  @Test
  void asynchronousPublicFactoryRestoresAnEventOnlyAccount() {
    AsyncEventStore<String, String> store =
        MemoryEventStore.createAsync(MemoryStorage.create(), UserAccountExample.config());
    assertTrue(UserAccountExample.restoreAsync(store, ID).join().isEmpty());

    EventEnvelope<String> created = UserAccountExample.event(ID, 1, "Alice");
    store.persistEvent(created).join();

    SnapshotReadResult<String> result = store.getLatestSnapshotById(ID).join().orElseThrow();
    assertEquals(1, result.headSeqNr());
    assertTrue(result.snapshot().isEmpty());
    assertEquals(Optional.of("Alice"), UserAccountExample.restoreAsync(store, ID).join());
    assertEvent(created, store.getEventsByIdSinceSeqNr(ID, 1).join());
  }

  @Test
  void asynchronousPublicFactoryRestoresSnapshotThenLaterEvents() {
    AsyncEventStore<String, String> store =
        MemoryEventStore.createAsync(MemoryStorage.create(), UserAccountExample.config());
    store.persistEvent(UserAccountExample.event(ID, 1, "Alice")).join();

    store
        .persistEventAndSnapshot(
            UserAccountExample.event(ID, 2, "Bob"), UserAccountExample.snapshot(2, "Bob"))
        .join();
    assertEquals(Optional.of("Bob"), UserAccountExample.restoreAsync(store, ID).join());
    store.persistEvent(UserAccountExample.event(ID, 3, "Carol")).join();

    SnapshotReadResult<String> result = store.getLatestSnapshotById(ID).join().orElseThrow();
    assertEquals(3, result.headSeqNr());
    assertEquals(2, result.snapshot().orElseThrow().seqNr());
    assertEquals("user-state", result.snapshot().orElseThrow().manifest());
    assertEquals(Optional.of("Carol"), UserAccountExample.restoreAsync(store, ID).join());
    assertEquals(1, store.getEventsByIdSinceSeqNr(ID, 3).join().size());
  }

  private static void assertEvent(
      EventEnvelope<String> expected, List<EventEnvelope<String>> events) {
    assertEquals(1, events.size());
    EventEnvelope<String> actual = events.get(0);
    assertEquals(expected.aggregateId(), actual.aggregateId());
    assertEquals(expected.seqNr(), actual.seqNr());
    assertEquals(expected.occurredAt(), actual.occurredAt());
    assertEquals(expected.manifest(), actual.manifest());
    assertEquals(expected.payload(), actual.payload());
  }
}
