package com.github.j5ik2o.event_store_adatpter_java;

import java.time.Instant;
import javax.annotation.Nonnull;

public interface Event<AID extends AggregateId> {
  @Nonnull
  String getId();

  @Nonnull
  AID getAggregateId();

  long getSeqNr();

  @Nonnull
  Instant getOccurredAt();

  boolean isCreated();
}
