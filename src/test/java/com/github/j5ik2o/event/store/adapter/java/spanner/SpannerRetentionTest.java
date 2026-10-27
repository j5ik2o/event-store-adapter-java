package com.github.j5ik2o.event.store.adapter.java.spanner;

import static com.github.j5ik2o.event.store.adapter.java.spanner.SpannerTestFixtures.AID;
import static com.github.j5ik2o.event.store.adapter.java.spanner.SpannerTestFixtures.EMULATOR;
import static com.github.j5ik2o.event.store.adapter.java.spanner.SpannerTestFixtures.deleteStore;
import static com.github.j5ik2o.event.store.adapter.java.spanner.SpannerTestFixtures.event;
import static com.github.j5ik2o.event.store.adapter.java.spanner.SpannerTestFixtures.hashPrefixLiteral;
import static com.github.j5ik2o.event.store.adapter.java.spanner.SpannerTestFixtures.open;
import static com.github.j5ik2o.event.store.adapter.java.spanner.SpannerTestFixtures.snapshot;
import static com.github.j5ik2o.event.store.adapter.java.spanner.SpannerTestFixtures.store;
import static com.github.j5ik2o.event.store.adapter.java.spanner.SpannerTestFixtures.ttlStore;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.j5ik2o.event.store.adapter.java.core.AggregateId;
import com.github.j5ik2o.event.store.adapter.java.core.EventStore;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class SpannerRetentionTest {
  private static List<Long> unmarked(String prefix) {
    return EMULATOR.longs(
        "SELECT seq_nr FROM "
            + prefix
            + "_snapshot_history WHERE expired_at IS NULL ORDER BY seq_nr");
  }

  private static List<Long> marked(String prefix) {
    return EMULATOR.longs(
        "SELECT seq_nr FROM "
            + prefix
            + "_snapshot_history WHERE expired_at IS NOT NULL ORDER BY seq_nr");
  }

  @Test
  void deleteModeKeepsTheNewestRowsAndDeletesOlderHistoryOnly() {
    String prefix = deleteStore(2);
    EventStore<String, String> store = store(open(prefix, SpannerRetentionPolicy.delete(2)));
    for (int i = 1; i <= 4; i++) store.persistEventAndSnapshot(event(i), snapshot(i));

    assertEquals(
        List.of(3L, 4L),
        EMULATOR.longs("SELECT seq_nr FROM " + prefix + "_snapshot_history ORDER BY seq_nr"));
    assertEquals(4, EMULATOR.count(prefix, "journal"));
    assertEquals(List.of(4L), EMULATOR.longs("SELECT seq_nr FROM " + prefix + "_head"));
    assertEquals(List.of(4L), EMULATOR.longs("SELECT seq_nr FROM " + prefix + "_snapshot_current"));
  }

  @Test
  void retentionOfOneAggregateNeverTouchesAnother() {
    String prefix = deleteStore(1);
    AggregateId other = AggregateId.of("Order", "2");
    EventStore<String, String> store = store(open(prefix, SpannerRetentionPolicy.delete(1)));
    store.persistEventAndSnapshot(event(other, 1), snapshot(1));
    for (int i = 1; i <= 3; i++) store.persistEventAndSnapshot(event(i), snapshot(i));

    assertEquals(
        List.of(1L),
        EMULATOR.longs("SELECT seq_nr FROM " + prefix + "_snapshot_history WHERE aid = 'Order-2'"));
    assertEquals(
        List.of(3L),
        EMULATOR.longs("SELECT seq_nr FROM " + prefix + "_snapshot_history WHERE aid = 'Order-1'"));
  }

  @Test
  void ttlModeMarksOldRowsInsteadOfDeletingThem() {
    String prefix = ttlStore(2, 7);
    EventStore<String, String> store = store(open(prefix, SpannerRetentionPolicy.ttl(2, 7)));
    for (int i = 1; i <= 4; i++) store.persistEventAndSnapshot(event(i), snapshot(i));

    assertEquals(List.of(3L, 4L), unmarked(prefix));
    assertEquals(List.of(1L, 2L), marked(prefix));
    assertEquals(4, EMULATOR.count(prefix, "journal"));
    assertEquals(List.of(4L), EMULATOR.longs("SELECT seq_nr FROM " + prefix + "_head"));
  }

  @Test
  void ttlModeMarksARowOnlyOnceAndKeepsItsExpiryTime() {
    String prefix = ttlStore(1, 7);
    EventStore<String, String> store = store(open(prefix, SpannerRetentionPolicy.ttl(1, 7)));
    store.persistEventAndSnapshot(event(1), snapshot(1));
    store.persistEventAndSnapshot(event(2), snapshot(2));
    String firstMark =
        EMULATOR.string(
            "SELECT CAST(expired_at AS STRING) FROM "
                + prefix
                + "_snapshot_history WHERE seq_nr = 1");

    store.persistEventAndSnapshot(event(3), snapshot(3));

    assertEquals(
        firstMark,
        EMULATOR.string(
            "SELECT CAST(expired_at AS STRING) FROM "
                + prefix
                + "_snapshot_history WHERE seq_nr = 1"));
    assertEquals(List.of(1L, 2L), marked(prefix));
    assertEquals(List.of(3L), unmarked(prefix));
  }

  @Test
  void aLargeCleanUpIsSplitIntoSeveralTransactionsAndStillKeepsTheNewestRows() {
    String prefix = deleteStore(3);
    String key = hashPrefixLiteral(AID);
    EMULATOR.execute(
        "INSERT INTO "
            + prefix
            + "_snapshot_history (hash_prefix, aid, seq_nr, manifest, payload) SELECT "
            + key
            + ", 'Order-1', n, 'snapshot', b'x' FROM UNNEST(GENERATE_ARRAY(1, 1200)) AS n");
    EMULATOR.execute(
        "INSERT INTO "
            + prefix
            + "_head (hash_prefix, aid, type_name, seq_nr, event_occurred_at_ns, event_manifest,"
            + " event_payload) VALUES ("
            + key
            + ", 'Order-1', 'Order', 1200, 0, 'event', b'x')");
    List<Integer> batchSizes = new ArrayList<>();
    SpannerStorageHooks hooks =
        new SpannerStorageHooks() {
          @Override
          public void beforeRetentionDelete(AggregateId aggregateId, List<Long> seqNrs) {
            batchSizes.add(seqNrs.size());
          }
        };

    store(open(prefix, SpannerRetentionPolicy.delete(3), hooks))
        .persistEventAndSnapshot(event(1201), snapshot(1201));

    assertEquals(List.of(1199L, 1200L, 1201L), unmarked(prefix));
    assertEquals(1198, batchSizes.stream().mapToInt(Integer::intValue).sum());
    assertTrue(batchSizes.size() >= 3, "1198 rows need at least three batches: " + batchSizes);
    assertTrue(batchSizes.stream().allMatch(size -> size <= 500), batchSizes.toString());
  }

  @Test
  void aRetentionFailureLeavesTheCommittedAppendIntact() {
    String prefix = deleteStore(1);
    SpannerStorageHooks hooks =
        new SpannerStorageHooks() {
          @Override
          public void beforeRetentionDelete(AggregateId aggregateId, List<Long> seqNrs) {
            throw new IllegalStateException("injected retention failure");
          }
        };
    EventStore<String, String> store = store(open(prefix, SpannerRetentionPolicy.delete(1), hooks));
    store.persistEventAndSnapshot(event(1), snapshot(1));

    store.persistEventAndSnapshot(event(2), snapshot(2));

    assertEquals(
        List.of(1L, 2L),
        EMULATOR.longs("SELECT seq_nr FROM " + prefix + "_journal ORDER BY seq_nr"));
    assertEquals(List.of(2L), EMULATOR.longs("SELECT seq_nr FROM " + prefix + "_head"));
    assertEquals(List.of(2L), EMULATOR.longs("SELECT seq_nr FROM " + prefix + "_snapshot_current"));
    assertEquals(
        List.of(1L, 2L),
        EMULATOR.longs("SELECT seq_nr FROM " + prefix + "_snapshot_history ORDER BY seq_nr"));
  }
}
