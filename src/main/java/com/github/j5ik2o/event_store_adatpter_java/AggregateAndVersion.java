package com.github.j5ik2o.event_store_adatpter_java;

public final class AggregateAndVersion<AID extends AggregateId, A extends Aggregate<AID>> {
  private final A aggregate;
  private final long version;

  public AggregateAndVersion(A aggregate, long version) {
    this.aggregate = aggregate;
    this.version = version;
  }

  public A getAggregate() {
    return aggregate;
  }

  public long getVersion() {
    return version;
  }
}
