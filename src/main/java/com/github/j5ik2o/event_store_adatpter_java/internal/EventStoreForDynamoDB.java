package com.github.j5ik2o.event_store_adatpter_java.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.j5ik2o.event_store_adatpter_java.*;
import java.time.*;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import javax.annotation.Nonnull;
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

  private Long keepSnapshotCount;

  private Duration deleteTtl;

  private KeyResolver<AID> keyResolver;

  private EventSerializer<AID, E> eventSerializer;

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
    this.keepSnapshotCount = null;
    this.deleteTtl = null;
    this.keyResolver = new DefaultKeyResolver<>();
    var objectMapper = new ObjectMapper();
    objectMapper.findAndRegisterModules();
    //    var javaTimeModule = new JavaTimeModule();
    //    javaTimeModule.addDeserializer(LocalDateTime.class, new
    // LocalDateTimeDeserializer(DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss")));
    //    objectMapper.registerModule(javaTimeModule);
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
    self.keepSnapshotCount = keepSnapshotCount;
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
    self.deleteTtl = deleteTtl;
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

  public EventStoreForDynamoDB<AID, A, E> withEventSerializer(
      EventSerializer<AID, E> eventSerializer) {
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

  @Nonnull
  @Override
  public CompletableFuture<Optional<AggregateAndVersion<AID, A>>> getLatestSnapshotById(
      Class<A> clazz, AID aggregateId) {
    LOGGER.debug("getLatestSnapshotById({}, {}): start", clazz, aggregateId);
    if (aggregateId == null) {
      throw new IllegalArgumentException("aggregateId is null");
    }
    var queryResult =
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
    var result =
        queryResult.thenApply(
            response -> {
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
                var seqNr = aggregate.getSequenceNumber();
                LOGGER.debug("seqNr = {}", seqNr);
                applyResult = Optional.of(new AggregateAndVersion<>(aggregate, version));
              }
              return applyResult;
            });
    LOGGER.debug("getLatestSnapshotById({}, {}): finished", clazz, aggregateId);
    return result;
  }

  @Nonnull
  @Override
  public CompletableFuture<List<E>> getEventsByIdSinceSeqNr(
      Class<E> clazz, AID aggregateId, long sequenceNumber) {
    LOGGER.debug("getEventsByIdSinceSeqNr({}, {}, {}): start", clazz, aggregateId, sequenceNumber);
    if (aggregateId == null) {
      throw new IllegalArgumentException("aggregateId is null");
    }
    var queryResult =
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
                        ":aid", AttributeValue.builder().s(aggregateId.asString()).build(),
                        ":seq_nr",
                            AttributeValue.builder().n(String.valueOf(sequenceNumber)).build()))
                .build());
    var result =
        queryResult.thenApply(
            response -> {
              var items = response.items();
              LOGGER.debug("items = {}", items);
              List<E> events = new java.util.ArrayList<>();
              for (var item : items) {
                var bytes = item.get("payload").b().asByteArray();
                var event = eventSerializer.deserialize(bytes, clazz);
                events.add(event);
              }
              return events;
            });
    LOGGER.debug(
        "getEventsByIdSinceSeqNr({}, {}, {}): finished", clazz, aggregateId, sequenceNumber);
    return result;
  }

  @Nonnull
  @Override
  public CompletableFuture<Void> persistEvent(E event, long version) {
    LOGGER.debug("persistEvent({}, {}): start", event, version);
    if (event == null) {
      throw new IllegalArgumentException("event is null");
    }
    if (event.isCreated()) {
      throw new IllegalArgumentException("event is created");
    }
    var result = updateEventAndSnapshotOpt(event, version, null).thenRun(() -> {});
    LOGGER.debug("persistEvent({}, {}): finished", event, version);
    return result;
  }

  @Nonnull
  @Override
  public CompletableFuture<Void> persistEventAndSnapshot(E event, A aggregate) {
    LOGGER.debug("persistEventAndSnapshot({}, {}): start", event, aggregate);
    if (event == null) {
      throw new IllegalArgumentException("event is null");
    }
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
      E event, A aggregate) {
    LOGGER.debug("createEventAndSnapshot({}, {}): start", event, aggregate);
    List<TransactWriteItem> transactItems = new java.util.ArrayList<>();
    transactItems.add(putSnapshot(event, 0, aggregate));
    transactItems.add(putJournal(event));
    if (keepSnapshotCount != null) {
      transactItems.add(putSnapshot(event, aggregate.getSequenceNumber(), aggregate));
    }
    var result =
        dynamoDbAsyncClient.transactWriteItems(
            TransactWriteItemsRequest.builder().transactItems(transactItems).build());
    LOGGER.debug("createEventAndSnapshot({}, {}): finished", event, aggregate);
    return result;
  }

  private CompletableFuture<TransactWriteItemsResponse> updateEventAndSnapshotOpt(
      E event, long version, A aggregate) {
    LOGGER.debug("updateEventAndSnapshotOpt({}, {}, {}): start", event, version, aggregate);
    List<TransactWriteItem> transactItems = new java.util.ArrayList<>();
    transactItems.add(updateSnapshot(event, 0, version, aggregate));
    transactItems.add(putJournal(event));
    if (keepSnapshotCount != null && aggregate != null) {
      transactItems.add(putSnapshot(event, aggregate.getSequenceNumber(), aggregate));
    }
    var result =
        dynamoDbAsyncClient.transactWriteItems(
            TransactWriteItemsRequest.builder().transactItems(transactItems).build());
    LOGGER.debug("updateEventAndSnapshotOpt({}, {}, {}): finished", event, version, aggregate);
    return result;
  }

  private String resolvePartitionKey(AID aggregateId, long shardCount) {
    return keyResolver.resolvePartitionKey(aggregateId, shardCount);
  }

  private String resolveSortKey(AID aggregateId, long seqNr) {
    return keyResolver.resolveSortKey(aggregateId, seqNr);
  }

  private TransactWriteItem putSnapshot(E event, long seqNr, A aggregate) {
    LOGGER.debug("putSnapshot({}, {}, {}): start", event, seqNr, aggregate);
    var pkey = resolvePartitionKey(event.getAggregateId(), shardCount);
    var skey = resolveSortKey(event.getAggregateId(), seqNr);
    var payload = snapshotSerializer.serialize(aggregate);
    LOGGER.debug(">--- put snapshot ---");
    LOGGER.debug("pkey = {}", pkey);
    LOGGER.debug("skey = {}", skey);
    LOGGER.debug("aid = {}", event.getAggregateId().asString());
    LOGGER.debug("seq_nr = {}", seqNr);
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
                    AttributeValue.builder().n(String.valueOf(seqNr)).build(),
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
    LOGGER.debug("putSnapshot({}, {}, {}): finished", event, seqNr, aggregate);
    return result;
  }

  private TransactWriteItem updateSnapshot(E event, long seqNr, long version, A aggregate) {
    LOGGER.debug("updateSnapshot({}, {}, {}): start", event, seqNr, aggregate);
    var pkey = resolvePartitionKey(event.getAggregateId(), shardCount);
    var skey = resolveSortKey(event.getAggregateId(), seqNr);
    LOGGER.debug(">--- update snapshot ---");
    LOGGER.debug("pkey = {}", pkey);
    LOGGER.debug("skey = {}", skey);
    LOGGER.debug("aid = {}", event.getAggregateId().asString());
    LOGGER.debug("seq_nr = {}", seqNr);
    LOGGER.debug("<--- update snapshot ---");

    var expressionAttributeNames =
        new java.util.HashMap<>(
            Map.of("#version", "version", "#last_updated_at", "last_updated_at"));
    var expressionAttributeValues =
        new java.util.HashMap<>(
            Map.of(
                ":before_version",
                AttributeValue.builder().n(String.valueOf(version)).build(),
                ":after_version",
                AttributeValue.builder().n(String.valueOf(version + 1)).build(),
                ":last_updated_at",
                AttributeValue.builder()
                    .n(String.valueOf(event.getOccurredAt().toEpochMilli()))
                    .build()));
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
            .expressionAttributeNames(expressionAttributeNames)
            .expressionAttributeValues(expressionAttributeValues)
            .conditionExpression("#version = :before_version");
    if (aggregate != null) {
      var payload = snapshotSerializer.serialize(aggregate);
      LOGGER.debug("payload = {}", payload);
      var expressionAttributeNames2 = Map.of("#seq_nr", ":seq_nr", "#payload", "payload");
      expressionAttributeNames.putAll(expressionAttributeNames2);
      var expressionAttributeValues2 =
          Map.of(
              ":seq_nr",
              AttributeValue.builder().n(String.valueOf(seqNr)).build(),
              ":payload",
              AttributeValue.builder().b(SdkBytes.fromByteArray(payload)).build());
      expressionAttributeValues.putAll(expressionAttributeValues2);
      update =
          update
              .updateExpression(
                  "SET #payload=:payload, #seq_nr=:seq_nr, #version=:after_version, #last_updated_at=:last_updated_at")
              .expressionAttributeNames(expressionAttributeNames)
              .expressionAttributeValues(expressionAttributeValues);
    }
    var result = TransactWriteItem.builder().update(update.build()).build();
    LOGGER.debug("updateSnapshot({}, {}, {}): finished", event, seqNr, aggregate);
    return result;
  }

  private TransactWriteItem putJournal(E event) {
    LOGGER.debug("putJournal({}): start", event);
    var pkey = resolvePartitionKey(event.getAggregateId(), shardCount);
    var skey = resolveSortKey(event.getAggregateId(), event.getSeqNr());
    var aid = event.getAggregateId().asString();
    var seqNr = event.getSeqNr();
    var payload = eventSerializer.serialize(event);
    var occurredAt = String.valueOf(event.getOccurredAt().toEpochMilli());

    LOGGER.debug(">--- put journal ---");
    LOGGER.debug("pkey = {}", pkey);
    LOGGER.debug("skey = {}", skey);
    LOGGER.debug("aid = {}", event.getAggregateId().asString());
    LOGGER.debug("seq_nr = {}", seqNr);
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
                    AttributeValue.builder().n(String.valueOf(seqNr)).build(),
                    "payload",
                    AttributeValue.builder().b(SdkBytes.fromByteArray(payload)).build(),
                    "occurred_at",
                    AttributeValue.builder().n(occurredAt).build()))
            .build();
    var result = TransactWriteItem.builder().put(put).build();
    LOGGER.debug("putJournal({}): finished", event);
    return result;
  }
}
