package com.github.j5ik2o.event.store.adapter.java.spanner;

import com.github.j5ik2o.event.store.adapter.java.core.AggregateId;
import java.util.List;

/** Internal fault injection and observation points. Not part of the public API. */
interface SpannerStorageHooks {
  SpannerStorageHooks NONE = new SpannerStorageHooks() {};

  /** Inside the read-write transaction, right before commit. */
  default void beforeCommit(AggregateId aggregateId) {}

  /** After a successful commit. Throwing makes the outcome unknown (SPN-8). */
  default void afterCommit(AggregateId aggregateId) {}

  /** In the events read transaction, right after the head read. */
  default void beforeReadEvents(AggregateId aggregateId) {}

  /** In the snapshot read transaction, right after the head read. */
  default void beforeReadSnapshot(AggregateId aggregateId) {}

  /**
   * The unmarked history rows, newest first, as read by one retention chunk. A hook may replace
   * what the chunk sees.
   */
  default List<Long> readHistory(AggregateId aggregateId, List<Long> actualHistory) {
    return actualHistory;
  }

  /** Right before a chunk of old history rows is deleted or marked. */
  default void beforeRetentionDelete(AggregateId aggregateId, List<Long> seqNrs) {}
}
