package com.github.j5ik2o.event.store.adapter.java.sqlite;

import static com.github.j5ik2o.event.store.adapter.java.sqlite.SqliteTestFixtures.AID;
import static com.github.j5ik2o.event.store.adapter.java.sqlite.SqliteTestFixtures.count;
import static com.github.j5ik2o.event.store.adapter.java.sqlite.SqliteTestFixtures.event;
import static com.github.j5ik2o.event.store.adapter.java.sqlite.SqliteTestFixtures.initialized;
import static com.github.j5ik2o.event.store.adapter.java.sqlite.SqliteTestFixtures.longs;
import static com.github.j5ik2o.event.store.adapter.java.sqlite.SqliteTestFixtures.raw;
import static com.github.j5ik2o.event.store.adapter.java.sqlite.SqliteTestFixtures.settings;
import static com.github.j5ik2o.event.store.adapter.java.sqlite.SqliteTestFixtures.store;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.j5ik2o.event.store.adapter.java.core.AggregateId;
import com.github.j5ik2o.event.store.adapter.java.core.EventStore;
import com.github.j5ik2o.event.store.adapter.java.core.OptimisticLockException;
import com.github.j5ik2o.event.store.adapter.java.core.RetentionPolicy;
import com.github.j5ik2o.event.store.adapter.java.core.StorageException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteErrorCode;
import org.sqlite.SQLiteException;

class SqliteConcurrencyTest {
  @TempDir Path directory;

  @Test
  void theWriteLockIsAlreadyHeldInsideAppendBeforeTheHeadIsRead() {
    Path file = initialized(directory, RetentionPolicy.none());
    AtomicReference<Object> otherConnectionResult = new AtomicReference<>();
    SqliteStorageHooks hooks =
        new SqliteStorageHooks() {
          @Override
          public void afterBeginImmediate(AggregateId aggregateId) {
            try (Connection other = raw(file);
                Statement s = other.createStatement()) {
              s.execute("BEGIN IMMEDIATE");
              otherConnectionResult.set("acquired");
            } catch (SQLiteException e) {
              otherConnectionResult.set(e.getResultCode());
            } catch (SQLException e) {
              otherConnectionResult.set(e);
            }
          }
        };
    try (SqliteStorage storage =
        SqliteStorage.open(file, settings(RetentionPolicy.none()), hooks)) {
      store(storage).persistEvent(event(1));
    }

    assertEquals(SQLiteErrorCode.SQLITE_BUSY, otherConnectionResult.get());
    assertEquals(1, count(file, "journal"));
  }

  @Test
  void appendWaitsForAForeignWriteLockThenFailsAsStorageErrorAndCommitsNothing() throws Exception {
    Path file = initialized(directory, RetentionPolicy.none());
    try (Connection holder = raw(file);
        Statement hs = holder.createStatement()) {
      hs.execute("BEGIN IMMEDIATE");
      try (SqliteStorage storage =
          SqliteStorage.open(file, settings(RetentionPolicy.none(), Duration.ofMillis(200)))) {
        long start = System.nanoTime();

        StorageException e =
            assertThrows(StorageException.class, () -> store(storage).persistEvent(event(1)));

        long waitedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        assertTrue(waitedMillis >= 150, "must wait for the configured lock wait: " + waitedMillis);
        assertNotNull(e.getMessage());
      }
      hs.execute("ROLLBACK");
    }
    assertEquals(0, count(file, "journal"));
    assertEquals(0, count(file, "head"));
  }

  @Test
  void aWriterBlockedByAnotherStoreGetsAStorageErrorNotAnOptimisticLock() throws Exception {
    Path file = initialized(directory, RetentionPolicy.none());
    CountDownLatch inside = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    SqliteStorageHooks blocking =
        new SqliteStorageHooks() {
          @Override
          public void afterBeginImmediate(AggregateId aggregateId) {
            inside.countDown();
            await(release);
          }
        };
    ExecutorService executor = Executors.newSingleThreadExecutor();
    try (SqliteStorage first =
            SqliteStorage.open(file, settings(RetentionPolicy.none()), blocking);
        SqliteStorage second =
            SqliteStorage.open(file, settings(RetentionPolicy.none(), Duration.ofMillis(200)))) {
      Future<?> firstWrite = executor.submit(() -> store(first).persistEvent(event(1)));
      assertTrue(inside.await(10, TimeUnit.SECONDS));

      assertThrows(StorageException.class, () -> store(second).persistEvent(event(1)));

      release.countDown();
      firstWrite.get(10, TimeUnit.SECONDS);
    } finally {
      release.countDown();
      executor.shutdownNow();
    }
    assertEquals(List.of(1L), longs(file, "SELECT seq_nr FROM journal"));
  }

