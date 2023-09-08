package com.github.j5ik2o.event_store_adatpter_java.internal; /*
                                                               * Copyright 2023 Junichi Kato
                                                               *
                                                               * Licensed under the Apache License, Version 2.0 (the "License");
                                                               * you may not use this file except in compliance with the License.
                                                               * You may obtain a copy of the License at
                                                               *
                                                               *     http://www.apache.org/licenses/LICENSE-2.0
                                                               *
                                                               * Unless required by applicable law or agreed to in writing, software
                                                               * distributed under the License is distributed on an "AS IS" BASIS,
                                                               * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
                                                               * See the License for the specific language governing permissions and
                                                               * limitations under the License.
                                                               */

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.j5ik2o.event_store_adatpter_java.Aggregate;
import com.github.j5ik2o.event_store_adatpter_java.AggregateId;
import com.github.j5ik2o.event_store_adatpter_java.AggregateWithSeqNrWithVersion;
import com.github.j5ik2o.event_store_adatpter_java.Event;
import com.github.j5ik2o.event_store_adatpter_java.EventSerializer;
import com.github.j5ik2o.event_store_adatpter_java.EventStore;
import com.github.j5ik2o.event_store_adatpter_java.KeyResolver;
import com.github.j5ik2o.event_store_adatpter_java.SnapshotSerializer;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.*;

