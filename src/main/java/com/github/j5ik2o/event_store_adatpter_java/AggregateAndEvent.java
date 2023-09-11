package com.github.j5ik2o.event_store_adatpter_java;

public final class AggregateAndEvent<
    AID extends AggregateId, A extends Aggregate<AID>, E extends Event<AID>> {
  private final A aggregate;
  private final E event;

  public AggregateAndEvent(A aggregate, E event) {
    this.aggregate = aggregate;
    this.event = event;
  }

  public A getAggregate() {
    return aggregate;
  }

  public E getEvent() {
    return event;
  }
}
