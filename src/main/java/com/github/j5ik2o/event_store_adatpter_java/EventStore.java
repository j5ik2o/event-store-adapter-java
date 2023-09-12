package com.github.j5ik2o.event_store_adatpter_java;

import java.util.List;
import java.util.Optional;
import javax.annotation.Nonnull;

public interface EventStore<
    AID extends AggregateId, A extends Aggregate<AID>, E extends Event<AID>> {
  @Nonnull
  Optional<AggregateAndVersion<AID, A>> getLatestSnapshotById(
      @Nonnull Class<A> clazz, @Nonnull AID aggregateId);

  @Nonnull
  List<E> getEventsByIdSinceSequenceNumber(
      @Nonnull Class<E> clazz, @Nonnull AID aggregateId, long sequenceNumber);

  void persistEvent(@Nonnull  E event, long version);

  void persistEventAndSnapshot(@Nonnull  E event, @Nonnull  A aggregate);
}
