package com.github.j5ik2o.event.store.adapter.java;

import javax.annotation.Nonnull;

public interface EventSerializer<AID extends AggregateId, E extends Event<AID>> {
  @Nonnull
  byte[] serialize(@Nonnull E event) throws SerializationException;

  @Nonnull
  E deserialize(@Nonnull byte[] bytes, @Nonnull Class<E> clazz) throws SerializationException;
}
