package com.github.j5ik2o.event.store.adapter.java.bigtable;

import static com.github.j5ik2o.event.store.adapter.java.bigtable.BigtableTestFixtures.AID;
import static com.github.j5ik2o.event.store.adapter.java.bigtable.BigtableTestFixtures.EMULATOR;
import static com.github.j5ik2o.event.store.adapter.java.bigtable.BigtableTestFixtures.config;
import static com.github.j5ik2o.event.store.adapter.java.bigtable.BigtableTestFixtures.event;
import static com.github.j5ik2o.event.store.adapter.java.bigtable.BigtableTestFixtures.headSeqNr;
import static com.github.j5ik2o.event.store.adapter.java.bigtable.BigtableTestFixtures.noneStore;
import static com.github.j5ik2o.event.store.adapter.java.bigtable.BigtableTestFixtures.open;
import static com.github.j5ik2o.event.store.adapter.java.bigtable.BigtableTestFixtures.prefix;
import static com.github.j5ik2o.event.store.adapter.java.bigtable.BigtableTestFixtures.snapshot;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.github.j5ik2o.event.store.adapter.java.core.AsyncEventStore;
import com.github.j5ik2o.event.store.adapter.java.core.ContractViolationException;
import com.github.j5ik2o.event.store.adapter.java.core.OptimisticLockException;
import com.github.j5ik2o.event.store.adapter.java.core.RetentionPolicy;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.Test;

/** The asynchronous store uses the same storage and surfaces errors through the future. */
class BigtableAsyncEventStoreTest {
  @Test
  void asyncOperationsReadAndWriteTheSameStorageAsTheSynchronousStore() {
    BigtableEmulator.Tables t = noneStore();
    BigtableStorage storage = open(t, RetentionPolicy.none());
    AsyncEventStore<String, String> async = BigtableEventStore.createAsync(storage, config());

    async.persistEvent(event(1)).join();
    async.persistEventAndSnapshot(event(2), snapshot(2)).join();

    assertEquals(2, async.getEventsByIdSinceSeqNr(AID, 0).join().size());
    assertEquals(2, async.getLatestSnapshotById(AID).join().orElseThrow().headSeqNr());
    assertEquals(headSeqNr(2), EMULATOR.text(t.head, prefix("Order-1"), "head", "seq_nr"));
    assertEquals(
        2, BigtableEventStore.create(storage, config()).getEventsByIdSinceSeqNr(AID, 1).size());
  }

  @Test
  void anOptimisticLockSurfacesThroughTheFuture() {
    BigtableEmulator.Tables t = noneStore();
    AsyncEventStore<String, String> async =
        BigtableEventStore.createAsync(open(t, RetentionPolicy.none()), config());
    async.persistEvent(event(1)).join();

    CompletionException e =
        assertThrows(CompletionException.class, () -> async.persistEvent(event(1)).join());

    assertInstanceOf(OptimisticLockException.class, e.getCause());
  }

  @Test
  void aContractViolationSurfacesThroughTheFutureAndNothingIsWritten() {
    BigtableEmulator.Tables t = noneStore();
    AsyncEventStore<String, String> async =
        BigtableEventStore.createAsync(open(t, RetentionPolicy.none()), config());

    CompletionException e =
        assertThrows(CompletionException.class, () -> async.persistEvent(event(3)).join());

    assertInstanceOf(ContractViolationException.class, e.getCause());
    assertEquals(1, EMULATOR.rows(t.head).size(), "only the configuration row");
  }
}
