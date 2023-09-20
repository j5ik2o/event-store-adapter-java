package com.github.j5ik2o.event.store.adapter.java;

import com.github.j5ik2o.event.store.adapter.java.internal.EventStoreForDynamoDB;
import java.util.List;
import java.util.Optional;
import javax.annotation.Nonnull;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

public interface EventStore<
        AID extends AggregateId, A extends Aggregate<A, AID>, E extends Event<AID>>
    extends EventStoreOptions<EventStore<AID, A, E>, AID, A, E> {
  static <AID extends AggregateId, A extends Aggregate<A, AID>, E extends Event<AID>>
      EventStore<AID, A, E> ofDynamoDB(
          @Nonnull DynamoDbClient dynamoDbClient,
          @Nonnull String journalTableName,
          @Nonnull String snapshotTableName,
          @Nonnull String journalAidIndexName,
          @Nonnull String snapshotAidIndexName,
          long shardCount) {
    return EventStoreForDynamoDB.create(
        dynamoDbClient,
        journalTableName,
        snapshotTableName,
        journalAidIndexName,
        snapshotAidIndexName,
        shardCount);
  }

  @Nonnull
  Optional<A> getLatestSnapshotById(@Nonnull Class<A> clazz, @Nonnull AID aggregateId)
      throws EventStoreReadException, SerializationException;

  @Nonnull
  List<E> getEventsByIdSinceSequenceNumber(
      @Nonnull Class<E> clazz, @Nonnull AID aggregateId, long sequenceNumber)
      throws EventStoreReadException, SerializationException;

  void persistEvent(@Nonnull E event, long version)
      throws EventStoreWriteException, SerializationException, TransactionException;

  void persistEventAndSnapshot(@Nonnull E event, @Nonnull A aggregate)
      throws EventStoreWriteException, SerializationException, TransactionException;
}
