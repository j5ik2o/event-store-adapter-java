package com.github.j5ik2o.event.store.adapter.java.internal;

import com.github.j5ik2o.event.store.adapter.java.*;
import io.vavr.Tuple2;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.dynamodb.model.*;

final class EventStoreSupport<
    AID extends AggregateId, A extends Aggregate<AID>, E extends Event<AID>> {
  private static final Logger LOGGER = LoggerFactory.getLogger(EventStoreSupport.class);
  @Nonnull private final String journalTableName;
  @Nonnull private final String snapshotTableName;
  @Nonnull private final String journalAidIndexName;
  @Nonnull private final String snapshotAidIndexName;
  private final long shardCount;

  private final Long keepSnapshotCount;

  private final Duration deleteTtl;

  @Nonnull private final KeyResolver<AID> keyResolver;

  @Nonnull private final EventSerializer<AID, E> eventSerializer;

  @Nonnull private final SnapshotSerializer<AID, A> snapshotSerializer;

  EventStoreSupport(
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
  }

  @Nonnull
  EventStoreSupport<AID, A, E> withKeepSnapshotCount(long keepSnapshotCount) {
    return new EventStoreSupport<>(
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
  EventStoreSupport<AID, A, E> withDeleteTtl(@Nonnull Duration deleteTtl) {
    return new EventStoreSupport<>(
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
  EventStoreSupport<AID, A, E> withKeyResolver(@Nonnull KeyResolver<AID> keyResolver) {
    return new EventStoreSupport<>(
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
  EventStoreSupport<AID, A, E> withEventSerializer(
      @Nonnull EventSerializer<AID, E> eventSerializer) {
    return new EventStoreSupport<>(
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
  EventStoreSupport<AID, A, E> withSnapshotSerializer(
      @Nonnull SnapshotSerializer<AID, A> snapshotSerializer) {
    return new EventStoreSupport<>(
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
  QueryRequest getLatestSnapshotByIdQueryRequest(@Nonnull AID aggregateId) {
    LOGGER.debug("getLatestSnapshotByIdQueryRequest({}): start", aggregateId);
    var request =
        QueryRequest.builder()
            .tableName(snapshotTableName)
            .indexName(snapshotAidIndexName)
            .keyConditionExpression("#aid = :aid and #seq_nr = :seq_nr")
            .expressionAttributeNames(
                Map.of(
                    "#aid", "aid",
                    "#seq_nr", "seq_nr"))
            .expressionAttributeValues(
                Map.of(
                    ":aid", AttributeValue.builder().s(aggregateId.asString()).build(),
                    ":seq_nr", AttributeValue.builder().n("0").build()))
            .limit(1)
            .build();
    LOGGER.debug("getLatestSnapshotByIdQueryRequest({}): finished", aggregateId);
    return request;
  }

  Optional<AggregateAndVersion<AID, A>> convertToAggregateAndVersion(
      @Nonnull QueryResponse response, @Nonnull Class<A> clazz) {
    LOGGER.debug("convertToAggregateAndVersion({}, {}): start", response, clazz);
    var items = response.items();
    LOGGER.debug("items = {}", items);
    Optional<AggregateAndVersion<AID, A>> applyResult;
    if (items.isEmpty()) {
      applyResult = Optional.empty();
    } else {
      var item = items.get(0);
      var bytes = item.get("payload").b().asByteArray();
      var aggregate = snapshotSerializer.deserialize(bytes, clazz);
      var version = Long.parseLong(item.get("version").n());
      var sequenceNumber = aggregate.getSequenceNumber();
      LOGGER.debug("sequenceNumber = {}", sequenceNumber);
      applyResult = Optional.of(new AggregateAndVersion<>(aggregate, version));
    }
    LOGGER.debug("convertToAggregateAndVersion({}, {}): finished", response, clazz);
    return applyResult;
  }

  QueryRequest getEventsByIdSinceSequenceNumberQueryRequest(
      @Nonnull AID aggregateId, long sequenceNumber) {
    return QueryRequest.builder()
        .tableName(journalTableName)
        .indexName(journalAidIndexName)
        .keyConditionExpression("#aid = :aid and #seq_nr >= :seq_nr")
        .expressionAttributeNames(
            Map.of(
                "#aid", "aid",
                "#seq_nr", "seq_nr"))
        .expressionAttributeValues(
            Map.of(
                ":aid",
                AttributeValue.builder().s(aggregateId.asString()).build(),
                ":seq_nr",
                AttributeValue.builder().n(String.valueOf(sequenceNumber)).build()))
        .build();
  }

  @Nonnull
  List<E> convertToEvents(@Nonnull QueryResponse response, @Nonnull Class<E> clazz) {
    var items = response.items();
    LOGGER.debug("items = {}", items);
    List<E> events = new java.util.ArrayList<>();
    for (var item : items) {
      var bytes = item.get("payload").b().asByteArray();
      var event = eventSerializer.deserialize(bytes, clazz);
      events.add(event);
    }
    return events;
  }

  @Nonnull
  TransactWriteItemsRequest createEventAndSnapshotTransactWriteItemsRequest(
      @Nonnull E event, @Nonnull A aggregate) {
    List<TransactWriteItem> transactItems = new java.util.ArrayList<>();
    transactItems.add(putSnapshot(event, 0, aggregate));
    transactItems.add(putJournal(event));
    if (keepSnapshotCount != null) {
      transactItems.add(putSnapshot(event, aggregate.getSequenceNumber(), aggregate));
    }
    return TransactWriteItemsRequest.builder().transactItems(transactItems).build();
  }

  @Nonnull
  TransactWriteItemsRequest updateEventAndSnapshotOptTransactWriteItemsRequest(
      @Nonnull E event, long version, A aggregate) {
    List<TransactWriteItem> transactItems = new java.util.ArrayList<>();
    transactItems.add(updateSnapshot(event, 0, version, aggregate));
    transactItems.add(putJournal(event));
    if (keepSnapshotCount != null && aggregate != null) {
      transactItems.add(putSnapshot(event, aggregate.getSequenceNumber(), aggregate));
    }
    return TransactWriteItemsRequest.builder().transactItems(transactItems).build();
  }

  @Nonnull
  TransactWriteItem putSnapshot(@Nonnull E event, long sequenceNumber, A aggregate) {
    LOGGER.debug("putSnapshot({}, {}, {}): start", event, sequenceNumber, aggregate);
    var pkey = resolvePartitionKey(event.getAggregateId(), shardCount);
    var skey = resolveSortKey(event.getAggregateId(), sequenceNumber);
    var payload = snapshotSerializer.serialize(aggregate);
    LOGGER.debug(">--- put snapshot ---");
    LOGGER.debug("pkey = {}", pkey);
    LOGGER.debug("skey = {}", skey);
    LOGGER.debug("aid = {}", event.getAggregateId().asString());
    LOGGER.debug("seq_nr = {}", sequenceNumber);
    LOGGER.debug("payload = {}", new String(payload));
    LOGGER.debug("<--- put snapshot ---");
    var put =
        Put.builder()
            .tableName(snapshotTableName)
            .item(
                Map.of(
                    "pkey",
                    AttributeValue.builder().s(pkey).build(),
                    "skey",
                    AttributeValue.builder().s(skey).build(),
                    "payload",
                    AttributeValue.builder().b(SdkBytes.fromByteArray(payload)).build(),
                    "aid",
                    AttributeValue.builder().s(event.getAggregateId().asString()).build(),
                    "seq_nr",
                    AttributeValue.builder().n(String.valueOf(sequenceNumber)).build(),
                    "version",
                    AttributeValue.builder().n("1").build(),
                    "ttl",
                    AttributeValue.builder().n("0").build(),
                    "last_updated_at",
                    AttributeValue.builder()
                        .n(String.valueOf(event.getOccurredAt().toEpochMilli()))
                        .build()))
            .conditionExpression("attribute_not_exists(pkey) AND attribute_not_exists(skey)")
            .build();
    var result = TransactWriteItem.builder().put(put).build();
    LOGGER.debug("putSnapshot({}, {}, {}): finished", event, sequenceNumber, aggregate);
    return result;
  }

  @Nonnull
  TransactWriteItem updateSnapshot(
      @Nonnull E event, long sequenceNumber, long version, A aggregate) {
    LOGGER.debug("updateSnapshot({}, {}, {}): start", event, sequenceNumber, aggregate);
    var pkey = resolvePartitionKey(event.getAggregateId(), shardCount);
    var skey = resolveSortKey(event.getAggregateId(), sequenceNumber);
    LOGGER.debug(">--- update snapshot ---");
    LOGGER.debug("pkey = {}", pkey);
    LOGGER.debug("skey = {}", skey);
    LOGGER.debug("aid = {}", event.getAggregateId().asString());
    LOGGER.debug("seq_nr = {}", sequenceNumber);
    LOGGER.debug("<--- update snapshot ---");

    var names =
        io.vavr.collection.HashMap.of("#version", "version", "#last_updated_at", "last_updated_at");
    var values =
        io.vavr.collection.HashMap.of(
            ":before_version",
            AttributeValue.builder().n(String.valueOf(version)).build(),
            ":after_version",
            AttributeValue.builder().n(String.valueOf(version + 1)).build(),
            ":last_updated_at",
            AttributeValue.builder()
                .n(String.valueOf(event.getOccurredAt().toEpochMilli()))
                .build());
    var update =
        Update.builder()
            .tableName(snapshotTableName)
            .updateExpression("SET #version=:after_version, #last_updated_at=:last_updated_at")
            .key(
                Map.of(
                    "pkey",
                    AttributeValue.builder().s(pkey).build(),
                    "skey",
                    AttributeValue.builder().s(skey).build()))
            .expressionAttributeNames(names.toJavaMap())
            .expressionAttributeValues(values.toJavaMap())
            .conditionExpression("#version = :before_version");
    if (aggregate != null) {
      var payload = snapshotSerializer.serialize(aggregate);
      LOGGER.debug("payload = {}", payload);
      update =
          update
              .updateExpression(
                  "SET #payload=:payload, #seq_nr=:seq_nr, #version=:after_version, #last_updated_at=:last_updated_at")
              .expressionAttributeNames(
                  names
                      .merge(
                          io.vavr.collection.HashMap.of(
                              "#seq_nr", ":seq_nr", "#payload", "payload"))
                      .toJavaMap())
              .expressionAttributeValues(
                  values
                      .merge(
                          io.vavr.collection.HashMap.of(
                              ":seq_nr",
                              AttributeValue.builder().n(String.valueOf(sequenceNumber)).build(),
                              ":payload",
                              AttributeValue.builder().b(SdkBytes.fromByteArray(payload)).build()))
                      .toJavaMap());
    }
    var result = TransactWriteItem.builder().update(update.build()).build();
    LOGGER.debug("updateSnapshot({}, {}, {}): finished", event, sequenceNumber, aggregate);
    return result;
  }

  @Nonnull
  String resolvePartitionKey(@Nonnull AID aggregateId, long shardCount) {
    return keyResolver.resolvePartitionKey(aggregateId, shardCount);
  }

  @Nonnull
  String resolveSortKey(@Nonnull AID aggregateId, long sequenceNumber) {
    return keyResolver.resolveSortKey(aggregateId, sequenceNumber);
  }

  @Nonnull
  TransactWriteItem putJournal(@Nonnull E event) {
    LOGGER.debug("putJournal({}): start", event);
    var pkey = resolvePartitionKey(event.getAggregateId(), shardCount);
    var skey = resolveSortKey(event.getAggregateId(), event.getSequenceNumber());
    var aid = event.getAggregateId().asString();
    var sequenceNumber = event.getSequenceNumber();
    var payload = eventSerializer.serialize(event);
    var occurredAt = String.valueOf(event.getOccurredAt().toEpochMilli());

    LOGGER.debug(">--- put journal ---");
    LOGGER.debug("pkey = {}", pkey);
    LOGGER.debug("skey = {}", skey);
    LOGGER.debug("aid = {}", event.getAggregateId().asString());
    LOGGER.debug("seq_nr = {}", sequenceNumber);
    LOGGER.debug("payload = {}", new String(payload));
    LOGGER.debug("<--- put journal ---");

    var put =
        Put.builder()
            .tableName(journalTableName)
            .item(
                Map.of(
                    "pkey",
                    AttributeValue.builder().s(pkey).build(),
                    "skey",
                    AttributeValue.builder().s(skey).build(),
                    "aid",
                    AttributeValue.builder().s(aid).build(),
                    "seq_nr",
                    AttributeValue.builder().n(String.valueOf(sequenceNumber)).build(),
                    "payload",
                    AttributeValue.builder().b(SdkBytes.fromByteArray(payload)).build(),
                    "occurred_at",
                    AttributeValue.builder().n(occurredAt).build()))
            .build();
    var result = TransactWriteItem.builder().put(put).build();
    LOGGER.debug("putJournal({}): finished", event);
    return result;
  }

  QueryRequest getSnapshotCountQueryRequest(AID id) {
    return QueryRequest.builder()
        .tableName(snapshotTableName)
        .indexName(snapshotAidIndexName)
        .keyConditionExpression("#aid = :aid")
        .expressionAttributeNames(Map.of("#aid", "aid"))
        .expressionAttributeValues(
            Map.of(":aid", AttributeValue.builder().s(id.asString()).build()))
        .select(Select.COUNT)
        .build();
  }

  QueryRequest getLastSnapshotKeysQueryRequest(AID id, int limit) {
    var names = io.vavr.collection.HashMap.of("#aid", "aid", "#seq_nr", "seq_nr");
    var values =
        io.vavr.collection.HashMap.of(
            ":aid", AttributeValue.builder().s(id.asString()).build(),
            ":seq_nr", AttributeValue.builder().n("0").build());
    var queryBuilder =
        QueryRequest.builder()
            .tableName(snapshotTableName)
            .indexName(snapshotAidIndexName)
            .keyConditionExpression("#aid = :aid AND #seq_nr > :seq_nr")
            .expressionAttributeNames(names.toJavaMap())
            .expressionAttributeValues(values.toJavaMap())
            .scanIndexForward(false)
            .limit(limit);
    if (deleteTtl != null) {
      queryBuilder =
          queryBuilder
              .filterExpression("ttl < :ttl")
              .expressionAttributeNames(
                  names.merge(io.vavr.collection.HashMap.of("#ttl", "ttl")).toJavaMap())
              .expressionAttributeValues(
                  values
                      .merge(
                          io.vavr.collection.HashMap.of(
                              ":ttl",
                              AttributeValue.builder()
                                  .n(String.valueOf(deleteTtl.toSeconds()))
                                  .build()))
                      .toJavaMap());
    }
    return queryBuilder.build();
  }

  UpdateItemRequest updateTtlOfExcessSnapshots(
      @Nonnull String pkey, @Nonnull String skey, long seconds) {
    return UpdateItemRequest.builder()
        .tableName(snapshotTableName)
        .key(
            Map.of(
                "pkey",
                AttributeValue.builder().s(pkey).build(),
                "skey",
                AttributeValue.builder().s(skey).build()))
        .updateExpression("SET #ttl = :ttl")
        .expressionAttributeNames(Map.of("#ttl", "ttl"))
        .expressionAttributeValues(
            Map.of(":ttl", AttributeValue.builder().n(String.valueOf(seconds)).build()))
        .build();
  }

  BatchWriteItemRequest batchDeleteSnapshotRequest(
      io.vavr.collection.List<Tuple2<String, String>> keys) {
    var requestItems = keys.map(EventStoreSupport::deleteSnapshotRequest).toJavaList();
    return BatchWriteItemRequest.builder()
        .requestItems(Map.of(snapshotTableName, requestItems))
        .build();
  }

  private static WriteRequest deleteSnapshotRequest(Tuple2<String, String> key) {
    return WriteRequest.builder()
        .deleteRequest(
            DeleteRequest.builder()
                .key(
                    Map.of(
                        "pkey",
                        AttributeValue.builder().s(key._1).build(),
                        "skey",
                        AttributeValue.builder().s(key._2).build()))
                .build())
        .build();
  }
}
