package com.github.j5ik2o.event_store_adatpter_java;

import javax.annotation.Nonnull;

public interface SnapshotSerializer<AID extends AggregateId, A extends Aggregate<AID>> {
  @Nonnull
  byte[] serialize(@Nonnull A snapshot) throws SerializationException;

  @Nonnull
  A deserialize(@Nonnull byte[] bytes, @Nonnull Class<A> clazz) throws SerializationException;
}
