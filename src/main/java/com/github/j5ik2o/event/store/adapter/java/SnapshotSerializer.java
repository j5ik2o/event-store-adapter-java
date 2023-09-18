package com.github.j5ik2o.event.store.adapter.java;

import javax.annotation.Nonnull;

public interface SnapshotSerializer<AID extends AggregateId, A extends Aggregate<A, AID>> {
  @Nonnull
  byte[] serialize(@Nonnull A snapshot) throws SerializationException;

  @Nonnull
  A deserialize(@Nonnull byte[] bytes, @Nonnull Class<A> clazz) throws SerializationException;
}
