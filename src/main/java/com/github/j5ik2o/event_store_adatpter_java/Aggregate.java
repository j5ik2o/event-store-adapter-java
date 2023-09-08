package com.github.j5ik2o.event_store_adatpter_java;

public interface Aggregate<AID extends AggregateId> {
  AID getId();

  long getSeqNr();
}
