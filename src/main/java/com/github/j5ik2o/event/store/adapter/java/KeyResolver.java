package com.github.j5ik2o.event.store.adapter.java;

import javax.annotation.Nonnull;

public interface KeyResolver<AID extends AggregateId> {
  @Nonnull
  String resolvePartitionKey(@Nonnull AID aggregateId, long shardCount);

  @Nonnull
  String resolveSortKey(@Nonnull AID aggregateId, long sequenceNumber);
}
