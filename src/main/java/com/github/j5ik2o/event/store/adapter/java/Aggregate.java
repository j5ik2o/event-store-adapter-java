package com.github.j5ik2o.event.store.adapter.java;

import javax.annotation.Nonnull;

public interface Aggregate<This extends Aggregate<This, AID>, AID extends AggregateId> {
  @Nonnull
  AID getId();

  long getSequenceNumber();

  long getVersion();

  This withVersion(long version);
}
