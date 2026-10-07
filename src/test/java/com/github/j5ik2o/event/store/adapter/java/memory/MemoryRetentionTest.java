package com.github.j5ik2o.event.store.adapter.java.memory;

import static com.github.j5ik2o.event.store.adapter.java.memory.MemoryTestFixtures.AID;
import static com.github.j5ik2o.event.store.adapter.java.memory.MemoryTestFixtures.event;
import static com.github.j5ik2o.event.store.adapter.java.memory.MemoryTestFixtures.snapshot;
import static com.github.j5ik2o.event.store.adapter.java.memory.MemoryTestFixtures.store;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import com.github.j5ik2o.event.store.adapter.java.core.AggregateId;
import com.github.j5ik2o.event.store.adapter.java.core.AsyncEventStore;
import com.github.j5ik2o.event.store.adapter.java.core.EventStore;
import com.github.j5ik2o.event.store.adapter.java.core.EventStoreConfig;
import com.github.j5ik2o.event.store.adapter.java.core.JsonPayloadSerializer;
import com.github.j5ik2o.event.store.adapter.java.core.RetentionFailure;
import com.github.j5ik2o.event.store.adapter.java.core.RetentionFailureListener;
import com.github.j5ik2o.event.store.adapter.java.core.RetentionMode;
import com.github.j5ik2o.event.store.adapter.java.core.RetentionPolicy;
import com.github.j5ik2o.event.store.adapter.java.core.StorageException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class MemoryRetentionTest {
  @Test
  void retentionFailureIsLoggedAfterUnlockWithoutChangingWriteResult() {
    DeleteFailure hooks = new DeleteFailure();
    MemoryStorage storage = MemoryStorage.create(RetentionPolicy.delete(1), hooks);
    EventStore<String, String> store = store(storage);
    store.persistEventAndSnapshot(event(1), snapshot(1));
    ExecutorService executor = Executors.newSingleThreadExecutor();
    List<Long> observedHeads = new ArrayList<>();
    Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
    AppenderBase<ILoggingEvent> appender =
        new AppenderBase<ILoggingEvent>() {
          protected void append(ILoggingEvent log) {
            if (log.getLevel() == Level.WARN
                && log.getLoggerName()
                    .startsWith("com.github.j5ik2o.event.store.adapter.java.memory")) {
              try {
                observedHeads.add(
                    executor
                        .submit(() -> store.getLatestSnapshotById(AID).get().headSeqNr())
                        .get(5, TimeUnit.SECONDS));
              } catch (Exception e) {
                addError("storage is inaccessible from the logging callback", e);
              }
            }
          }
        };
    appender.setContext(root.getLoggerContext());
    appender.start();
    root.addAppender(appender);
    hooks.fail = true;
    try {
      assertDoesNotThrow(() -> store.persistEventAndSnapshot(event(2), snapshot(2)));

      assertTrue(!observedHeads.isEmpty());
      assertTrue(observedHeads.stream().allMatch(head -> head == 2L));
      assertEquals(2, store.getLatestSnapshotById(AID).get().headSeqNr());
    } finally {
      root.detachAppender(appender);
      appender.stop();
      executor.shutdownNow();
    }
  }

  @Test
  void sharedStoresUseStorageRetentionAndKeepJournalAndHead() {
    MemoryStorage storage = MemoryStorage.create(RetentionPolicy.delete(2));
    EventStore<String, String> first = store(storage);
    EventStore<String, String> second = store(storage);

    first.persistEventAndSnapshot(event(1), snapshot(1));
    second.persistEventAndSnapshot(event(2), snapshot(2));
    first.persistEventAndSnapshot(event(3), snapshot(3));

    assertEquals(List.of(3L, 2L), storage.historySeqNrs(AID));
    assertEquals(3, second.getLatestSnapshotById(AID).get().headSeqNr());
    assertEquals(3, second.getEventsByIdSinceSeqNr(AID, 0).size());
  }

  @Test
  void defaultPolicyKeepsCurrentSnapshotWithoutHistory() {
    MemoryStorage storage = MemoryStorage.create();
    EventStore<String, String> store = store(storage);

    store.persistEventAndSnapshot(event(1), snapshot(1));
    store.persistEventAndSnapshot(event(2), snapshot(2));

    assertTrue(storage.historySeqNrs(AID).isEmpty());
    assertEquals("snapshot-2", store.getLatestSnapshotById(AID).get().snapshot().get().aggregate());
  }

  @Test
  void failedRetentionKeepsCommitNotifiesOnlyWriterAndEventOnlyAppendCleansUp() {
    DeleteFailure hooks = new DeleteFailure();
    MemoryStorage storage = MemoryStorage.create(RetentionPolicy.delete(1), hooks);
    List<RetentionFailure> writerNotifications = new ArrayList<>();
    List<RetentionFailure> otherNotifications = new ArrayList<>();
    EventStore<String, String> writer =
        MemoryEventStore.create(storage, configuration(writerNotifications::add));
    EventStore<String, String> other =
        MemoryEventStore.create(storage, configuration(otherNotifications::add));
    writer.persistEventAndSnapshot(event(1), snapshot(1));
    hooks.fail = true;

    assertDoesNotThrow(() -> writer.persistEventAndSnapshot(event(2), snapshot(2)));

    assertEquals(2, other.getLatestSnapshotById(AID).get().headSeqNr());
    assertEquals("snapshot-2", other.getLatestSnapshotById(AID).get().snapshot().get().aggregate());
    assertEquals(2, other.getEventsByIdSinceSeqNr(AID, 0).size());
    assertEquals(List.of(2L, 1L), storage.historySeqNrs(AID));
    assertEquals(1, writerNotifications.size());
    assertEquals(AID, writerNotifications.get(0).aggregateId());
    assertEquals(RetentionMode.DELETE, writerNotifications.get(0).mode());
    assertTrue(otherNotifications.isEmpty());
    hooks.fail = false;
    other.persistEvent(event(3));
    assertEquals(List.of(2L), storage.historySeqNrs(AID));
    assertEquals(3, other.getLatestSnapshotById(AID).get().headSeqNr());
  }

  @Test
  void listenerCanReadFromAnotherThreadAndItsExceptionDoesNotFailAsyncWrite() {
    DeleteFailure hooks = new DeleteFailure();
    MemoryStorage storage = MemoryStorage.create(RetentionPolicy.delete(1), hooks);
    EventStore<String, String> reader = store(storage);
    reader.persistEventAndSnapshot(event(1), snapshot(1));
    ExecutorService executor = Executors.newSingleThreadExecutor();
    List<Long> observedHeads = new ArrayList<>();
    AsyncEventStore<String, String> writer =
        MemoryEventStore.createAsync(
            storage,
            configuration(
                failure -> {
                  try {
                    observedHeads.add(
                        executor
                            .submit(() -> reader.getLatestSnapshotById(AID).get().headSeqNr())
                            .get(5, TimeUnit.SECONDS));
                  } catch (Exception e) {
                    throw new AssertionError(
                        "listener must run after the storage lock is released", e);
                  }
                  throw new IllegalStateException("listener failure");
                }));
    hooks.fail = true;
    try {
      CompletableFuture<Void> result = writer.persistEventAndSnapshot(event(2), snapshot(2));

      assertDoesNotThrow(result::join);
      assertEquals(List.of(2L), observedHeads);
      assertEquals(2, reader.getEventsByIdSinceSeqNr(AID, 0).size());
    } finally {
      executor.shutdownNow();
    }
  }

  private static EventStoreConfig<String, String> configuration(RetentionFailureListener listener) {
    return EventStoreConfig.<String, String>builder()
        .payloadSerializer(JsonPayloadSerializer.of(String.class))
        .snapshotSerializer(JsonPayloadSerializer.of(String.class))
        .retentionFailureListener(listener)
        .build();
  }

  private static final class DeleteFailure implements MemoryStorageHooks {
    boolean fail;

    public void beforeCommit(AggregateId aggregateId) {}

    public void beforeReadEvents(AggregateId aggregateId) {}

    public void beforeReadSnapshot(AggregateId aggregateId) {}

    public List<Long> readHistory(AggregateId aggregateId, List<Long> actualHistory) {
      return actualHistory;
    }

    public void beforeRetentionDelete(AggregateId aggregateId, List<Long> seqNrs) {
      if (fail) {
        throw new StorageException("retention failure");
      }
    }
  }
}
