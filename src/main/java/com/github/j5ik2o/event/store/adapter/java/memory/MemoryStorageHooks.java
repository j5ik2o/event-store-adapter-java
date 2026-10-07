package com.github.j5ik2o.event.store.adapter.java.memory;

import com.github.j5ik2o.event.store.adapter.java.core.AggregateId;
import java.util.List;

/** 内部の障害差し込みと履歴観測。公開 API には含めない。 */
interface MemoryStorageHooks {
  MemoryStorageHooks NONE = new MemoryStorageHooks() {};

  default void beforeCommit(AggregateId aggregateId) {}

  default void beforeReadEvents(AggregateId aggregateId) {}

  default void beforeReadSnapshot(AggregateId aggregateId) {}

  default List<Long> readHistory(AggregateId aggregateId, List<Long> actualHistory) {
    return actualHistory;
  }

  default void beforeRetentionDelete(AggregateId aggregateId, List<Long> seqNrs) {}
}
