package com.github.j5ik2o.event.store.adapter.java.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.testcontainers.containers.localstack.LocalStackContainer.Service.DYNAMODB;

import com.github.j5ik2o.event.store.adapter.java.*;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.localstack.LocalStackContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ScanRequest;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;

@Testcontainers
public class EventStoreForDynamoDBTest {

  private static final Logger LOGGER = LoggerFactory.getLogger(EventStoreForDynamoDBTest.class);

  private static final String JOURNAL_TABLE_NAME = "journal";
  private static final String SNAPSHOT_TABLE_NAME = "snapshot";

  private static final String JOURNAL_AID_INDEX_NAME = "journal-aid-index";
  private static final String SNAPSHOT_AID_INDEX_NAME = "snapshot-aid-index";

  DockerImageName localstackImage = DockerImageName.parse("localstack/localstack:2.1.0");

  @Container
  public LocalStackContainer localstack =
      new LocalStackContainer(localstackImage).withServices(DYNAMODB);

  @Test
  public void persistAndGet()
      throws SerializationException,
          EventStoreWriteException,
          EventStoreReadException,
          OptimisticLockException,
          DeserializationException {
    try (var client = DynamoDBUtils.createDynamoDbClient(localstack)) {
      DynamoDBUtils.createJournalTable(client, JOURNAL_TABLE_NAME, JOURNAL_AID_INDEX_NAME);
      DynamoDBUtils.createSnapshotTable(client, SNAPSHOT_TABLE_NAME, SNAPSHOT_AID_INDEX_NAME);
      client.listTables().tableNames().forEach(System.out::println);

      EventStore<UserAccountId, UserAccount, UserAccountEvent> eventStore =
          EventStore.ofDynamoDB(
              client,
              JOURNAL_TABLE_NAME,
              SNAPSHOT_TABLE_NAME,
              JOURNAL_AID_INDEX_NAME,
              SNAPSHOT_AID_INDEX_NAME,
              32);

      var id = new UserAccountId(IdGenerator.generate().toString());
      var aggregateAndEvent = UserAccount.create(id, "test-1");
      try {
        eventStore.persistEventAndSnapshot(
            aggregateAndEvent.getEvent(), aggregateAndEvent.getAggregate());
      } catch (com.github.j5ik2o.event.store.adapter.java.OptimisticLockException e) {
        throw new OptimisticLockException(e);
      }

      var result = eventStore.getLatestSnapshotById(UserAccount.class, id);
      if (result.isPresent()) {
        assertEquals(result.get().getId(), aggregateAndEvent.getAggregate().getId());
        LOGGER.info("result = {}", result.get());
      } else {
        fail("result is empty");
      }
    }
  }

  @Test
  public void repositoryStoreAndFindById() {
    try (var client = DynamoDBUtils.createDynamoDbClient(localstack)) {
      DynamoDBUtils.createJournalTable(client, JOURNAL_TABLE_NAME, JOURNAL_AID_INDEX_NAME);
      DynamoDBUtils.createSnapshotTable(client, SNAPSHOT_TABLE_NAME, SNAPSHOT_AID_INDEX_NAME);
      client.listTables().tableNames().forEach(System.out::println);

      EventStore<UserAccountId, UserAccount, UserAccountEvent> eventStore =
          EventStore.ofDynamoDB(
              client,
              JOURNAL_TABLE_NAME,
              SNAPSHOT_TABLE_NAME,
              JOURNAL_AID_INDEX_NAME,
              SNAPSHOT_AID_INDEX_NAME,
              32);
      var userAccountRepository = new UserAccountRepository(eventStore);

      var id = new UserAccountId(IdGenerator.generate().toString());
      var aggregateAndEvent1 = UserAccount.create(id, "test-1");
      var aggregate1 = aggregateAndEvent1.getAggregate();

      userAccountRepository.store(aggregateAndEvent1.getEvent(), aggregate1);

      var aggregateAndEvent2 = aggregate1.changeName("test-2");

      userAccountRepository.store(
          aggregateAndEvent2.getEvent(), aggregateAndEvent2.getAggregate().getVersion());

      var result = userAccountRepository.findById(id);
      if (result.isPresent()) {
        assertEquals(result.get().getId(), aggregateAndEvent2.getAggregate().getId());
        assertEquals(result.get().getName(), "test-2");
        assertEquals(result.get().getSequenceNumber(), 2L);
        assertEquals(result.get().getVersion(), 2L);
      } else {
        fail("result is empty");
      }
    }
  }

