package com.github.j5ik2o.event.store.adapter.java;

public interface Aggregate<AID extends AggregateId> {
  AID getId();

  long getSequenceNumber();

  long getVersion();
}
