package com.github.j5ik2o.event_store_adatpter_java;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import javax.annotation.Nonnull;

public interface EventStoreAsync<
    AID extends AggregateId, A extends Aggregate<AID>, E extends Event<AID>> {
  @Nonnull
  CompletableFuture<Optional<AggregateAndVersion<AID, A>>> getLatestSnapshotById(
      @Nonnull Class<A> clazz, @Nonnull AID aggregateId);

  @Nonnull
  CompletableFuture<List<E>> getEventsByIdSinceSequenceNumber(
      @Nonnull Class<E> clazz, @Nonnull AID aggregateId, long sequenceNumber);

  @Nonnull
  CompletableFuture<Void> persistEvent(@Nonnull E event, long version);

  @Nonnull
  CompletableFuture<Void> persistEventAndSnapshot(@Nonnull E event, @Nonnull A aggregate);
}
