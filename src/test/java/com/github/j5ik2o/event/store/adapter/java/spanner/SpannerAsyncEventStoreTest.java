package com.github.j5ik2o.event.store.adapter.java.spanner;

import static com.github.j5ik2o.event.store.adapter.java.spanner.SpannerTestFixtures.AID;
import static com.github.j5ik2o.event.store.adapter.java.spanner.SpannerTestFixtures.EMULATOR;
import static com.github.j5ik2o.event.store.adapter.java.spanner.SpannerTestFixtures.config;
import static com.github.j5ik2o.event.store.adapter.java.spanner.SpannerTestFixtures.event;
import static com.github.j5ik2o.event.store.adapter.java.spanner.SpannerTestFixtures.noneStore;
import static com.github.j5ik2o.event.store.adapter.java.spanner.SpannerTestFixtures.open;
import static com.github.j5ik2o.event.store.adapter.java.spanner.SpannerTestFixtures.snapshot;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.github.j5ik2o.event.store.adapter.java.core.AsyncEventStore;
import com.github.j5ik2o.event.store.adapter.java.core.OptimisticLockException;
import java.util.List;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.Test;

class SpannerAsyncEventStoreTest {
  @Test
  void asyncAppendAndReadsUseTheSameStorageAsTheSyncStore() {
    String prefix = noneStore();
    AsyncEventStore<String, String> async =
        SpannerEventStore.createAsync(open(prefix, SpannerRetentionPolicy.none()), config());

    async.persistEvent(event(1)).join();
    async.persistEventAndSnapshot(event(2), snapshot(2)).join();

    assertEquals(2, async.getEventsByIdSinceSeqNr(AID, 0).join().size());
    assertEquals(2, async.getLatestSnapshotById(AID).join().orElseThrow().headSeqNr());
    assertEquals(List.of(2L), EMULATOR.longs("SELECT seq_nr FROM " + prefix + "_head"));
  }

  @Test
  void anOptimisticLockSurfacesThroughTheFuture() {
    String prefix = noneStore();
    AsyncEventStore<String, String> async =
        SpannerEventStore.createAsync(open(prefix, SpannerRetentionPolicy.none()), config());
    async.persistEvent(event(1)).join();

    CompletionException e =
        assertThrows(CompletionException.class, () -> async.persistEvent(event(1)).join());

    assertInstanceOf(OptimisticLockException.class, e.getCause());
  }
}
