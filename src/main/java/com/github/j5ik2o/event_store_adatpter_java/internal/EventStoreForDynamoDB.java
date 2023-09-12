package com.github.j5ik2o.event_store_adatpter_java.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.j5ik2o.event_store_adatpter_java.*;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsResponse;

public final class EventStoreForDynamoDB<
        AID extends AggregateId, A extends Aggregate<AID>, E extends Event<AID>>
    implements EventStore<AID, A, E> {
  private static final Logger LOGGER = LoggerFactory.getLogger(EventStoreForDynamoDB.class);
  private static final ObjectMapper objectMapper = new ObjectMapper();

  static {
    objectMapper.findAndRegisterModules();
  }

  private final DynamoDbClient dynamoDbClient;
  private final String journalTableName;
  private final String snapshotTableName;
  private final String journalAidIndexName;
  private final String snapshotAidIndexName;
  private final long shardCount;

  private final Long keepSnapshotCount;

  private final Duration deleteTtl;

  private final KeyResolver<AID> keyResolver;

  private final EventSerializer<AID, E> eventSerializer;

  private final SnapshotSerializer<AID, A> snapshotSerializer;
  private final EventStoreSupport<AID, A, E> eventStoreSupport;

  private EventStoreForDynamoDB(
      @Nonnull DynamoDbClient dynamoDbClient,
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
    // required parameters
    this.dynamoDbClient = dynamoDbClient;
    this.journalTableName = journalTableName;
    this.snapshotTableName = snapshotTableName;
    this.journalAidIndexName = journalAidIndexName;
    this.snapshotAidIndexName = snapshotAidIndexName;
    this.shardCount = shardCount;
    // optional parameters
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
      EventStoreForDynamoDB<AID, A, E> create(
          @Nonnull DynamoDbClient dynamoDbClient,
          @Nonnull String journalTableName,
          @Nonnull String snapshotTableName,
          @Nonnull String journalAidIndexName,
          @Nonnull String snapshotAidIndexName,
          long shardCount) {
    return new EventStoreForDynamoDB<>(
        dynamoDbClient,
        journalTableName,
        snapshotTableName,
        journalAidIndexName,
        snapshotAidIndexName,
        shardCount);
  }

  EventStoreForDynamoDB(
      @Nonnull DynamoDbClient dynamoDbClient,
      @Nonnull String journalTableName,
      @Nonnull String snapshotTableName,
      @Nonnull String journalAidIndexName,
      @Nonnull String snapshotAidIndexName,
      long shardCount) {
    this(
        dynamoDbClient,
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
  public EventStoreForDynamoDB<AID, A, E> withKeepSnapshotCount(long keepSnapshotCount) {
    return new EventStoreForDynamoDB<>(
        dynamoDbClient,
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
  public EventStoreForDynamoDB<AID, A, E> withDeleteTtl(@Nonnull Duration deleteTtl) {
    return new EventStoreForDynamoDB<>(
        dynamoDbClient,
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
  public EventStoreForDynamoDB<AID, A, E> withKeyResolver(@Nonnull KeyResolver<AID> keyResolver) {
    return new EventStoreForDynamoDB<>(
        dynamoDbClient,
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
  public EventStoreForDynamoDB<AID, A, E> withEventSerializer(
      @Nonnull EventSerializer<AID, E> eventSerializer) {
    return new EventStoreForDynamoDB<>(
        dynamoDbClient,
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
  public EventStoreForDynamoDB<AID, A, E> withSnapshotSerializer(
      @Nonnull SnapshotSerializer<AID, A> snapshotSerializer) {
    return new EventStoreForDynamoDB<>(
        dynamoDbClient,
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
  public Optional<AggregateAndVersion<AID, A>> getLatestSnapshotById(
      @Nonnull Class<A> clazz, @Nonnull AID aggregateId) {
    LOGGER.debug("getLatestSnapshotById({}, {}): start", clazz, aggregateId);
    var request = eventStoreSupport.getLatestSnapshotByIdQueryRequest(aggregateId);
    var response = dynamoDbClient.query(request);
    var result = eventStoreSupport.convertToAggregateAndVersion(response, clazz);
    LOGGER.debug("getLatestSnapshotById({}, {}): finished", clazz, aggregateId);
    return result;
  }

  @Nonnull
  @Override
  public List<E> getEventsByIdSinceSequenceNumber(
      @Nonnull Class<E> clazz, @Nonnull AID aggregateId, long sequenceNumber) {
    LOGGER.debug(
        "getEventsByIdSinceSequenceNumber({}, {}, {}): start", clazz, aggregateId, sequenceNumber);
    var request =
        eventStoreSupport.getEventsByIdSinceSequenceNumberQueryRequest(aggregateId, sequenceNumber);
    var response = dynamoDbClient.query(request);

    var result = eventStoreSupport.convertToEvents(response, clazz);
    LOGGER.debug(
        "getEventsByIdSinceSequenceNumber({}, {}, {}): finished",
        clazz,
        aggregateId,
        sequenceNumber);
    return result;
  }

  @Override
  public void persistEvent(@Nonnull E event, long version) {
    LOGGER.debug("persistEvent({}, {}): start", event, version);
    if (event.isCreated()) {
      throw new IllegalArgumentException("event is created");
    }
    updateEventAndSnapshotOpt(event, version, null);
    LOGGER.debug("persistEvent({}, {}): finished", event, version);
  }

  @Override
  public void persistEventAndSnapshot(@Nonnull E event, @Nonnull A aggregate) {
    LOGGER.debug("persistEventAndSnapshot({}, {}): start", event, aggregate);
    TransactWriteItemsResponse result;
    if (event.isCreated()) {
      result = createEventAndSnapshot(event, aggregate);
    } else {
      result = updateEventAndSnapshotOpt(event, aggregate.getVersion(), aggregate);
    }
    LOGGER.debug("result = {}", result);
    LOGGER.debug("persistEventAndSnapshot({}, {}): finished", event, aggregate);
  }

  private TransactWriteItemsResponse createEventAndSnapshot(
      @Nonnull E event, @Nonnull A aggregate) {
    LOGGER.debug("createEventAndSnapshot({}, {}): start", event, aggregate);
    var request =
        eventStoreSupport.createEventAndSnapshotTransactWriteItemsRequest(event, aggregate);
    var result = dynamoDbClient.transactWriteItems(request);
    LOGGER.debug("createEventAndSnapshot({}, {}): finished", event, aggregate);
    return result;
  }

  private TransactWriteItemsResponse updateEventAndSnapshotOpt(
      @Nonnull E event, long version, A aggregate) {
    LOGGER.debug("updateEventAndSnapshotOpt({}, {}, {}): start", event, version, aggregate);
    var request =
        eventStoreSupport.updateEventAndSnapshotOptTransactWriteItemsRequest(
            event, version, aggregate);
    var result = dynamoDbClient.transactWriteItems(request);
    LOGGER.debug("updateEventAndSnapshotOpt({}, {}, {}): finished", event, version, aggregate);
    return result;
  }
}
