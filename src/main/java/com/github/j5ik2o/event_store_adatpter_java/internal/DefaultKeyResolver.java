package com.github.j5ik2o.event_store_adatpter_java.internal;

import com.github.j5ik2o.event_store_adatpter_java.AggregateId;
import com.github.j5ik2o.event_store_adatpter_java.KeyResolver;
import javax.annotation.Nonnull;

public class DefaultKeyResolver<AID extends AggregateId> implements KeyResolver<AID> {
  @Nonnull
  @Override
  public String resolvePartitionKey(@Nonnull AID aggregateId, long shardCount) {
    var remainder = Math.abs(aggregateId.getValue().hashCode()) % shardCount;
    return String.format("%s-%d", aggregateId.getTypeName(), remainder);
  }

  @Nonnull
  @Override
  public String resolveSortKey(@Nonnull AID aggregateId, long sequenceNumber) {
    return String.format(
        "%s-%s-%d", aggregateId.getTypeName(), aggregateId.getValue(), sequenceNumber);
  }
}