  @Test
  void concurrentStoresAppendingTheSameNextSequenceCommitExactlyOne() throws Exception {
    Path file = initialized(directory, RetentionPolicy.none());
    int writers = 4;
    List<SqliteStorage> storages = new ArrayList<>();
    for (int i = 0; i < writers; i++) {
      storages.add(SqliteStorage.open(file, settings(RetentionPolicy.none())));
    }
    ExecutorService executor = Executors.newFixedThreadPool(writers);
    CyclicBarrier barrier = new CyclicBarrier(writers);
    try {
      List<Future<Throwable>> results = new ArrayList<>();
      for (SqliteStorage storage : storages) {
        Callable<Throwable> task =
            () -> {
              barrier.await(10, TimeUnit.SECONDS);
              try {
                store(storage).persistEvent(event(1));
                return null;
              } catch (RuntimeException e) {
                return e;
              }
            };
        results.add(executor.submit(task));
      }
      int succeeded = 0;
      int conflicts = 0;
      for (Future<Throwable> result : results) {
        Throwable failure = result.get(30, TimeUnit.SECONDS);
        if (failure == null) {
          succeeded++;
        } else if (failure instanceof OptimisticLockException) {
          conflicts++;
        } else {
          throw new AssertionError("unexpected failure", failure);
        }
      }
      assertEquals(1, succeeded);
      assertEquals(writers - 1, conflicts);
    } finally {
      executor.shutdownNow();
      for (SqliteStorage storage : storages) {
        storage.close();
      }
    }
    assertEquals(List.of(1L), longs(file, "SELECT seq_nr FROM journal"));
  }

  @Test
  void concurrentStoresWritingDifferentAggregatesAllCommit() throws Exception {
    Path file = initialized(directory, RetentionPolicy.none());
    int writers = 4;
    List<SqliteStorage> storages = new ArrayList<>();
    for (int i = 0; i < writers; i++) {
      storages.add(SqliteStorage.open(file, settings(RetentionPolicy.none())));
    }
    ExecutorService executor = Executors.newFixedThreadPool(writers);
    try {
      List<Future<?>> results = new ArrayList<>();
      for (int i = 0; i < writers; i++) {
        SqliteStorage storage = storages.get(i);
        AggregateId id = AggregateId.of("Order", "w" + i);
        results.add(
            executor.submit(
                () -> {
                  EventStore<String, String> store = store(storage);
                  for (long seq = 1; seq <= 5; seq++) {
                    store.persistEvent(event(id, seq));
                  }
                }));
      }
      for (Future<?> result : results) {
        try {
          result.get(60, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
          throw new AssertionError(e.getCause());
        }
      }
    } finally {
      executor.shutdownNow();
      for (SqliteStorage storage : storages) {
        storage.close();
      }
    }
    assertEquals(20, count(file, "journal"));
    assertEquals(4, count(file, "head"));
    assertEquals(List.of(5L, 5L, 5L, 5L), longs(file, "SELECT seq_nr FROM head ORDER BY aid"));
  }

  @Test
  void aSecondStoreReadsWhatTheFirstCommittedWithoutReopening() {
    Path file = initialized(directory, RetentionPolicy.none());
    try (SqliteStorage first = SqliteStorage.open(file, settings(RetentionPolicy.none()));
        SqliteStorage second = SqliteStorage.open(file, settings(RetentionPolicy.none()))) {
      store(first).persistEvent(event(1));
      store(second).persistEvent(event(2));
      store(first).persistEvent(event(3));

      assertEquals(3, store(second).getEventsByIdSinceSeqNr(AID, 0).size());
      assertEquals(3, store(first).getEventsByIdSinceSeqNr(AID, 0).size());
    }
  }

  private static void await(CountDownLatch latch) {
    try {
      if (!latch.await(30, TimeUnit.SECONDS)) {
        throw new AssertionError("latch timed out");
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new AssertionError(e);
    }
  }
}
