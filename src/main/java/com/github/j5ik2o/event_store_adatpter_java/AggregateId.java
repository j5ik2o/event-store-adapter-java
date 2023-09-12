package com.github.j5ik2o.event_store_adatpter_java;

import javax.annotation.Nonnull;

public interface AggregateId {
  @Nonnull
  String getTypeName();

  @Nonnull
  String getValue();

  @Nonnull
  String asString();
}
