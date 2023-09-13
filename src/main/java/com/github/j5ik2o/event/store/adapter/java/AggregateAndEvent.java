package com.github.j5ik2o.event.store.adapter.java;

import javax.annotation.Nonnull;

public final class AggregateAndEvent<
    AID extends AggregateId, A extends Aggregate<AID>, E extends Event<AID>> {
  @Nonnull private final A aggregate;
  @Nonnull private final E event;

  public AggregateAndEvent(@Nonnull A aggregate, @Nonnull E event) {
    this.aggregate = aggregate;
    this.event = event;
  }

  @Nonnull
  public A getAggregate() {
    return aggregate;
  }

  @Nonnull
  public E getEvent() {
    return event;
  }
}
