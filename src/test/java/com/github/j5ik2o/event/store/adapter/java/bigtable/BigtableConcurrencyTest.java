package com.github.j5ik2o.event.store.adapter.java.bigtable;

import static com.github.j5ik2o.event.store.adapter.java.bigtable.BigtableTestFixtures.AID;
import static com.github.j5ik2o.event.store.adapter.java.bigtable.BigtableTestFixtures.noneStore;
import static com.github.j5ik2o.event.store.adapter.java.bigtable.BigtableTestFixtures.open;
import static com.github.j5ik2o.event.store.adapter.java.bigtable.BigtableTestFixtures.store;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.j5ik2o.event.store.adapter.java.core.AggregateId;
import com.github.j5ik2o.event.store.adapter.java.core.EventEnvelope;
import com.github.j5ik2o.event.store.adapter.java.core.EventStore;
import com.github.j5ik2o.event.store.adapter.java.core.OptimisticLockException;
import com.github.j5ik2o.event.store.adapter.java.core.RetentionPolicy;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** BT-7 / BT-5: only one of the writers that read the same h can move the head. */
class BigtableConcurrencyTest {
  private static EventEnvelope<String> eventWithPayload(long seqNr, String payload) {
    return EventEnvelope.<String>builder()
        .aggregateId(AID)
        .seqNr(seqNr)
        .occurredAt(Instant.EPOCH)
        .manifest("event")
        .payload(payload)
        .build();
  }

  @Test
  void ofTwoWritersThatReadTheSameHeadExactlyTheFirstToCommitWins() throws Exception {
    BigtableEmulator.Tables t = noneStore();
    store(open(t, RetentionPolicy.none())).persistEvent(eventWithPayload(1, "base"));
    CountDownLatch bothRead = new CountDownLatch(2);
    BigtableStorageHooks meet =
        new BigtableStorageHooks() {
          @Override
          public void beforeCommit(AggregateId aggregateId, long seqNr) {
            bothRead.countDown();
            try {
              assertTrue(bothRead.await(30, TimeUnit.SECONDS), "both writers reached the commit");
            } catch (InterruptedException e) {
              throw new IllegalStateException(e);
            }
          }
        };
    EventStore<String, String> a = store(open(t, RetentionPolicy.none(), meet));
    EventStore<String, String> b = store(open(t, RetentionPolicy.none(), meet));
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      Future<Throwable> fa =
          pool.submit(outcome(() -> a.persistEvent(eventWithPayload(2, "from-a"))));
      Future<Throwable> fb =
          pool.submit(outcome(() -> b.persistEvent(eventWithPayload(2, "from-b"))));
      Throwable ra = fa.get(60, TimeUnit.SECONDS);
      Throwable rb = fb.get(60, TimeUnit.SECONDS);

      int winners = (ra == null ? 1 : 0) + (rb == null ? 1 : 0);
      assertEquals(1, winners, "exactly one writer commits: " + ra + " / " + rb);
      Throwable loser = ra != null ? ra : rb;
      assertInstanceOf(OptimisticLockException.class, loser);
      String winnerPayload = ra == null ? "from-a" : "from-b";
      List<EventEnvelope<String>> events = a.getEventsByIdSinceSeqNr(AID, 1);
      assertEquals(2, events.size());
      assertEquals(winnerPayload, events.get(1).payload());
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void concurrentCreationOfSequenceNumberOneLeavesExactlyOneEvent() throws Exception {
    BigtableEmulator.Tables t = noneStore();
    int writers = 8;
    CountDownLatch go = new CountDownLatch(1);
    ExecutorService pool = Executors.newFixedThreadPool(writers);
    try {
      List<Future<Throwable>> results = new ArrayList<>();
      for (int i = 0; i < writers; i++) {
        String payload = "writer-" + i;
        EventStore<String, String> store = store(open(t, RetentionPolicy.none()));
        results.add(
            pool.submit(
                outcome(
                    () -> {
                      go.await();
                      store.persistEvent(eventWithPayload(1, payload));
                    })));
      }
      go.countDown();
      int wins = 0;
      for (Future<Throwable> f : results) {
        Throwable r = f.get(120, TimeUnit.SECONDS);
        if (r == null) wins++;
        else assertInstanceOf(OptimisticLockException.class, r);
      }

      assertEquals(1, wins);
      List<EventEnvelope<String>> events =
          store(open(t, RetentionPolicy.none())).getEventsByIdSinceSeqNr(AID, 1);
      assertEquals(1, events.size());
      assertTrue(events.get(0).payload().startsWith("writer-"));
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void aSecondWriterRunningInsideTheFirstWritersWriteOutWindowMakesTheFirstWriterLose() {
    BigtableEmulator.Tables t = noneStore();
    EventStore<String, String> fast = store(open(t, RetentionPolicy.none()));
    fast.persistEvent(eventWithPayload(1, "base"));
    boolean[] ran = {false};
    BigtableStorageHooks interleave =
        new BigtableStorageHooks() {
          @Override
          public void journalWritePrevious(AggregateId aggregateId, long seqNr) {
            if (ran[0]) return;
            ran[0] = true;
            fast.persistEvent(eventWithPayload(2, "fast"));
          }
        };
    EventStore<String, String> slow = store(open(t, RetentionPolicy.none(), interleave));

    try {
      slow.persistEvent(eventWithPayload(2, "slow"));
      throw new AssertionError("the slow writer must lose");
    } catch (OptimisticLockException expected) {
      // the head moved between the write-out and the conditional update
    }

    assertTrue(ran[0]);
    List<EventEnvelope<String>> events = fast.getEventsByIdSinceSeqNr(AID, 1);
    assertEquals(2, events.size());
    assertEquals("fast", events.get(1).payload());
  }

  private static Callable<Throwable> outcome(ThrowingRunnable action) {
    return () -> {
      try {
        action.run();
        return null;
      } catch (Throwable failure) {
        return failure;
      }
    };
  }

  private interface ThrowingRunnable {
    void run() throws Exception;
  }
}
