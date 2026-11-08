package com.github.j5ik2o.event.store.adapter.java.bigtable;

import com.github.j5ik2o.event.store.adapter.java.core.AggregateId;
import java.util.List;
import java.util.OptionalLong;

/** Internal fault injection and observation points. Not part of the public API. */
interface BigtableStorageHooks {
  BigtableStorageHooks NONE = new BigtableStorageHooks() {};

  /** Replaces the FNV-1a 64 hash of an aid, so tests can force row key prefixes to collide. */
  default OptionalLong aggregateHash(String aid) {
    return OptionalLong.empty();
  }

  /** Right before the previous event is written out to the journal (BT-7 step 3). */
  default void journalWritePrevious(AggregateId aggregateId, long seqNr) {}

  /** Right before the conditional update of the head. */
  default void beforeCommit(AggregateId aggregateId, long seqNr) {}

  /** After the conditional update of the head succeeded. Throwing makes the outcome unknown. */
  default void afterCommit(AggregateId aggregateId, long seqNr) {}

  /** Right before the new tail event is written out after the commit (BT-7 step 5). */
  default void journalWriteTail(AggregateId aggregateId, long seqNr) {}

  /** In the events read, right after the head read. */
  default void beforeReadEvents(AggregateId aggregateId) {}

  /** In the snapshot read, right after the head read. */
  default void beforeReadSnapshot(AggregateId aggregateId) {}

  /** The active history rows as read by retention. A hook may replace what retention sees. */
  default List<Long> readHistory(AggregateId aggregateId, List<Long> actualHistory) {
    return actualHistory;
  }

  /** Right before old history rows are retired. */
  default void beforeRetentionDelete(AggregateId aggregateId, List<Long> seqNrs) {}
}
