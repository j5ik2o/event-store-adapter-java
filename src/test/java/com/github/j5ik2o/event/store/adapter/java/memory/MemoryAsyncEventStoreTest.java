package com.github.j5ik2o.event.store.adapter.java.memory;

import static com.github.j5ik2o.event.store.adapter.java.memory.MemoryTestFixtures.AID;
import static com.github.j5ik2o.event.store.adapter.java.memory.MemoryTestFixtures.config;
import static com.github.j5ik2o.event.store.adapter.java.memory.MemoryTestFixtures.event;
import static com.github.j5ik2o.event.store.adapter.java.memory.MemoryTestFixtures.snapshot;
import static com.github.j5ik2o.event.store.adapter.java.memory.MemoryTestFixtures.store;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.j5ik2o.event.store.adapter.java.core.AsyncEventStore;
import com.github.j5ik2o.event.store.adapter.java.core.ErrorCategory;
import com.github.j5ik2o.event.store.adapter.java.core.EventStore;
import com.github.j5ik2o.event.store.adapter.java.core.EventStoreConfig;
import com.github.j5ik2o.event.store.adapter.java.core.EventStoreException;
import com.github.j5ik2o.event.store.adapter.java.core.EventStoreExceptions;
import com.github.j5ik2o.event.store.adapter.java.core.JsonPayloadSerializer;
import com.github.j5ik2o.event.store.adapter.java.core.OptimisticLockException;
import com.github.j5ik2o.event.store.adapter.java.core.PayloadSerializer;
import com.github.j5ik2o.event.store.adapter.java.core.SerializationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.Test;

class MemoryAsyncEventStoreTest {
  @Test
  void asyncNullEventRestorationReturnsAnExceptionalFutureWithSerializationClassification() {
    MemoryStorage storage = MemoryStorage.create();
    store(storage).persistEvent(event(1));
    AsyncEventStore<String, String> reader = nullRestoringReader(storage);

    CompletableFuture<?> result = assertDoesNotThrow(() -> reader.getEventsByIdSinceSeqNr(AID, 0));
    Throwable failure =
        EventStoreExceptions.unwrap(assertThrows(CompletionException.class, result::join));

    assertTrue(failure instanceof SerializationException);
    assertEquals(ErrorCategory.SERIALIZATION, ((SerializationException) failure).category());
    assertTrue(failure.getMessage().contains("deserialize"));
    assertTrue(failure.getMessage().contains("null"));
  }

  @Test
  void asyncNullSnapshotRestorationReturnsAnExceptionalFutureWithSerializationClassification() {
    MemoryStorage storage = MemoryStorage.create();
    store(storage).persistEventAndSnapshot(event(1), snapshot(1));
    AsyncEventStore<String, String> reader = nullRestoringReader(storage);

    CompletableFuture<?> result = assertDoesNotThrow(() -> reader.getLatestSnapshotById(AID));
    Throwable failure =
        EventStoreExceptions.unwrap(assertThrows(CompletionException.class, result::join));

    assertTrue(failure instanceof SerializationException);
    assertEquals(ErrorCategory.SERIALIZATION, ((SerializationException) failure).category());
    assertTrue(failure.getMessage().contains("deserialize"));
    assertTrue(failure.getMessage().contains("null"));
  }

  @Test
  void asyncFourOperationsUseTheExplicitlySharedStorage() {
    MemoryStorage storage = MemoryStorage.create();
    AsyncEventStore<String, String> async = MemoryEventStore.createAsync(storage, config());
    EventStore<String, String> sync = store(storage);

    async.persistEvent(event(1)).join();
    async.persistEventAndSnapshot(event(2), snapshot(2)).join();

    assertEquals(2, async.getLatestSnapshotById(AID).join().get().headSeqNr());
    assertEquals(
        "snapshot-2", async.getLatestSnapshotById(AID).join().get().snapshot().get().aggregate());
    assertEquals("event-2", async.getEventsByIdSinceSeqNr(AID, 2).join().get(0).payload());
    assertEquals(2, sync.getLatestSnapshotById(AID).get().headSeqNr());
  }

  @Test
  void asyncConflictIsAnExceptionalFutureWithTheSameClassificationAsSync() {
    MemoryStorage storage = MemoryStorage.create();
    EventStore<String, String> sync = store(storage);
    AsyncEventStore<String, String> async = MemoryEventStore.createAsync(storage, config());
    sync.persistEvent(event(1));
    OptimisticLockException direct =
        assertThrows(OptimisticLockException.class, () -> sync.persistEvent(event(1)));

    CompletableFuture<Void> result = assertDoesNotThrow(() -> async.persistEvent(event(1)));
    Throwable unwrapped =
        EventStoreExceptions.unwrap(assertThrows(CompletionException.class, result::join));

    assertTrue(unwrapped instanceof OptimisticLockException);
    assertEquals(direct.category(), ((EventStoreException) unwrapped).category());
    assertEquals(1, sync.getEventsByIdSinceSeqNr(AID, 0).size());
  }

  private static AsyncEventStore<String, String> nullRestoringReader(MemoryStorage storage) {
    PayloadSerializer<String> serializer =
        new PayloadSerializer<String>() {
          public byte[] serialize(String value) {
            return JsonPayloadSerializer.of(String.class).serialize(value);
          }

          public String deserialize(byte[] bytes) {
            return null;
          }
        };
    return MemoryEventStore.createAsync(
        storage,
        EventStoreConfig.<String, String>builder()
            .payloadSerializer(serializer)
            .snapshotSerializer(serializer)
            .build());
  }
}