  @Test
  public void readsAllEventsAcrossOneMegabytePages() throws Exception {
    try (var client = DynamoDBUtils.createDynamoDbClient(localstack)) {
      DynamoDBUtils.createJournalTable(client, JOURNAL_TABLE_NAME, JOURNAL_AID_INDEX_NAME);
      DynamoDBUtils.createSnapshotTable(client, SNAPSHOT_TABLE_NAME, SNAPSHOT_AID_INDEX_NAME);
      EventStore<UserAccountId, UserAccount, UserAccountEvent> store =
          EventStore.ofDynamoDB(
              client,
              JOURNAL_TABLE_NAME,
              SNAPSHOT_TABLE_NAME,
              JOURNAL_AID_INDEX_NAME,
              SNAPSHOT_AID_INDEX_NAME,
              32);
      var id = new UserAccountId(IdGenerator.generate().toString());
      var created = UserAccount.create(id, "x".repeat(100_000));
      store.persistEventAndSnapshot(created.getEvent(), created.getAggregate());
      var aggregate = created.getAggregate();
      for (int i = 2; i <= 16; i++) {
        var changed = aggregate.changeName("x".repeat(100_000));
        store.persistEvent(changed.getEvent(), aggregate.getVersion());
        aggregate = changed.getAggregate().withVersion(i);
      }
      var events = store.getEventsByIdSinceSequenceNumber(UserAccountEvent.class, id, 1);
      assertEquals(16, events.size());
      for (int i = 0; i < events.size(); i++) {
        assertEquals(i + 1L, events.get(i).getSequenceNumber());
      }
      assertEquals(
          6, store.getEventsByIdSinceSequenceNumber(UserAccountEvent.class, id, 11).size());
      assertEquals(
          0, store.getEventsByIdSinceSequenceNumber(UserAccountEvent.class, id, 17).size());
    }
  }

  @Test
  public void retainsNewestSnapshots() throws Exception {
    try (var client = DynamoDBUtils.createDynamoDbClient(localstack)) {
      DynamoDBUtils.createJournalTable(client, JOURNAL_TABLE_NAME, JOURNAL_AID_INDEX_NAME);
      DynamoDBUtils.createSnapshotTable(client, SNAPSHOT_TABLE_NAME, SNAPSHOT_AID_INDEX_NAME);
      EventStore<UserAccountId, UserAccount, UserAccountEvent> store =
          EventStore.<UserAccountId, UserAccount, UserAccountEvent>ofDynamoDB(
                  client,
                  JOURNAL_TABLE_NAME,
                  SNAPSHOT_TABLE_NAME,
                  JOURNAL_AID_INDEX_NAME,
                  SNAPSHOT_AID_INDEX_NAME,
                  32)
              .withKeepSnapshotCount(2);
      var id = new UserAccountId(IdGenerator.generate().toString());
      var created = UserAccount.create(id, "first");
      store.persistEventAndSnapshot(created.getEvent(), created.getAggregate());
      var aggregate = created.getAggregate();
      for (int i = 2; i <= 5; i++) {
        var changed = aggregate.changeName("name-" + i);
        store.persistEventAndSnapshot(changed.getEvent(), changed.getAggregate());
        aggregate = changed.getAggregate().withVersion(i);
      }
      var snapshots =
          client.scan(ScanRequest.builder().tableName(SNAPSHOT_TABLE_NAME).build()).items();
      var sequences =
          snapshots.stream()
              .map(item -> Long.parseLong(item.get("seq_nr").n()))
              .sorted()
              .collect(Collectors.toList());
      assertEquals(List.of(0L, 4L, 5L), sequences);
    }
  }

