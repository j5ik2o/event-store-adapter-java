package com.github.j5ik2o.event_store_adatpter_java;

public interface KeyResolver<AID extends AggregateId> {
  String resolvePartitionKey(AID aggregateId, long shardCount);

  String resolveSortKey(AID aggregateId, long seqNr);
}
