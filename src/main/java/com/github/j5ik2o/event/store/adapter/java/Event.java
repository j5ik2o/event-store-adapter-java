package com.github.j5ik2o.event.store.adapter.java;

import java.time.Instant;
import javax.annotation.Nonnull;

public interface Event<AID extends AggregateId> {
  @Nonnull
  String getId();

  @Nonnull
  AID getAggregateId();

  long getSequenceNumber();

  @Nonnull
  Instant getOccurredAt();

  boolean isCreated();
}