  @Test
  public void countsAndPurgesSnapshotsAcrossOneMegabytePages() throws Exception {
    try (var client = DynamoDBUtils.createDynamoDbClient(localstack)) {
      DynamoDBUtils.createJournalTable(client, JOURNAL_TABLE_NAME, JOURNAL_AID_INDEX_NAME);
      DynamoDBUtils.createSnapshotTable(client, SNAPSHOT_TABLE_NAME, SNAPSHOT_AID_INDEX_NAME);
      EventStore<UserAccountId, UserAccount, UserAccountEvent> store =
          EventStore.<UserAccountId, UserAccount, UserAccountEvent>ofDynamoDB(
                  client,
                  JOURNAL_TABLE_NAME,
                  SNAPSHOT_TABLE_NAME,
                  JOURNAL_AID_INDEX_NAME,
                  SNAPSHOT_AID_INDEX_NAME,
                  32)
              .withKeepSnapshotCount(100);
      var id = new UserAccountId(IdGenerator.generate().toString());
      var created = UserAccount.create(id, "x".repeat(100_000));
      store.persistEventAndSnapshot(created.getEvent(), created.getAggregate());
      var aggregate = created.getAggregate();
      for (int i = 2; i <= 16; i++) {
        var changed = aggregate.changeName("x".repeat(100_000));
        store.persistEventAndSnapshot(changed.getEvent(), changed.getAggregate());
        aggregate = changed.getAggregate().withVersion(i);
      }
      var changed = aggregate.changeName("trigger retention");
      store.withKeepSnapshotCount(2).persistEvent(changed.getEvent(), aggregate.getVersion());
      var snapshots =
          client.scan(ScanRequest.builder().tableName(SNAPSHOT_TABLE_NAME).build()).items();
      var sequences =
          snapshots.stream()
              .map(item -> Long.parseLong(item.get("seq_nr").n()))
              .sorted()
              .collect(Collectors.toList());
      assertEquals(List.of(0L, 15L, 16L), sequences);
    }
  }

  @Test
  public void marksOldSnapshotsWithEpochSecondTtl() throws Exception {
    try (var client = DynamoDBUtils.createDynamoDbClient(localstack)) {
      DynamoDBUtils.createJournalTable(client, JOURNAL_TABLE_NAME, JOURNAL_AID_INDEX_NAME);
      DynamoDBUtils.createSnapshotTable(client, SNAPSHOT_TABLE_NAME, SNAPSHOT_AID_INDEX_NAME);
      EventStore<UserAccountId, UserAccount, UserAccountEvent> store =
          EventStore.<UserAccountId, UserAccount, UserAccountEvent>ofDynamoDB(
                  client,
                  JOURNAL_TABLE_NAME,
                  SNAPSHOT_TABLE_NAME,
                  JOURNAL_AID_INDEX_NAME,
                  SNAPSHOT_AID_INDEX_NAME,
                  32)
              .withKeepSnapshotCount(2)
              .withDeleteTtl(Duration.ofHours(1));
      var id = new UserAccountId(IdGenerator.generate().toString());
      var created = UserAccount.create(id, "first");
      store.persistEventAndSnapshot(created.getEvent(), created.getAggregate());
      var changed = created.getAggregate().changeName("second");
      store.persistEventAndSnapshot(changed.getEvent(), changed.getAggregate());
      var before = Instant.now().plusSeconds(3600).getEpochSecond();
      var third = changed.getAggregate().withVersion(2).changeName("third");
      store.persistEventAndSnapshot(third.getEvent(), third.getAggregate());
      var after = Instant.now().plusSeconds(3600).getEpochSecond();
      var snapshots =
          client.scan(ScanRequest.builder().tableName(SNAPSHOT_TABLE_NAME).build()).items();
      assertEquals(4, snapshots.size());
      for (var item : snapshots) {
        var sequence = Long.parseLong(item.get("seq_nr").n());
        var ttl = Long.parseLong(item.get("ttl").n());
        if (sequence == 1) {
          assertTrue(
              ttl >= before && ttl <= after,
              "old snapshot TTL must be marking time plus grace, but was " + ttl);
        } else {
          assertEquals(0L, ttl, "latest and retained snapshots must remain unmarked");
        }
      }
    }
  }

