package com.github.j5ik2o.event.store.adapter.java;

import com.github.j5ik2o.event.store.adapter.java.internal.EventStoreAsyncForDynamoDB;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import javax.annotation.Nonnull;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;

public interface EventStoreAsync<
    AID extends AggregateId, A extends Aggregate<AID>, E extends Event<AID>> {
  static <AID extends AggregateId, A extends Aggregate<AID>, E extends Event<AID>>
      EventStoreAsync<AID, A, E> ofDynamoDB(
          @Nonnull DynamoDbAsyncClient dynamoDbAsyncClient,
          @Nonnull String journalTableName,
          @Nonnull String snapshotTableName,
          @Nonnull String journalAidIndexName,
          @Nonnull String snapshotAidIndexName,
          long shardCount) {
    return EventStoreAsyncForDynamoDB.create(
        dynamoDbAsyncClient,
        journalTableName,
        snapshotTableName,
        journalAidIndexName,
        snapshotAidIndexName,
        shardCount);
  }

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
