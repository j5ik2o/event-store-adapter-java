package com.github.j5ik2o.event.store.adapter.java;

import javax.annotation.Nonnull;

public interface AggregateId {
  @Nonnull
  String getTypeName();

  @Nonnull
  String getValue();

  @Nonnull
  String asString();
}
