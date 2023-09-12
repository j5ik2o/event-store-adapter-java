package com.github.j5ik2o.event_store_adatpter_java;

import javax.annotation.Nonnull;

public interface KeyResolver<AID extends AggregateId> {
  @Nonnull
  String resolvePartitionKey(@Nonnull AID aggregateId, long shardCount);

  @Nonnull
  String resolveSortKey(@Nonnull AID aggregateId, long sequenceNumber);
}