  @Test
  public void excludesAlreadyMarkedSnapshotsFromRetention() throws Exception {
    try (var client = DynamoDBUtils.createDynamoDbClient(localstack)) {
      DynamoDBUtils.createJournalTable(client, JOURNAL_TABLE_NAME, JOURNAL_AID_INDEX_NAME);
      DynamoDBUtils.createSnapshotTable(client, SNAPSHOT_TABLE_NAME, SNAPSHOT_AID_INDEX_NAME);
      EventStore<UserAccountId, UserAccount, UserAccountEvent> store =
          EventStore.<UserAccountId, UserAccount, UserAccountEvent>ofDynamoDB(
                  client,
                  JOURNAL_TABLE_NAME,
                  SNAPSHOT_TABLE_NAME,
                  JOURNAL_AID_INDEX_NAME,
                  SNAPSHOT_AID_INDEX_NAME,
                  32)
              .withKeepSnapshotCount(100)
              .withDeleteTtl(Duration.ofHours(1));
      var id = new UserAccountId(IdGenerator.generate().toString());
      var created = UserAccount.create(id, "first");
      store.persistEventAndSnapshot(created.getEvent(), created.getAggregate());
      var second = created.getAggregate().changeName("second");
      store.persistEventAndSnapshot(second.getEvent(), second.getAggregate());
      var third = second.getAggregate().withVersion(2).changeName("third");
      store.persistEventAndSnapshot(third.getEvent(), third.getAggregate());
      var keyResolver = new DefaultKeyResolver<UserAccountId>();
      var previousTtl = Instant.now().plusSeconds(60).getEpochSecond();
      client.updateItem(
          UpdateItemRequest.builder()
              .tableName(SNAPSHOT_TABLE_NAME)
              .key(
                  Map.of(
                      "pkey",
                          AttributeValue.builder()
                              .s(keyResolver.resolvePartitionKey(id, 32))
                              .build(),
                      "skey",
                          AttributeValue.builder().s(keyResolver.resolveSortKey(id, 3)).build()))
              .updateExpression("SET #ttl = :ttl")
              .expressionAttributeNames(Map.of("#ttl", "ttl"))
              .expressionAttributeValues(
                  Map.of(":ttl", AttributeValue.builder().n(String.valueOf(previousTtl)).build()))
              .build());
      var before = Instant.now().plusSeconds(3600).getEpochSecond();
      var fourth = third.getAggregate().withVersion(3).changeName("fourth");
      store
          .withKeepSnapshotCount(2)
          .persistEventAndSnapshot(fourth.getEvent(), fourth.getAggregate());
      var after = Instant.now().plusSeconds(3600).getEpochSecond();
      var snapshots =
          client.scan(ScanRequest.builder().tableName(SNAPSHOT_TABLE_NAME).build()).items();
      assertEquals(5, snapshots.size());
      var ttls =
          snapshots.stream()
              .collect(
                  Collectors.toMap(
                      item -> Long.parseLong(item.get("seq_nr").n()),
                      item -> Long.parseLong(item.get("ttl").n())));
      assertEquals(0L, ttls.get(0L));
      assertTrue(ttls.get(1L) >= before && ttls.get(1L) <= after);
      assertEquals(0L, ttls.get(2L), "second snapshot must remain unmarked");
      assertEquals(previousTtl, ttls.get(3L), "existing expiration must not change");
      assertEquals(0L, ttls.get(4L));
    }
  }

