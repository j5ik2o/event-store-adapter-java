package com.github.j5ik2o.event.store.adapter.java;

import javax.annotation.Nonnull;

public interface Aggregate<AID extends AggregateId> {
  @Nonnull
  AID getId();

  long getSequenceNumber();

  long getVersion();
}
