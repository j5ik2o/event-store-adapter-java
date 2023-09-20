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
import java.util.concurrent.CompletableFuture;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.*;

public final class EventStoreAsyncForDynamoDB<
        AID extends AggregateId, A extends Aggregate<A, AID>, E extends Event<AID>>
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

  @Nonnull private final Option<Long> keepSnapshotCount;

  @Nonnull private final Option<Duration> deleteTtl;

  @Nonnull private final KeyResolver<AID> keyResolver;

  @Nonnull private final EventSerializer<AID, E> eventSerializer;

  @Nonnull private final SnapshotSerializer<AID, A> snapshotSerializer;

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
      @Nonnull KeyResolver<AID> keyResolver,
      @Nonnull EventSerializer<AID, E> eventSerializer,
      @Nonnull SnapshotSerializer<AID, A> snapshotSerializer) {
    this.dynamoDbAsyncClient = dynamoDbAsyncClient;
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
  @Override
  public EventStoreAsyncForDynamoDB<AID, A, E> withKeepSnapshotCount(long keepSnapshotCount) {
    return new EventStoreAsyncForDynamoDB<>(
        dynamoDbAsyncClient,
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
  public EventStoreAsyncForDynamoDB<AID, A, E> withDeleteTtl(@Nonnull Duration deleteTtl) {
    return new EventStoreAsyncForDynamoDB<>(
        dynamoDbAsyncClient,
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
  public EventStoreAsyncForDynamoDB<AID, A, E> withKeyResolver(
      @Nonnull KeyResolver<AID> keyResolver) {
    return new EventStoreAsyncForDynamoDB<>(
        dynamoDbAsyncClient,
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
  public EventStoreAsyncForDynamoDB<AID, A, E> withEventSerializer(
      @Nonnull EventSerializer<AID, E> eventSerializer) {
    return new EventStoreAsyncForDynamoDB<>(
        dynamoDbAsyncClient,
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
  public EventStoreAsyncForDynamoDB<AID, A, E> withSnapshotSerializer(
      @Nonnull SnapshotSerializer<AID, A> snapshotSerializer) {
    return new EventStoreAsyncForDynamoDB<>(
        dynamoDbAsyncClient,
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
  public CompletableFuture<Optional<A>> getLatestSnapshotById(
      @Nonnull Class<A> clazz, @Nonnull AID aggregateId) {
    LOGGER.debug("getLatestSnapshotById({}, {}): start", clazz, aggregateId);
    var request = eventStoreSupport.getLatestSnapshotByIdQueryRequest(aggregateId);
    return dynamoDbAsyncClient
        .query(request)
        .thenApply(
            response -> {
              try {
                return eventStoreSupport.convertToAggregateAndVersion(response, clazz);
              } catch (SerializationException e) {
                throw new EventStoreRuntimeException(e);
              }
            })
        .thenApply(
            result -> {
              LOGGER.debug("getLatestSnapshotById({}, {}): finished", clazz, aggregateId);
              return result;
            });
  }

  @Nonnull
  @Override
  public CompletableFuture<List<E>> getEventsByIdSinceSequenceNumber(
      @Nonnull Class<E> clazz, @Nonnull AID aggregateId, long sequenceNumber) {
    LOGGER.debug(
        "getEventsByIdSinceSequenceNumber({}, {}, {}): start", clazz, aggregateId, sequenceNumber);
    var request =
        eventStoreSupport.getEventsByIdSinceSequenceNumberQueryRequest(aggregateId, sequenceNumber);
    return dynamoDbAsyncClient
        .query(request)
        .thenApply(
            response -> {
              try {
                return eventStoreSupport.convertToEvents(response, clazz);
              } catch (SerializationException e) {
                throw new EventStoreRuntimeException(e);
              }
            })
        .thenApply(
            result -> {
              LOGGER.debug(
                  "getEventsByIdSinceSequenceNumber({}, {}, {}): finished",
                  clazz,
                  aggregateId,
                  sequenceNumber);
              return result;
            });
  }

  @Nonnull
  @Override
  public CompletableFuture<Void> persistEvent(@Nonnull E event, long version) {
    LOGGER.debug("persistEvent({}, {}): start", event, version);
    if (event.isCreated()) {
      throw new IllegalArgumentException("event is created");
    }
    return updateEventAndSnapshotOpt(event, version, Option.none())
        .thenCompose(ignored -> tryPurgeExcessSnapshots(event))
        .thenRun(() -> LOGGER.debug("persistEvent({}, {}): finished", event, version));
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
          updateEventAndSnapshotOpt(event, aggregate.getVersion(), Option.some(aggregate))
              .thenCompose(ignored -> tryPurgeExcessSnapshots(event));
    }
    return result.thenRun(
        () -> LOGGER.debug("persistEventAndSnapshot({}, {}): finished", event, aggregate));
  }

  private CompletableFuture<TransactWriteItemsResponse> createEventAndSnapshot(
      @Nonnull E event, @Nonnull A aggregate) {
    LOGGER.debug("createEventAndSnapshot({}, {}): start", event, aggregate);
    return CompletableFuture.supplyAsync(
            () -> {
              try {
                return eventStoreSupport.createEventAndSnapshotTransactWriteItemsRequest(
                    event, aggregate);
              } catch (SerializationException e) {
                throw new EventStoreRuntimeException(e);
              }
            })
        .thenCompose(dynamoDbAsyncClient::transactWriteItems)
        .thenApply(
            result -> {
              LOGGER.debug("createEventAndSnapshot({}, {}): finished", event, aggregate);
              return result;
            });
  }

  private CompletableFuture<TransactWriteItemsResponse> updateEventAndSnapshotOpt(
      @Nonnull E event, long version, Option<A> aggregate) {
    LOGGER.debug("updateEventAndSnapshotOpt({}, {}, {}): start", event, version, aggregate);
    return CompletableFuture.supplyAsync(
            () -> {
              try {
                return eventStoreSupport.updateEventAndSnapshotOptTransactWriteItemsRequest(
                    event, version, aggregate);
              } catch (SerializationException e) {
                throw new EventStoreRuntimeException(e);
              }
            })
        .thenCompose(dynamoDbAsyncClient::transactWriteItems)
        .thenApply(
            result -> {
              LOGGER.debug(
                  "updateEventAndSnapshotOpt({}, {}, {}): finished", event, version, aggregate);
              return result;
            });
  }

  private CompletableFuture<Integer> getSnapshotCount(AID id) {
    var request = eventStoreSupport.getSnapshotCountQueryRequest(id);
    var response = dynamoDbAsyncClient.query(request);
    return response.thenApply(QueryResponse::count);
  }

  private CompletableFuture<io.vavr.collection.List<Tuple2<String, String>>> getLastSnapshotKeys(
      AID id, int limit) {
    var request = eventStoreSupport.getLastSnapshotKeysQueryRequest(id, limit);
    return dynamoDbAsyncClient
        .query(request)
        .thenApply(
            response -> {
              var result = new ArrayList<Tuple2<String, String>>();
              for (var item : response.items()) {
                var pkey = item.get("pkey").s();
                var skey = item.get("skey").s();
                result.add(new Tuple2<>(pkey, skey));
              }
              return io.vavr.collection.List.ofAll(result);
            });
  }

  private CompletableFuture<Void> tryPurgeExcessSnapshots(E event) {
    if (keepSnapshotCount.isDefined()) {
      if (deleteTtl.isDefined()) {
        return updateTtlOfExcessSnapshots(event.getAggregateId());
      } else {
        return deleteExcessSnapshots(event.getAggregateId());
      }
    }
    return CompletableFuture.completedFuture(null);
  }

  private CompletableFuture<Void> deleteExcessSnapshots(AID id) {
    if (keepSnapshotCount.isDefined()) {
      return getSnapshotCount(id)
          .thenCompose(
              count -> {
                var snapshotCount = count - 1;
                var excessCount = snapshotCount - keepSnapshotCount.get();
                if (excessCount > 0) {
                  return getLastSnapshotKeys(id, (int) excessCount)
                      .thenCompose(
                          keys -> {
                            if (!keys.isEmpty()) {
                              return dynamoDbAsyncClient
                                  .batchWriteItem(
                                      eventStoreSupport.batchDeleteSnapshotRequest(keys))
                                  .thenRun(() -> {});
                            }
                            return CompletableFuture.completedFuture(null);
                          });
                }
                return CompletableFuture.completedFuture(null);
              });
    }
    return CompletableFuture.completedFuture(null);
  }

  private CompletableFuture<Void> updateTtlOfExcessSnapshots(AID id) {
    if (keepSnapshotCount.isDefined() && deleteTtl.isDefined()) {
      return getSnapshotCount(id)
          .thenCompose(
              count -> {
                var snapshotCount = count - 1;
                var excessCount = snapshotCount - keepSnapshotCount.get();
                if (excessCount > 0) {
                  var keysFuture = getLastSnapshotKeys(id, (int) excessCount);
                  var ttl = Instant.now().plus(deleteTtl.get());
                  return keysFuture.thenCompose(
                      keys ->
                          keys.foldLeft(
                              CompletableFuture.completedFuture(null),
                              (acc, key) ->
                                  acc.thenCompose(
                                      ignored ->
                                          dynamoDbAsyncClient
                                              .updateItem(
                                                  eventStoreSupport.updateTtlOfExcessSnapshots(
                                                      key._1, key._2, ttl.getEpochSecond()))
                                              .thenRun(() -> {}))));
                }
                return CompletableFuture.completedFuture(null);
              });
    }
    return CompletableFuture.completedFuture(null);
  }
}
