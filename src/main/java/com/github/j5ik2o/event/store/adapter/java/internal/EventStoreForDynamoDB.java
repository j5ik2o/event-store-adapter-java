package com.github.j5ik2o.event.store.adapter.java.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.j5ik2o.event.store.adapter.java.*;
import io.vavr.Tuple2;
import io.vavr.control.Option;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import javax.annotation.Nonnull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;
import software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException;

public final class EventStoreForDynamoDB<
        AID extends AggregateId, A extends Aggregate<A, AID>, E extends Event<AID>>
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

  private final Option<Long> keepSnapshotCount;

  private final Option<Duration> deleteTtl;

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
      @Nonnull Long keepSnapshotCount,
      @Nonnull Duration deleteTtl,
      @Nonnull KeyResolver<AID> keyResolver,
      @Nonnull EventSerializer<AID, E> eventSerializer,
      @Nonnull SnapshotSerializer<AID, A> snapshotSerializer) {
    this.dynamoDbClient = dynamoDbClient;
    this.journalTableName = journalTableName;
    this.snapshotTableName = snapshotTableName;
    this.journalAidIndexName = journalAidIndexName;
    this.snapshotAidIndexName = snapshotAidIndexName;
    this.shardCount = shardCount;
    this.keepSnapshotCount = Option.of(keepSnapshotCount);
    this.deleteTtl = Option.of(deleteTtl);
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
            this.keepSnapshotCount,
            this.deleteTtl,
            keyResolver,
            eventSerializer,
            snapshotSerializer);
  }

  public static <AID extends AggregateId, A extends Aggregate<A, AID>, E extends Event<AID>>
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
  @Override
  public EventStoreForDynamoDB<AID, A, E> withKeepSnapshotCount(long keepSnapshotCount) {
    return new EventStoreForDynamoDB<>(
        dynamoDbClient,
        journalTableName,
        snapshotTableName,
        journalAidIndexName,
        snapshotAidIndexName,
        shardCount,
        keepSnapshotCount,
        deleteTtl.getOrNull(),
        keyResolver,
        eventSerializer,
        snapshotSerializer);
  }

  @Nonnull
  @Override
  public EventStoreForDynamoDB<AID, A, E> withDeleteTtl(@Nonnull Duration deleteTtl) {
    return new EventStoreForDynamoDB<>(
        dynamoDbClient,
        journalTableName,
        snapshotTableName,
        journalAidIndexName,
        snapshotAidIndexName,
        shardCount,
        keepSnapshotCount.getOrNull(),
        deleteTtl,
        keyResolver,
        eventSerializer,
        snapshotSerializer);
  }

  @Nonnull
  @Override
  public EventStoreForDynamoDB<AID, A, E> withKeyResolver(@Nonnull KeyResolver<AID> keyResolver) {
    return new EventStoreForDynamoDB<>(
        dynamoDbClient,
        journalTableName,
        snapshotTableName,
        journalAidIndexName,
        snapshotAidIndexName,
        shardCount,
        keepSnapshotCount.getOrNull(),
        deleteTtl.getOrNull(),
        keyResolver,
        eventSerializer,
        snapshotSerializer);
  }

  @Nonnull
  @Override
  public EventStoreForDynamoDB<AID, A, E> withEventSerializer(
      @Nonnull EventSerializer<AID, E> eventSerializer) {
    return new EventStoreForDynamoDB<>(
        dynamoDbClient,
        journalTableName,
        snapshotTableName,
        journalAidIndexName,
        snapshotAidIndexName,
        shardCount,
        keepSnapshotCount.getOrNull(),
        deleteTtl.getOrNull(),
        keyResolver,
        eventSerializer,
        snapshotSerializer);
  }

  @Nonnull
  @Override
  public EventStoreForDynamoDB<AID, A, E> withSnapshotSerializer(
      @Nonnull SnapshotSerializer<AID, A> snapshotSerializer) {
    return new EventStoreForDynamoDB<>(
        dynamoDbClient,
        journalTableName,
        snapshotTableName,
        journalAidIndexName,
        snapshotAidIndexName,
        shardCount,
        keepSnapshotCount.getOrNull(),
        deleteTtl.getOrNull(),
        keyResolver,
        eventSerializer,
        snapshotSerializer);
  }

  @Nonnull
  @Override
  public Optional<A> getLatestSnapshotById(@Nonnull Class<A> clazz, @Nonnull AID aggregateId)
      throws EventStoreReadException, SerializationException {
    try {
      LOGGER.debug("getLatestSnapshotById({}, {}): start", clazz, aggregateId);
      var request = eventStoreSupport.getLatestSnapshotByIdQueryRequest(aggregateId);
      var response = dynamoDbClient.query(request);
      var result = eventStoreSupport.convertToAggregateAndVersion(response, clazz);
      LOGGER.debug("getLatestSnapshotById({}, {}): finished", clazz, aggregateId);
      return result;
    } catch (AwsServiceException | SdkClientException e) {
      throw new EventStoreReadException(e);
    }
  }

  @Nonnull
  @Override
  public List<E> getEventsByIdSinceSequenceNumber(
      @Nonnull Class<E> clazz, @Nonnull AID aggregateId, long sequenceNumber)
      throws SerializationException, EventStoreReadException {
    try {
      LOGGER.debug(
          "getEventsByIdSinceSequenceNumber({}, {}, {}): start",
          clazz,
          aggregateId,
          sequenceNumber);
      var request =
          eventStoreSupport.getEventsByIdSinceSequenceNumberQueryRequest(
              aggregateId, sequenceNumber);
      var response = dynamoDbClient.query(request);
      var result = eventStoreSupport.convertToEvents(response, clazz);
      LOGGER.debug(
          "getEventsByIdSinceSequenceNumber({}, {}, {}): finished",
          clazz,
          aggregateId,
          sequenceNumber);
      return result;
    } catch (AwsServiceException | SdkClientException e) {
      throw new EventStoreReadException(e);
    }
  }

  @Override
  public void persistEvent(@Nonnull E event, long version)
      throws EventStoreWriteException, SerializationException, TransactionException {
    LOGGER.debug("persistEvent({}, {}): start", event, version);
    if (event.isCreated()) {
      throw new IllegalArgumentException("event is created");
    }
    updateEventAndSnapshotOpt(event, version, Option.none());
    tryPurgeExcessSnapshots(event);
    LOGGER.debug("persistEvent({}, {}): finished", event, version);
  }

  @Override
  public void persistEventAndSnapshot(@Nonnull E event, @Nonnull A aggregate)
      throws EventStoreWriteException, SerializationException, TransactionException {
    LOGGER.debug("persistEventAndSnapshot({}, {}): start", event, aggregate);
    TransactWriteItemsResponse result;
    if (event.isCreated()) {
      result = createEventAndSnapshot(event, aggregate);
    } else {
      result = updateEventAndSnapshotOpt(event, aggregate.getVersion(), Option.some(aggregate));
      tryPurgeExcessSnapshots(event);
    }
    LOGGER.debug("result = {}", result);
    LOGGER.debug("persistEventAndSnapshot({}, {}): finished", event, aggregate);
  }

  private TransactWriteItemsResponse createEventAndSnapshot(@Nonnull E event, @Nonnull A aggregate)
      throws SerializationException, EventStoreWriteException {
    try {
      LOGGER.debug("createEventAndSnapshot({}, {}): start", event, aggregate);
      var request =
          eventStoreSupport.createEventAndSnapshotTransactWriteItemsRequest(event, aggregate);
      var result = dynamoDbClient.transactWriteItems(request);
      LOGGER.debug("createEventAndSnapshot({}, {}): finished", event, aggregate);
      return result;
    } catch (AwsServiceException | SdkClientException e) {
      throw new EventStoreWriteException(e);
    }
  }

  private TransactWriteItemsResponse updateEventAndSnapshotOpt(
      @Nonnull E event, long version, Option<A> aggregate)
      throws SerializationException, EventStoreWriteException, TransactionException {
    try {
      LOGGER.debug("updateEventAndSnapshotOpt({}, {}, {}): start", event, version, aggregate);
      var request =
          eventStoreSupport.updateEventAndSnapshotOptTransactWriteItemsRequest(
              event, version, aggregate);
      var result = dynamoDbClient.transactWriteItems(request);
      LOGGER.debug("updateEventAndSnapshotOpt({}, {}, {}): finished", event, version, aggregate);
      return result;
    } catch (TransactionCanceledException e) {
      throw new TransactionException(e);
    } catch (AwsServiceException | SdkClientException e) {
      throw new EventStoreWriteException(e);
    }
  }

  private int getSnapshotCount(AID id) throws EventStoreReadException {
    try {
      var request = eventStoreSupport.getSnapshotCountQueryRequest(id);
      var response = dynamoDbClient.query(request);
      return response.count();
    } catch (AwsServiceException | SdkClientException e) {
      throw new EventStoreReadException(e);
    }
  }

  private io.vavr.collection.List<Tuple2<String, String>> getLastSnapshotKeys(AID id, int limit)
      throws EventStoreReadException {
    try {
      var request = eventStoreSupport.getLastSnapshotKeysQueryRequest(id, limit);
      var response = dynamoDbClient.query(request);
      var items = response.items();
      var result = new ArrayList<Tuple2<String, String>>();
      for (var item : items) {
        var pkey = item.get("pkey").s();
        var skey = item.get("skey").s();
        result.add(new Tuple2<>(pkey, skey));
      }
      return io.vavr.collection.List.ofAll(result);
    } catch (AwsServiceException | SdkClientException e) {
      throw new EventStoreReadException(e);
    }
  }

  private void tryPurgeExcessSnapshots(E event) throws EventStoreWriteException {
    if (keepSnapshotCount.isDefined()) {
      if (deleteTtl.isDefined()) {
        updateTtlOfExcessSnapshots(event.getAggregateId());
      } else {
        deleteExcessSnapshots(event.getAggregateId());
      }
    }
  }

  private void deleteExcessSnapshots(AID id) throws EventStoreWriteException {
    try {
      if (keepSnapshotCount.isDefined()) {
        var snapshotCount = getSnapshotCount(id) - 1;
        var excessCount = snapshotCount - keepSnapshotCount.get();
        if (excessCount > 0) {
          var keys = getLastSnapshotKeys(id, (int) excessCount);
          if (!keys.isEmpty()) {
            dynamoDbClient.batchWriteItem(eventStoreSupport.batchDeleteSnapshotRequest(keys));
          }
        }
      }
    } catch (AwsServiceException | SdkClientException | EventStoreReadException e) {
      throw new EventStoreWriteException(e);
    }
  }

  private void updateTtlOfExcessSnapshots(AID id) throws EventStoreWriteException {
    try {
      if (keepSnapshotCount.isDefined() && deleteTtl.isDefined()) {
        var snapshotCount = getSnapshotCount(id) - 1;
        var excessCount = snapshotCount - keepSnapshotCount.get();
        if (excessCount > 0) {
          var keys = getLastSnapshotKeys(id, (int) excessCount);
          var ttl = Instant.now().plus(deleteTtl.get());
          for (var key : keys) {
            dynamoDbClient.updateItem(
                eventStoreSupport.updateTtlOfExcessSnapshots(key._1, key._2, ttl.getEpochSecond()));
          }
        }
      }
    } catch (AwsServiceException | SdkClientException | EventStoreReadException e) {
      throw new EventStoreWriteException(e);
    }
  }
}
