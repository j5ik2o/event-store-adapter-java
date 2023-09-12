package com.github.j5ik2o.event_store_adatpter_java;

import java.util.List;
import java.util.Optional;
import javax.annotation.Nonnull;

public interface EventStore<
    AID extends AggregateId, A extends Aggregate<AID>, E extends Event<AID>> {
  @Nonnull
  Optional<AggregateAndVersion<AID, A>> getLatestSnapshotById(Class<A> clazz, AID aggregateId);

  @Nonnull
  List<E> getEventsByIdSinceSequenceNumber(Class<E> clazz, AID aggregateId, long sequenceNumber);

  void persistEvent(E event, long version);

  void persistEventAndSnapshot(E event, A aggregate);
}
