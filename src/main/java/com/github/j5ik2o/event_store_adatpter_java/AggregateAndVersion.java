package com.github.j5ik2o.event_store_adatpter_java;

import javax.annotation.Nonnull;

public final class AggregateAndVersion<AID extends AggregateId, A extends Aggregate<AID>> {
  @Nonnull private final A aggregate;
  private final long version;

  public AggregateAndVersion(@Nonnull A aggregate, long version) {
    this.aggregate = aggregate;
    this.version = version;
  }

  @Nonnull
  public A getAggregate() {
    return aggregate;
  }

  public long getVersion() {
    return version;
  }
}
