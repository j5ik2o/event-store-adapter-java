package com.github.j5ik2o.event_store_adatpter_java.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.j5ik2o.event_store_adatpter_java.*;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.*;

public final class EventStoreAsyncForDynamoDB<
        AID extends AggregateId, A extends Aggregate<AID>, E extends Event<AID>>
    implements EventStoreAsync<AID, A, E> {

  private static final Logger LOGGER = LoggerFactory.getLogger(EventStoreAsyncForDynamoDB.class);
  private static final ObjectMapper objectMapper = new ObjectMapper();

  static {
    objectMapper.findAndRegisterModules();
  }

  @Nonnull private final DynamoDbAsyncClient dynamoDbAsyncClient;
  @Nonnull private final String journalTableName;
  @Nonnull private final String snapshotTableName;
  @Nonnull private final String journalAidIndexName;
  @Nonnull private final String snapshotAidIndexName;
  private final long shardCount;

  @Nullable private final Long keepSnapshotCount;

  @Nullable private final Duration deleteTtl;

  @Nullable private final KeyResolver<AID> keyResolver;

  @Nullable private final EventSerializer<AID, E> eventSerializer;

  @Nullable private final SnapshotSerializer<AID, A> snapshotSerializer;

  @Nonnull private final EventStoreSupport<AID, A, E> eventStoreSupport;

  EventStoreAsyncForDynamoDB(
      @Nonnull DynamoDbAsyncClient dynamoDbAsyncClient,
      @Nonnull String journalTableName,
      @Nonnull String snapshotTableName,
      @Nonnull String journalAidIndexName,
      @Nonnull String snapshotAidIndexName,
      long shardCount,
      @Nullable Long keepSnapshotCount,
      @Nullable Duration deleteTtl,
      @Nullable KeyResolver<AID> keyResolver,
      @Nullable EventSerializer<AID, E> eventSerializer,
      @Nullable SnapshotSerializer<AID, A> snapshotSerializer) {
    this.dynamoDbAsyncClient = dynamoDbAsyncClient;
    this.journalTableName = journalTableName;
    this.snapshotTableName = snapshotTableName;
    this.journalAidIndexName = journalAidIndexName;
    this.snapshotAidIndexName = snapshotAidIndexName;
    this.shardCount = shardCount;
    this.keepSnapshotCount = keepSnapshotCount;
    this.deleteTtl = deleteTtl;
    this.keyResolver = keyResolver;
    this.eventSerializer = eventSerializer;
    this.snapshotSerializer = snapshotSerializer;

    this.eventStoreSupport =
        new EventStoreSupport<>(
            journalTableName,
            snapshotTableName,
            journalAidIndexName,
            snapshotAidIndexName,
            shardCount,
            keepSnapshotCount,
            deleteTtl,
            keyResolver,
            eventSerializer,
            snapshotSerializer);
  }

  public static <AID extends AggregateId, A extends Aggregate<AID>, E extends Event<AID>>
      EventStoreAsyncForDynamoDB<AID, A, E> create(
          @Nonnull DynamoDbAsyncClient dynamoDbAsyncClient,
          @Nonnull String journalTableName,
          @Nonnull String snapshotTableName,
          @Nonnull String journalAidIndexName,
          @Nonnull String snapshotAidIndexName,
          long shardCount) {
    return new EventStoreAsyncForDynamoDB<>(
        dynamoDbAsyncClient,
        journalTableName,
        snapshotTableName,
        journalAidIndexName,
        snapshotAidIndexName,
        shardCount);
  }

  EventStoreAsyncForDynamoDB(
      @Nonnull DynamoDbAsyncClient dynamoDbAsyncClient,
      @Nonnull String journalTableName,
      @Nonnull String snapshotTableName,
      @Nonnull String journalAidIndexName,
      @Nonnull String snapshotAidIndexName,
      long shardCount) {
    this(
        dynamoDbAsyncClient,
        journalTableName,
        snapshotTableName,
        journalAidIndexName,
        snapshotAidIndexName,
        shardCount,
        null,
        null,
        new DefaultKeyResolver<>(),
        new JsonEventSerializer<>(objectMapper),
        new JsonSnapshotSerializer<>(objectMapper));
  }

  @Nonnull
  public EventStoreAsyncForDynamoDB<AID, A, E> withKeepSnapshotCount(long keepSnapshotCount) {
    return new EventStoreAsyncForDynamoDB<>(
        dynamoDbAsyncClient,
        journalTableName,
        snapshotTableName,
        journalAidIndexName,
        snapshotAidIndexName,
        shardCount,
        keepSnapshotCount,
        deleteTtl,
        keyResolver,
        eventSerializer,
        snapshotSerializer);
  }

  @Nonnull
  public EventStoreAsyncForDynamoDB<AID, A, E> withDeleteTtl(@Nonnull Duration deleteTtl) {
    return new EventStoreAsyncForDynamoDB<>(
        dynamoDbAsyncClient,
        journalTableName,
        snapshotTableName,
        journalAidIndexName,
        snapshotAidIndexName,
        shardCount,
        keepSnapshotCount,
        deleteTtl,
        keyResolver,
        eventSerializer,
        snapshotSerializer);
  }

  @Nonnull
  public EventStoreAsyncForDynamoDB<AID, A, E> withKeyResolver(
      @Nonnull KeyResolver<AID> keyResolver) {
    return new EventStoreAsyncForDynamoDB<>(
        dynamoDbAsyncClient,
        journalTableName,
        snapshotTableName,
        journalAidIndexName,
        snapshotAidIndexName,
        shardCount,
        keepSnapshotCount,
        deleteTtl,
        keyResolver,
        eventSerializer,
        snapshotSerializer);
  }

  @Nonnull
  public EventStoreAsyncForDynamoDB<AID, A, E> withEventSerializer(
      @Nonnull EventSerializer<AID, E> eventSerializer) {
    return new EventStoreAsyncForDynamoDB<>(
        dynamoDbAsyncClient,
        journalTableName,
        snapshotTableName,
        journalAidIndexName,
        snapshotAidIndexName,
        shardCount,
        keepSnapshotCount,
        deleteTtl,
        keyResolver,
        eventSerializer,
        snapshotSerializer);
  }

  @Nonnull
  public EventStoreAsyncForDynamoDB<AID, A, E> withSnapshotSerializer(
      @Nonnull SnapshotSerializer<AID, A> snapshotSerializer) {
    return new EventStoreAsyncForDynamoDB<>(
        dynamoDbAsyncClient,
        journalTableName,
        snapshotTableName,
        journalAidIndexName,
        snapshotAidIndexName,
        shardCount,
        keepSnapshotCount,
        deleteTtl,
        keyResolver,
        eventSerializer,
        snapshotSerializer);
  }

  @Nonnull
  @Override
  public CompletableFuture<Optional<AggregateAndVersion<AID, A>>> getLatestSnapshotById(
      @Nonnull Class<A> clazz, @Nonnull AID aggregateId) {
    LOGGER.debug("getLatestSnapshotById({}, {}): start", clazz, aggregateId);
    var request = eventStoreSupport.getLatestSnapshotByIdQueryRequest(aggregateId);
    var result =
        dynamoDbAsyncClient
            .query(request)
            .thenApply(response -> eventStoreSupport.convertToAggregateAndVersion(response, clazz));
    LOGGER.debug("getLatestSnapshotById({}, {}): finished", clazz, aggregateId);
    return result;
  }

  @Nonnull
  @Override
  public CompletableFuture<List<E>> getEventsByIdSinceSequenceNumber(
      @Nonnull Class<E> clazz, @Nonnull AID aggregateId, long sequenceNumber) {
    LOGGER.debug(
        "getEventsByIdSinceSequenceNumber({}, {}, {}): start", clazz, aggregateId, sequenceNumber);
    var request =
        eventStoreSupport.getEventsByIdSinceSequenceNumberQueryRequest(aggregateId, sequenceNumber);
    var result =
        dynamoDbAsyncClient
            .query(request)
            .thenApply(response -> eventStoreSupport.convertToEvents(response, clazz));
    LOGGER.debug(
        "getEventsByIdSinceSequenceNumber({}, {}, {}): finished",
        clazz,
        aggregateId,
        sequenceNumber);
    return result;
  }

  @Nonnull
  @Override
  public CompletableFuture<Void> persistEvent(@Nonnull E event, long version) {
    LOGGER.debug("persistEvent({}, {}): start", event, version);
    if (event.isCreated()) {
      throw new IllegalArgumentException("event is created");
    }
    var result = updateEventAndSnapshotOpt(event, version, null).thenRun(() -> {});
    LOGGER.debug("persistEvent({}, {}): finished", event, version);
    return result;
  }

  @Nonnull
  @Override
  public CompletableFuture<Void> persistEventAndSnapshot(@Nonnull E event, @Nonnull A aggregate) {
    LOGGER.debug("persistEventAndSnapshot({}, {}): start", event, aggregate);
    CompletableFuture<Void> result;
    if (event.isCreated()) {
      result = createEventAndSnapshot(event, aggregate).thenRun(() -> {});
    } else {
      result =
          updateEventAndSnapshotOpt(event, aggregate.getVersion(), aggregate).thenRun(() -> {});
    }
    LOGGER.debug("persistEventAndSnapshot({}, {}): finished", event, aggregate);
    return result;
  }

  private CompletableFuture<TransactWriteItemsResponse> createEventAndSnapshot(
      @Nonnull E event, @Nonnull A aggregate) {
    LOGGER.debug("createEventAndSnapshot({}, {}): start", event, aggregate);
    var request =
        eventStoreSupport.createEventAndSnapshotTransactWriteItemsRequest(event, aggregate);
    var result = dynamoDbAsyncClient.transactWriteItems(request);
    LOGGER.debug("createEventAndSnapshot({}, {}): finished", event, aggregate);
    return result;
  }

  private CompletableFuture<TransactWriteItemsResponse> updateEventAndSnapshotOpt(
      @Nonnull E event, long version, A aggregate) {
    LOGGER.debug("updateEventAndSnapshotOpt({}, {}, {}): start", event, version, aggregate);
    var request =
        eventStoreSupport.updateEventAndSnapshotOptTransactWriteItemsRequest(
            event, version, aggregate);
    var result = dynamoDbAsyncClient.transactWriteItems(request);
    LOGGER.debug("updateEventAndSnapshotOpt({}, {}, {}): finished", event, version, aggregate);
    return result;
  }
}
