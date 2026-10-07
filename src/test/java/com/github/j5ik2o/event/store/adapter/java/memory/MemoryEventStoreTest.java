package com.github.j5ik2o.event.store.adapter.java.memory;

import static com.github.j5ik2o.event.store.adapter.java.memory.MemoryTestFixtures.AID;
import static com.github.j5ik2o.event.store.adapter.java.memory.MemoryTestFixtures.event;
import static com.github.j5ik2o.event.store.adapter.java.memory.MemoryTestFixtures.snapshot;
import static com.github.j5ik2o.event.store.adapter.java.memory.MemoryTestFixtures.store;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.j5ik2o.event.store.adapter.java.core.AggregateId;
import com.github.j5ik2o.event.store.adapter.java.core.ContractViolationException;
import com.github.j5ik2o.event.store.adapter.java.core.ErrorCategory;
import com.github.j5ik2o.event.store.adapter.java.core.EventEnvelope;
import com.github.j5ik2o.event.store.adapter.java.core.EventStore;
import com.github.j5ik2o.event.store.adapter.java.core.EventStoreConfig;
import com.github.j5ik2o.event.store.adapter.java.core.JsonPayloadSerializer;
import com.github.j5ik2o.event.store.adapter.java.core.OptimisticLockException;
import com.github.j5ik2o.event.store.adapter.java.core.PayloadSerializer;
import com.github.j5ik2o.event.store.adapter.java.core.RetentionPolicy;
import com.github.j5ik2o.event.store.adapter.java.core.SerializationException;
import com.github.j5ik2o.event.store.adapter.java.core.SnapshotReadResult;
import com.github.j5ik2o.event.store.adapter.java.core.StorageException;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class MemoryEventStoreTest {
  @Test
  void failureImmediatelyBeforeCommitLeavesAllPreviouslyPublishedStateUnchanged() {
    CommitFailure hooks = new CommitFailure();
    MemoryStorage storage = MemoryStorage.create(RetentionPolicy.delete(2), hooks);
    EventStore<String, String> store = store(storage);
    store.persistEventAndSnapshot(event(1), snapshot(1));
    hooks.fail = true;

    StorageException failure =
        assertThrows(
            StorageException.class, () -> store.persistEventAndSnapshot(event(2), snapshot(2)));

    assertEquals(ErrorCategory.STORAGE, failure.category());
    assertEquals(1, store.getLatestSnapshotById(AID).get().headSeqNr());
    assertEquals("snapshot-1", store.getLatestSnapshotById(AID).get().snapshot().get().aggregate());
    assertEquals(List.of(1L), storage.historySeqNrs(AID));
    assertEquals(
        List.of(1L),
        store.getEventsByIdSinceSeqNr(AID, 0).stream()
            .map(EventEnvelope::seqNr)
            .collect(Collectors.toList()));
    hooks.fail = false;
    assertDoesNotThrow(() -> store.persistEventAndSnapshot(event(2), snapshot(2)));
  }

  @Test
  void snapshotSerializationFailureDoesNotPublishEventOrHead() {
    MemoryStorage storage = MemoryStorage.create();
    PayloadSerializer<String> failing =
        new PayloadSerializer<String>() {
          public byte[] serialize(String value) {
            throw new SerializationException("snapshot failure");
          }

          public String deserialize(byte[] bytes) {
            return JsonPayloadSerializer.of(String.class).deserialize(bytes);
          }
        };
    EventStore<String, String> writer =
        MemoryEventStore.create(
            storage,
            EventStoreConfig.<String, String>builder()
                .payloadSerializer(JsonPayloadSerializer.of(String.class))
                .snapshotSerializer(failing)
                .build());
    EventStore<String, String> reader = store(storage);

    SerializationException failure =
        assertThrows(
            SerializationException.class,
            () -> writer.persistEventAndSnapshot(event(1), snapshot(1)));

    assertEquals(ErrorCategory.SERIALIZATION, failure.category());
    assertTrue(reader.getLatestSnapshotById(AID).isEmpty());
    assertTrue(reader.getEventsByIdSinceSeqNr(AID, 0).isEmpty());
    assertDoesNotThrow(() -> reader.persistEvent(event(1)));
  }

  @Test
  void firstEventWithoutSnapshotCreatesHeadAndAllowsNextEvent() {
    EventStore<String, String> store = store(MemoryStorage.create());

    store.persistEvent(event(1));
    store.persistEvent(event(2));

    SnapshotReadResult<String> result = store.getLatestSnapshotById(AID).get();
    assertEquals(2, result.headSeqNr());
    assertTrue(result.snapshot().isEmpty());
    assertEquals(
        List.of(1L, 2L),
        store.getEventsByIdSinceSeqNr(AID, 0).stream()
            .map(EventEnvelope::seqNr)
            .collect(Collectors.toList()));
  }

  @Test
  void gapIsRejectedWithoutChangingJournalHeadOrSnapshot() {
    EventStore<String, String> store = store(MemoryStorage.create());
    store.persistEventAndSnapshot(event(1), snapshot(1));

    ContractViolationException failure =
        assertThrows(
            ContractViolationException.class,
            () -> store.persistEventAndSnapshot(event(7), snapshot(7)));

    assertEquals("W-8", failure.rule());
    assertEquals(1, store.getLatestSnapshotById(AID).get().headSeqNr());
    assertEquals(1, store.getLatestSnapshotById(AID).get().snapshot().get().seqNr());
    assertEquals(1, store.getEventsByIdSinceSeqNr(AID, 0).size());
  }

  @Test
  void mismatchIsRejectedBeforePublishingEitherEnvelope() {
    EventStore<String, String> store = store(MemoryStorage.create());

    ContractViolationException failure =
        assertThrows(
            ContractViolationException.class,
            () -> store.persistEventAndSnapshot(event(1), snapshot(0)));

    assertEquals("W-9", failure.rule());
    assertTrue(store.getLatestSnapshotById(AID).isEmpty());
    assertTrue(store.getEventsByIdSinceSeqNr(AID, 0).isEmpty());
    assertDoesNotThrow(() -> store.persistEvent(event(1)));
  }

  @Test
  void concurrentSameNumberCommitsExactlyOnceAcrossSharedStores() throws Exception {
    MemoryStorage storage = MemoryStorage.create();
    EventStore<String, String> first = store(storage);
    EventStore<String, String> second = store(storage);
    CountDownLatch start = new CountDownLatch(1);
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      Future<Boolean> a = executor.submit(() -> appendAfter(start, first));
      Future<Boolean> b = executor.submit(() -> appendAfter(start, second));

      start.countDown();

      assertNotEquals(a.get(5, TimeUnit.SECONDS), b.get(5, TimeUnit.SECONDS));
      assertEquals(1, first.getLatestSnapshotById(AID).get().headSeqNr());
      assertEquals(1, first.getEventsByIdSinceSeqNr(AID, 0).size());
    } finally {
      start.countDown();
      executor.shutdownNow();
    }
  }

  private static boolean appendAfter(CountDownLatch start, EventStore<String, String> store)
      throws InterruptedException {
    assertTrue(start.await(5, TimeUnit.SECONDS));
    try {
      store.persistEvent(event(1));
      return true;
    } catch (OptimisticLockException expected) {
      assertEquals(ErrorCategory.OPTIMISTIC_LOCK, expected.category());
      return false;
    }
  }

  private static final class CommitFailure implements MemoryStorageHooks {
    boolean fail;

    public void beforeCommit(AggregateId aggregateId) {
      if (fail) {
        throw new StorageException("commit failure");
      }
    }

    public void beforeReadEvents(AggregateId aggregateId) {}

    public void beforeReadSnapshot(AggregateId aggregateId) {}

    public List<Long> readHistory(AggregateId aggregateId, List<Long> actualHistory) {
      return actualHistory;
    }

    public void beforeRetentionDelete(AggregateId aggregateId, List<Long> seqNrs) {}
  }
}
