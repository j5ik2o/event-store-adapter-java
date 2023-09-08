package com.github.j5ik2o.event_store_adatpter_java;

import java.time.Instant;

public interface Event<AID extends AggregateId> {
  String getId();

  AID getAggregateId();

  long getSeqNr();

  Instant getOccurredAt();

  boolean isCreated();
}