  @Test
  public void countsAndMarksUnmarkedSnapshotsAcrossPages() throws Exception {
    try (var client = DynamoDBUtils.createDynamoDbClient(localstack)) {
      DynamoDBUtils.createJournalTable(client, JOURNAL_TABLE_NAME, JOURNAL_AID_INDEX_NAME);
      DynamoDBUtils.createSnapshotTable(client, SNAPSHOT_TABLE_NAME, SNAPSHOT_AID_INDEX_NAME);
      EventStore<UserAccountId, UserAccount, UserAccountEvent> store =
          EventStore.<UserAccountId, UserAccount, UserAccountEvent>ofDynamoDB(
                  client,
                  JOURNAL_TABLE_NAME,
                  SNAPSHOT_TABLE_NAME,
                  JOURNAL_AID_INDEX_NAME,
                  SNAPSHOT_AID_INDEX_NAME,
                  32)
              .withKeepSnapshotCount(100)
              .withDeleteTtl(Duration.ofHours(1));
      var id = new UserAccountId(IdGenerator.generate().toString());
      var created = UserAccount.create(id, "x".repeat(100_000));
      store.persistEventAndSnapshot(created.getEvent(), created.getAggregate());
      var aggregate = created.getAggregate();
      for (int i = 2; i <= 16; i++) {
        var changed = aggregate.changeName("x".repeat(100_000));
        store.persistEventAndSnapshot(changed.getEvent(), changed.getAggregate());
        aggregate = changed.getAggregate().withVersion(i);
      }
      var keyResolver = new DefaultKeyResolver<UserAccountId>();
      var marked = List.of(1L, 3L, 5L, 16L);
      var previousTtl = Instant.now().plusSeconds(60).getEpochSecond();
      for (var sequence : marked) {
        client.updateItem(
            UpdateItemRequest.builder()
                .tableName(SNAPSHOT_TABLE_NAME)
                .key(
                    Map.of(
                        "pkey",
                            AttributeValue.builder()
                                .s(keyResolver.resolvePartitionKey(id, 32))
                                .build(),
                        "skey",
                            AttributeValue.builder()
                                .s(keyResolver.resolveSortKey(id, sequence))
                                .build()))
                .updateExpression("SET #ttl = :ttl")
                .expressionAttributeNames(Map.of("#ttl", "ttl"))
                .expressionAttributeValues(
                    Map.of(":ttl", AttributeValue.builder().n(String.valueOf(previousTtl)).build()))
                .build());
      }
      // Legacy rows without a TTL attribute also count as unmarked snapshots.
      client.updateItem(
          UpdateItemRequest.builder()
              .tableName(SNAPSHOT_TABLE_NAME)
              .key(
                  Map.of(
                      "pkey",
                          AttributeValue.builder()
                              .s(keyResolver.resolvePartitionKey(id, 32))
                              .build(),
                      "skey",
                          AttributeValue.builder().s(keyResolver.resolveSortKey(id, 2)).build()))
              .updateExpression("REMOVE #ttl")
              .expressionAttributeNames(Map.of("#ttl", "ttl"))
              .build());
      var changed = aggregate.changeName("trigger retention");
      var before = Instant.now().plusSeconds(3600).getEpochSecond();
      store
          .withKeepSnapshotCount(2)
          .persistEventAndSnapshot(changed.getEvent(), changed.getAggregate());
      var after = Instant.now().plusSeconds(3600).getEpochSecond();
      var ttls = new java.util.HashMap<Long, Long>();
      var request = ScanRequest.builder().tableName(SNAPSHOT_TABLE_NAME).build();
      while (true) {
        var response = client.scan(request);
        for (var item : response.items()) {
          ttls.put(Long.parseLong(item.get("seq_nr").n()), Long.parseLong(item.get("ttl").n()));
        }
        if (!response.hasLastEvaluatedKey()) {
          break;
        }
        request = request.toBuilder().exclusiveStartKey(response.lastEvaluatedKey()).build();
      }
      assertEquals(18, ttls.size());
      for (var entry : ttls.entrySet()) {
        var sequence = entry.getKey();
        var ttl = entry.getValue();
        if (marked.contains(sequence)) {
          assertEquals(previousTtl, ttl, "existing expiration must not change");
        } else if (sequence == 0 || sequence == 15 || sequence == 17) {
          assertEquals(0L, ttl, "latest and newest unmarked snapshots must remain unmarked");
        } else {
          assertTrue(
              ttl >= before && ttl <= after,
              "old unmarked snapshot must receive a TTL: " + sequence);
        }
      }
      // A repeated purge must traverse empty filtered pages and preserve all existing TTLs.
      var next = changed.getAggregate().withVersion(17).changeName("purge again");
      store.withKeepSnapshotCount(1).persistEvent(next.getEvent(), 17);
      var finalTtls = new java.util.HashMap<Long, Long>();
      request = ScanRequest.builder().tableName(SNAPSHOT_TABLE_NAME).build();
      while (true) {
        var response = client.scan(request);
        for (var item : response.items()) {
          finalTtls.put(
              Long.parseLong(item.get("seq_nr").n()), Long.parseLong(item.get("ttl").n()));
        }
        if (!response.hasLastEvaluatedKey()) {
          break;
        }
        request = request.toBuilder().exclusiveStartKey(response.lastEvaluatedKey()).build();
      }
      for (var entry : ttls.entrySet()) {
        if (entry.getKey() == 15) {
          assertTrue(
              finalTtls.get(15L) > 0,
              "oldest unmarked snapshot must be found after filtered pages");
        } else {
          assertEquals(
              entry.getValue(), finalTtls.get(entry.getKey()), "existing TTL must not change");
        }
      }
    }
  }
}
