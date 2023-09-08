package com.github.j5ik2o.event_store_adatpter_java;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

public interface EventStore<
    AID extends AggregateId, A extends Aggregate<AID>, E extends Event<AID>> {
  CompletableFuture<Optional<AggregateWithSeqNrWithVersion<A>>> getLatestSnapshotById(
      Class<A> clazz, AID aggregateId);

  CompletableFuture<List<E>> getEventsByIdSinceSeqNr(Class<E> clazz, AID aggregateId, long seqNr);

  CompletableFuture<Void> storeEvent(E event, long snapshotVersion);

  CompletableFuture<Void> storeEventAndSnapshot(E event, long snapshotVersion, A aggregate);
}
