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
import com.github.j5ik2o.event.store.adapter.java.core.ConfigurationException;
import com.github.j5ik2o.event.store.adapter.java.core.ErrorCategory;
import com.github.j5ik2o.event.store.adapter.java.core.EventEnvelope;
import com.github.j5ik2o.event.store.adapter.java.core.EventStore;
import com.github.j5ik2o.event.store.adapter.java.core.EventStoreConfig;
import com.github.j5ik2o.event.store.adapter.java.core.EventStoreException;
import com.github.j5ik2o.event.store.adapter.java.core.EventStoreExceptions;
import com.github.j5ik2o.event.store.adapter.java.core.JsonPayloadSerializer;
import com.github.j5ik2o.event.store.adapter.java.core.OptimisticLockException;
import com.github.j5ik2o.event.store.adapter.java.core.PayloadSerializer;
import com.github.j5ik2o.event.store.adapter.java.core.SerializationException;
import com.github.j5ik2o.event.store.adapter.java.core.SnapshotReadResult;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
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

  @Test
  void nullStorageIsRejectedAsConfigurationErrorOnAsyncCreation() {
    ConfigurationException failure =
        assertThrows(
            ConfigurationException.class, () -> MemoryEventStore.createAsync(null, config()));

    assertEquals(ErrorCategory.CONFIGURATION, failure.category());
  }

  @Test
  void nullConfigIsRejectedAsConfigurationErrorOnAsyncCreation() {
    MemoryStorage storage = MemoryStorage.create();

    ConfigurationException failure =
        assertThrows(
            ConfigurationException.class, () -> MemoryEventStore.createAsync(storage, null));

    assertEquals(ErrorCategory.CONFIGURATION, failure.category());
  }

  @Test
  void concurrentAsyncReadsNeverObserveAPartiallyAppliedWrite() throws Exception {
    MemoryStorage storage = MemoryStorage.create();
    AsyncEventStore<String, String> writer = MemoryEventStore.createAsync(storage, config());
    AsyncEventStore<String, String> reader = MemoryEventStore.createAsync(storage, config());
    writer.persistEventAndSnapshot(event(1), snapshot(1)).join();
    CountDownLatch start = new CountDownLatch(1);
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      Future<?> writes =
          executor.submit(
              () -> {
                assertTrue(start.await(5, TimeUnit.SECONDS));
                for (long seqNr = 2; seqNr <= 101; seqNr++) {
                  writer.persistEventAndSnapshot(event(seqNr), snapshot(seqNr)).join();
                }
                return null;
              });
      Future<?> reads =
          executor.submit(
              () -> {
                assertTrue(start.await(5, TimeUnit.SECONDS));
                for (int i = 0; i < 100; i++) {
                  SnapshotReadResult<String> captured =
                      reader.getLatestSnapshotById(AID).join().get();
                  assertEquals(captured.headSeqNr(), captured.snapshot().get().seqNr());
                  assertEquals(
                      "snapshot-" + captured.headSeqNr(), captured.snapshot().get().aggregate());
                  List<EventEnvelope<String>> events =
                      reader.getEventsByIdSinceSeqNr(AID, 0).join();
                  for (int index = 0; index < events.size(); index++) {
                    assertEquals(index + 1L, events.get(index).seqNr());
                  }
                }
                return null;
              });

      start.countDown();
      writes.get(5, TimeUnit.SECONDS);
      reads.get(5, TimeUnit.SECONDS);

      assertEquals(101, reader.getLatestSnapshotById(AID).join().get().headSeqNr());
      assertEquals(101, reader.getEventsByIdSinceSeqNr(AID, 0).join().size());
    } finally {
      start.countDown();
      executor.shutdownNow();
    }
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
