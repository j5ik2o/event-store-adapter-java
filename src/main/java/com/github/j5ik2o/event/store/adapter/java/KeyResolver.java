package com.github.j5ik2o.event.store.adapter.java;

import javax.annotation.Nonnull;

/**
 * This is an interface for resolving partition keys and sort keys from aggregate IDs. /
 * 集約IDからパーティションキーとソートキーを解決するためのインターフェース。
 *
 * @param <AID> Aggregate ID type / 集約IDの型
 */
public interface KeyResolver<AID extends AggregateId> {
  /**
   * Resolves the partition key from the aggregate id. / 集約IDからパーティションキーを解決します。
   *
   * @param aggregateId aggregate id / 集約ID
   * @param shardCount shard count / シャード数
   * @return partition key / パーティションキー
   */
  @Nonnull
  String resolvePartitionKey(@Nonnull AID aggregateId, long shardCount);

  /**
   * Resolves the sort key from the aggregate id and sequence number. / 集約IDとシーケンス番号からソートキーを解決します。
   *
   * @param aggregateId aggregate id / 集約ID
   * @param sequenceNumber sequence number / シーケンス番号
   * @return sort key / ソートキー
   */
  @Nonnull
  String resolveSortKey(@Nonnull AID aggregateId, long sequenceNumber);
}
