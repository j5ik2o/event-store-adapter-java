package com.github.j5ik2o.event.store.adapter.java.sqlite;

import com.github.j5ik2o.event.store.adapter.java.core.AggregateId;
import java.util.List;

/** 内部の障害差し込みと履歴観測。公開 API には含めない。 */
interface SqliteStorageHooks {
  SqliteStorageHooks NONE = new SqliteStorageHooks() {};

  /** 追記の BEGIN IMMEDIATE 直後、head 読み取りの前。 */
  default void afterBeginImmediate(AggregateId aggregateId) {}

  /** 追記の COMMIT 直前。 */
  default void beforeCommit(AggregateId aggregateId) {}

  /** イベント取得の読み取りトランザクション内、head 読み取りの直後。 */
  default void beforeReadEvents(AggregateId aggregateId) {}

  /** スナップショット取得の読み取りトランザクション内、head 読み取りの直後。 */
  default void beforeReadSnapshot(AggregateId aggregateId) {}

  default List<Long> readHistory(AggregateId aggregateId, List<Long> actualHistory) {
    return actualHistory;
  }

  default void beforeRetentionDelete(AggregateId aggregateId, List<Long> seqNrs) {}
}
