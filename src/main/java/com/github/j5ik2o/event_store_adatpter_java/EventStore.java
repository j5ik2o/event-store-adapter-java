package com.github.j5ik2o.event_store_adatpter_java;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import javax.annotation.Nonnull;

public interface EventStore<
    AID extends AggregateId, A extends Aggregate<AID>, E extends Event<AID>> {
  @Nonnull
  CompletableFuture<Optional<AggregateAndVersion<AID, A>>> getLatestSnapshotById(
      Class<A> clazz, AID aggregateId);

  @Nonnull
  CompletableFuture<List<E>> getEventsByIdSinceSeqNr(
      Class<E> clazz, AID aggregateId, long sequenceNumber);

  @Nonnull
  CompletableFuture<Void> persistEvent(E event, long version);

  @Nonnull
  CompletableFuture<Void> persistEventAndSnapshot(E event, A aggregate);
}
