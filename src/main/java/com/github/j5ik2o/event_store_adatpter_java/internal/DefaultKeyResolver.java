package com.github.j5ik2o.event_store_adatpter_java.internal;

import com.github.j5ik2o.event_store_adatpter_java.AggregateId;
import com.github.j5ik2o.event_store_adatpter_java.KeyResolver;

public class DefaultKeyResolver<AID extends AggregateId> implements KeyResolver<AID> {
  @Override
  public String resolvePartitionKey(AID aggregateId, long shardCount) {
    var remainder = Math.abs(aggregateId.getValue().hashCode()) % shardCount;
    return String.format("%s-%d", aggregateId.getTypeName(), remainder);
  }

  @Override
  public String resolveSortKey(AID aggregateId, long sequenceNumber) {
    return String.format(
        "%s-%s-%d", aggregateId.getTypeName(), aggregateId.getValue(), sequenceNumber);
  }
}