public class EventStoreForDynamoDB<
        AID extends AggregateId, A extends Aggregate<AID>, E extends Event<AID>>
    implements EventStore<AID, A, E> {

  private static final Logger LOGGER = LoggerFactory.getLogger(EventStoreForDynamoDB.class);

  private final DynamoDbAsyncClient dynamoDbAsyncClient;
  private final String journalTableName;
  private final String snapshotTableName;
  private final String journalAidIndexName;
  private final String snapshotAidIndexName;
  private final long shardCount;

  private Optional<Long> keepSnapshotCount;

  private Optional<Duration> deleteTtl;

  private KeyResolver<AID> keyResolver;

  private EventSerializer<E> eventSerializer;

  private SnapshotSerializer<AID, A> snapshotSerializer;

  public EventStoreForDynamoDB(
      DynamoDbAsyncClient dynamoDbAsyncClient,
      String journalTableName,
      String snapshotTableName,
      String journalAidIndexName,
      String snapshotAidIndexName,
      long shardCount) {
    if (dynamoDbAsyncClient == null) {
      throw new IllegalArgumentException("dynamoDbAsyncClient is null");
    }
    if (journalTableName == null) {
      throw new IllegalArgumentException("journalTableName is null");
    }
    if (snapshotTableName == null) {
      throw new IllegalArgumentException("snapshotTableName is null");
    }
    if (journalAidIndexName == null) {
      throw new IllegalArgumentException("journalAidIndexName is null");
    }
    if (snapshotAidIndexName == null) {
      throw new IllegalArgumentException("snapshotAidIndexName is null");
    }
    this.dynamoDbAsyncClient = dynamoDbAsyncClient;
    this.journalTableName = journalTableName;
    this.snapshotTableName = snapshotTableName;
    this.journalAidIndexName = journalAidIndexName;
    this.snapshotAidIndexName = snapshotAidIndexName;
    this.shardCount = shardCount;
    this.keepSnapshotCount = Optional.empty();
    this.deleteTtl = Optional.empty();
    this.keyResolver = new DefaultKeyResolver<>();
    var objectMapper = new ObjectMapper();
    this.eventSerializer = new JsonEventSerializer<>(objectMapper);
    this.snapshotSerializer = new JsonSnapshotSerializer<>(objectMapper);
  }

  public EventStoreForDynamoDB<AID, A, E> withKeepSnapshotCount(long keepSnapshotCount) {
    EventStoreForDynamoDB<AID, A, E> self =
        new EventStoreForDynamoDB<>(
            dynamoDbAsyncClient,
            journalTableName,
            snapshotTableName,
            journalAidIndexName,
            snapshotAidIndexName,
            shardCount);
    self.keepSnapshotCount = Optional.of(Long.valueOf(keepSnapshotCount));
    return self;
  }

  public EventStoreForDynamoDB<AID, A, E> withDeleteTtl(Duration deleteTtl) {
    EventStoreForDynamoDB<AID, A, E> self =
        new EventStoreForDynamoDB<>(
            dynamoDbAsyncClient,
            journalTableName,
            snapshotTableName,
            journalAidIndexName,
            snapshotAidIndexName,
            shardCount);
    self.deleteTtl = Optional.of(deleteTtl);
    return self;
  }

  public EventStoreForDynamoDB<AID, A, E> withKeyResolver(KeyResolver<AID> keyResolver) {
    EventStoreForDynamoDB<AID, A, E> self =
        new EventStoreForDynamoDB<>(
            dynamoDbAsyncClient,
            journalTableName,
            snapshotTableName,
            journalAidIndexName,
            snapshotAidIndexName,
            shardCount);
    self.keyResolver = keyResolver;
    return self;
  }

  public EventStoreForDynamoDB<AID, A, E> withEventSerializer(EventSerializer<E> eventSerializer) {
    EventStoreForDynamoDB<AID, A, E> self =
        new EventStoreForDynamoDB<>(
            dynamoDbAsyncClient,
            journalTableName,
            snapshotTableName,
            journalAidIndexName,
            snapshotAidIndexName,
            shardCount);
    self.eventSerializer = eventSerializer;
    return self;
  }

  public EventStoreForDynamoDB<AID, A, E> withSnapshotSerializer(
      SnapshotSerializer<AID, A> snapshotSerializer) {
    EventStoreForDynamoDB<AID, A, E> self =
        new EventStoreForDynamoDB<>(
            dynamoDbAsyncClient,
            journalTableName,
            snapshotTableName,
            journalAidIndexName,
            snapshotAidIndexName,
            shardCount);
    self.snapshotSerializer = snapshotSerializer;
    return self;
  }

  @Override
  public CompletableFuture<Optional<AggregateWithSeqNrWithVersion<A>>> getLatestSnapshotById(
      Class<A> clazz, AID aggregateId) {
    if (aggregateId == null) {
      throw new IllegalArgumentException("aggregateId is null");
    }
    var result =
        dynamoDbAsyncClient.query(
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
                .build());
    return result.thenApply(
        response -> {
          var items = response.items();
          if (items.size() != 1) {
            throw new RuntimeException("Unexpected result size");
          }
          var item = items.get(0);
          var bytes = item.get("payload").b().asByteArray();
          var aggregate = snapshotSerializer.deserialize(bytes, clazz);
          var version = Long.parseLong(item.get("version").n());
          var seqNr = aggregate.getSeqNr();
          return Optional.of(new AggregateWithSeqNrWithVersion<>(aggregate, seqNr, version));
        });
  }

  @Override
  public CompletableFuture<List<E>> getEventsByIdSinceSeqNr(
      Class<E> clazz, AID aggregateId, long seqNr) {
    if (aggregateId == null) {
      throw new IllegalArgumentException("aggregateId is null");
    }
    var result =
        dynamoDbAsyncClient.query(
            QueryRequest.builder()
                .tableName(journalTableName)
                .indexName(journalAidIndexName)
                .keyConditionExpression("#aid = :aid and #seq_nr > :seq_nr")
                .expressionAttributeNames(
                    Map.of(
                        "#aid", "aid",
                        "#seq_nr", "seq_nr"))
                .expressionAttributeValues(
                    Map.of(
                        ":aid", AttributeValue.builder().s(aggregateId.toString()).build(),
                        ":seq_nr", AttributeValue.builder().n(String.valueOf(seqNr)).build()))
                .build());
    return result.thenApply(
        response -> {
          var items = response.items();
          var events = new java.util.ArrayList<E>();
          for (var item : items) {
            var bytes = item.get("payload").b().asByteArray();
            var event = eventSerializer.deserialize(bytes, null);
            events.add(event);
          }
          return events;
        });
  }

  @Override
  public CompletableFuture<Void> storeEvent(E event, long snapshotVersion) {
    if (event == null) {
      throw new IllegalArgumentException("event is null");
    }
    if (event.isCreated()) {
      throw new IllegalArgumentException("event is created");
    }
    return updateEventAndSnapshotOpt(event, snapshotVersion, null).thenRun(() -> {});
  }

  @Override
  public CompletableFuture<Void> storeEventAndSnapshot(E event, long version, A aggregate) {
    if (event == null) {
      throw new IllegalArgumentException("event is null");
    }
    if (event.isCreated()) {
      return createEventAndSnapshot(event, aggregate).thenRun(() -> {});
    } else {
      return updateEventAndSnapshotOpt(event, version, aggregate).thenRun(() -> {});
    }
  }

  private CompletableFuture<TransactWriteItemsResponse> createEventAndSnapshot(
      E event, A aggregate) {
    List<TransactWriteItem> transactItems = new java.util.ArrayList<>();
    transactItems.add(putSnapshot(event, 0, aggregate));
    transactItems.add(putJournal(event));
    if (keepSnapshotCount.isPresent()) {
      transactItems.add(putSnapshot(event, aggregate.getSeqNr(), aggregate));
    }
    return dynamoDbAsyncClient.transactWriteItems(
        TransactWriteItemsRequest.builder().transactItems(transactItems).build());
  }

  private CompletableFuture<TransactWriteItemsResponse> updateEventAndSnapshotOpt(
      E event, long version, A aggregate) {
    List<TransactWriteItem> transactItems = new java.util.ArrayList<>();
    transactItems.add(updateSnapshot(event, 0, version, aggregate));
    transactItems.add(putJournal(event));
    if (keepSnapshotCount.isPresent() && aggregate != null) {
      transactItems.add(putSnapshot(event, aggregate.getSeqNr(), aggregate));
    }
    return dynamoDbAsyncClient.transactWriteItems(
        TransactWriteItemsRequest.builder().transactItems(transactItems).build());
  }

  private String resolvePartitionKey(AID aggregateId, long shardCount) {
    return keyResolver.resolvePartitionKey(aggregateId, shardCount);
  }

  private String resolveSortKey(AID aggregateId, long seqNr) {
    return keyResolver.resolveSortKey(aggregateId, seqNr);
  }

  private TransactWriteItem putSnapshot(E event, long seqNr, A aggregate) {
    var pkey = resolvePartitionKey(event.getAggregateId(), shardCount);
    var skey = resolveSortKey(event.getAggregateId(), seqNr);
    var payload = snapshotSerializer.serialize(aggregate);
    LOGGER.debug(">--- put snapshot ---");
    LOGGER.debug("pkey = {}", pkey);
    LOGGER.debug("skey = {}", skey);
    LOGGER.debug("aid = {}", event.getAggregateId().toString());
    LOGGER.debug("seq_nr = {}", seqNr);
    LOGGER.debug("payload = {}", payload);
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
                    AttributeValue.builder().s(event.getAggregateId().toString()).build(),
                    "seq_nr",
                    AttributeValue.builder().n(String.valueOf(seqNr)).build(),
                    "ttl",
                    AttributeValue.builder().n("0").build(),
                    "last_updated_at",
                    AttributeValue.builder()
                        .n(String.valueOf(event.getOccurredAt().toEpochMilli()))
                        .build()))
            .conditionExpression("attribute_not_exists(pkey) AND attribute_not_exists(skey)")
            .build();
    return TransactWriteItem.builder().put(put).build();
  }

  private TransactWriteItem putJournal(E event) {
    var pkey = resolvePartitionKey(event.getAggregateId(), shardCount);
    var skey = resolveSortKey(event.getAggregateId(), event.getSeqNr());
    var aid = event.getAggregateId().toString();
    var seqNr = event.getSeqNr();
    var payload = eventSerializer.serialize(event);
    var occurredAt = String.valueOf(event.getOccurredAt().toEpochMilli());
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
                    AttributeValue.builder().n(String.valueOf(seqNr)).build(),
                    "payload",
                    AttributeValue.builder().b(SdkBytes.fromByteArray(payload)).build(),
                    "occurred_at",
                    AttributeValue.builder().n(occurredAt).build()))
            .build();
    return TransactWriteItem.builder().put(put).build();
  }

  private TransactWriteItem updateSnapshot(E event, long seqNr, long version, A aggregate) {
    var pkey = resolvePartitionKey(event.getAggregateId(), shardCount);
    var skey = resolveSortKey(event.getAggregateId(), seqNr);
    LOGGER.debug(">--- update snapshot ---");
    LOGGER.debug("pkey = {}", pkey);
    LOGGER.debug("skey = {}", skey);
    LOGGER.debug("aid = {}", event.getAggregateId().toString());
    LOGGER.debug("seq_nr = {}", seqNr);
    // LOGGER.debug("payload = {}", payload);
    LOGGER.debug("<--- put snapshot ---");
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
            .expressionAttributeNames(
                Map.of("#version", "version", "#last_updated_at", "last_updated_at"))
            .expressionAttributeValues(
                Map.of(
                    ":before_version",
                    AttributeValue.builder().n(String.valueOf(version)).build(),
                    ":after_version",
                    AttributeValue.builder().n(String.valueOf(version + 1)).build(),
                    ":last_updated_at",
                    AttributeValue.builder()
                        .n(String.valueOf(event.getOccurredAt().toEpochMilli()))
                        .build()))
            .conditionExpression("#version = :before_version")
            .build();
    if (aggregate != null) {
      var payload = snapshotSerializer.serialize(aggregate);
      update =
          update.toBuilder()
              .updateExpression(update.updateExpression() + ", #payload=:payload, #seq_nr=:seq_nr")
              .expressionAttributeNames(Map.of("#seq_nr", ":seq_nr", "#payload", "payload"))
              .expressionAttributeValues(
                  Map.of(
                      ":seq_nr",
                      AttributeValue.builder().n(String.valueOf(seqNr)).build(),
                      ":payload",
                      AttributeValue.builder().b(SdkBytes.fromByteArray(payload)).build()))
              .build();
    }
    return TransactWriteItem.builder().update(update).build();
  }
}
