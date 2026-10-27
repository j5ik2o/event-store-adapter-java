package com.github.j5ik2o.event.store.adapter.java.spanner;

import static com.github.j5ik2o.event.store.adapter.java.spanner.SpannerTestFixtures.EMULATOR;
import static com.github.j5ik2o.event.store.adapter.java.spanner.SpannerTestFixtures.event;
import static com.github.j5ik2o.event.store.adapter.java.spanner.SpannerTestFixtures.noneStore;
import static com.github.j5ik2o.event.store.adapter.java.spanner.SpannerTestFixtures.store;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.j5ik2o.event.store.adapter.java.core.AggregateId;
import com.github.j5ik2o.event.store.adapter.java.core.EventStore;
import com.github.j5ik2o.event.store.adapter.java.core.OptimisticLockException;
import com.github.j5ik2o.event.store.adapter.java.core.StorageException;
import com.google.cloud.spanner.ErrorCode;
import com.google.cloud.spanner.SpannerExceptionFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class SpannerTransactionRetryTest {
  private static SpannerStorage openWithRetryLimit(
      String prefix, int limit, SpannerStorageHooks hooks) {
    return SpannerStorage.open(
        EMULATOR.client(),
        SpannerStorageConfig.builder().tablePrefix(prefix).transactionRetryLimit(limit).build(),
        hooks);
  }

  /** Aborts the first {@code aborts} commit attempts and counts every attempt. */
  private static final class AbortingHooks implements SpannerStorageHooks {
    final AtomicInteger attempts = new AtomicInteger();
    private final int aborts;

    AbortingHooks(int aborts) {
      this.aborts = aborts;
    }

    @Override
    public void beforeCommit(AggregateId aggregateId) {
      if (attempts.incrementAndGet() <= aborts) {
        throw SpannerExceptionFactory.newSpannerException(ErrorCode.ABORTED, "injected abort");
      }
    }
  }

  @Test
  void abortsWithinTheLimitAreRetriedAndExactlyOneEventIsStored() {
    String prefix = noneStore();
    AbortingHooks hooks = new AbortingHooks(2);

    store(openWithRetryLimit(prefix, 5, hooks)).persistEvent(event(1));

    assertEquals(3, hooks.attempts.get());
    assertEquals(1, EMULATOR.count(prefix, "journal"));
    assertEquals(1, EMULATOR.count(prefix, "head"));
  }

  @Test
  void theFirstAttemptIsNotCountedAgainstTheLimit() {
    String prefix = noneStore();
    AbortingHooks hooks = new AbortingHooks(3);

    store(openWithRetryLimit(prefix, 3, hooks)).persistEvent(event(1));

    assertEquals(4, hooks.attempts.get());
    assertEquals(1, EMULATOR.count(prefix, "journal"));
  }

  @Test
  void aboveTheLimitTheAppendFailsWithAStorageErrorAndStoresNothing() {
    String prefix = noneStore();
    AbortingHooks hooks = new AbortingHooks(Integer.MAX_VALUE);

    assertThrows(
        StorageException.class,
        () -> store(openWithRetryLimit(prefix, 2, hooks)).persistEvent(event(1)));

    assertEquals(3, hooks.attempts.get());
    for (String table : List.of("journal", "head", "snapshot_current", "snapshot_history")) {
      assertEquals(0, EMULATOR.count(prefix, table), table);
    }
  }

  @Test
  void aZeroRetryLimitAttemptsOnceAndFailsOnTheFirstAbort() {
    String prefix = noneStore();
    AbortingHooks hooks = new AbortingHooks(1);

    assertThrows(
        StorageException.class,
        () -> store(openWithRetryLimit(prefix, 0, hooks)).persistEvent(event(1)));

    assertEquals(1, hooks.attempts.get());
  }

  @Test
  void aLostCommitResponseIsAStorageErrorAndNoCompensatingWriteHappens() {
    String prefix = noneStore();
    SpannerStorageHooks hooks =
        new SpannerStorageHooks() {
          @Override
          public void afterCommit(AggregateId aggregateId) {
            throw SpannerExceptionFactory.newSpannerException(
                ErrorCode.UNAVAILABLE, "injected response loss");
          }
        };
    SpannerStorage lossy = openWithRetryLimit(prefix, 5, hooks);

    assertThrows(StorageException.class, () -> store(lossy).persistEvent(event(1)));

    // The commit did happen; nothing removed it, and a resend is a conflict rather than a
    // duplicate.
    assertEquals(1, EMULATOR.count(prefix, "journal"));
    EventStore<String, String> healthy =
        store(openWithRetryLimit(prefix, 5, SpannerStorageHooks.NONE));
    assertThrows(OptimisticLockException.class, () -> healthy.persistEvent(event(1)));
    assertEquals(1, EMULATOR.count(prefix, "journal"));
  }

  @Test
  void concurrentFirstAppendsOfSequenceOneStoreExactlyOneEvent() throws Exception {
    String prefix = noneStore();
    int writers = 4;
    ExecutorService pool = Executors.newFixedThreadPool(writers);
    CountDownLatch start = new CountDownLatch(1);
    try {
      List<Future<Boolean>> results = new ArrayList<>();
      for (int i = 0; i < writers; i++) {
        EventStore<String, String> store =
            store(openWithRetryLimit(prefix, 10, SpannerStorageHooks.NONE));
        Callable<Boolean> task =
            () -> {
              start.await();
              try {
                store.persistEvent(event(1));
                return true;
              } catch (OptimisticLockException conflict) {
                return false;
              }
            };
        results.add(pool.submit(task));
      }
      start.countDown();
      int successes = 0;
      for (Future<Boolean> result : results) {
        if (result.get()) successes++;
      }

      assertEquals(1, successes);
      assertEquals(1, EMULATOR.count(prefix, "journal"));
      assertTrue(EMULATOR.longs("SELECT seq_nr FROM " + prefix + "_head").contains(1L));
    } finally {
      pool.shutdownNow();
    }
  }
}
