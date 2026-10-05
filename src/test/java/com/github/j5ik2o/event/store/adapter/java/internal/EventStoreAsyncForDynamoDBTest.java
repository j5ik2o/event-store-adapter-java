package com.github.j5ik2o.event.store.adapter.java.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.testcontainers.containers.localstack.LocalStackContainer.Service.DYNAMODB;

import com.github.j5ik2o.event.store.adapter.java.DefaultKeyResolver;
import com.github.j5ik2o.event.store.adapter.java.EventStoreAsync;
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
public class EventStoreAsyncForDynamoDBTest {

  private static final Logger LOGGER =
      LoggerFactory.getLogger(EventStoreAsyncForDynamoDBTest.class);

  private static final String JOURNAL_TABLE_NAME = "journal";
  private static final String SNAPSHOT_TABLE_NAME = "snapshot";

  private static final String JOURNAL_AID_INDEX_NAME = "journal-aid-index";
  private static final String SNAPSHOT_AID_INDEX_NAME = "snapshot-aid-index";

  DockerImageName localstackImage = DockerImageName.parse("localstack/localstack:2.1.0");

  @Container
  public LocalStackContainer localstack =
      new LocalStackContainer(localstackImage).withServices(DYNAMODB);

  @Test
  public void persistAndGet() {
    try (var client = DynamoDBAsyncUtils.createDynamoDbAsyncClient(localstack)) {
      DynamoDBAsyncUtils.createJournalTable(client, JOURNAL_TABLE_NAME, JOURNAL_AID_INDEX_NAME)
          .join();
      DynamoDBAsyncUtils.createSnapshotTable(client, SNAPSHOT_TABLE_NAME, SNAPSHOT_AID_INDEX_NAME)
          .join();
      client.listTables().join().tableNames().forEach(System.out::println);

      EventStoreAsync<UserAccountId, UserAccount, UserAccountEvent> eventStore =
          EventStoreAsync.ofDynamoDB(
              client,
              JOURNAL_TABLE_NAME,
              SNAPSHOT_TABLE_NAME,
              JOURNAL_AID_INDEX_NAME,
              SNAPSHOT_AID_INDEX_NAME,
              32);

      var id = new UserAccountId(IdGenerator.generate().toString());
      var aggregateAndEvent = UserAccount.create(id, "test-1");
      eventStore
          .persistEventAndSnapshot(aggregateAndEvent.getEvent(), aggregateAndEvent.getAggregate())
          .join();

      var result = eventStore.getLatestSnapshotById(UserAccount.class, id).join();
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
    try (var client = DynamoDBAsyncUtils.createDynamoDbAsyncClient(localstack)) {
      DynamoDBAsyncUtils.createJournalTable(client, JOURNAL_TABLE_NAME, JOURNAL_AID_INDEX_NAME)
          .join();
      DynamoDBAsyncUtils.createSnapshotTable(client, SNAPSHOT_TABLE_NAME, SNAPSHOT_AID_INDEX_NAME)
          .join();
      client.listTables().join().tableNames().forEach(System.out::println);

      EventStoreAsyncForDynamoDB<UserAccountId, UserAccount, UserAccountEvent> eventStore =
          EventStoreAsyncForDynamoDB.create(
              client,
              JOURNAL_TABLE_NAME,
              SNAPSHOT_TABLE_NAME,
              JOURNAL_AID_INDEX_NAME,
              SNAPSHOT_AID_INDEX_NAME,
              32);
      var userAccountRepository = new UserAccountRepositoryAsync(eventStore);

      var id = new UserAccountId(IdGenerator.generate().toString());
      var aggregateAndEvent1 = UserAccount.create(id, "test-1");
      var aggregate1 = aggregateAndEvent1.getAggregate();

      var result =
          userAccountRepository
              .store(aggregateAndEvent1.getEvent(), aggregate1)
              .thenCompose(
                  ignored -> {
                    var aggregateAndEvent2 = aggregate1.changeName("test-2");
                    return userAccountRepository.store(
                        aggregateAndEvent2.getEvent(),
                        aggregateAndEvent2.getAggregate().getVersion());
                  })
              .thenCompose(r -> userAccountRepository.findById(id))
              .join();

      if (result.isPresent()) {
        assertEquals(result.get().getId(), aggregateAndEvent1.getAggregate().getId());
        assertEquals(result.get().getName(), "test-2");
      } else {
        fail("result is empty");
      }
    }
  }

  @Test
  public void readsAllEventsAcrossOneMegabytePages() throws Exception {
    try (var client = DynamoDBAsyncUtils.createDynamoDbAsyncClient(localstack)) {
      DynamoDBAsyncUtils.createJournalTable(client, JOURNAL_TABLE_NAME, JOURNAL_AID_INDEX_NAME)
          .join();
      DynamoDBAsyncUtils.createSnapshotTable(client, SNAPSHOT_TABLE_NAME, SNAPSHOT_AID_INDEX_NAME)
          .join();
      EventStoreAsync<UserAccountId, UserAccount, UserAccountEvent> store =
          EventStoreAsync.ofDynamoDB(
              client,
              JOURNAL_TABLE_NAME,
              SNAPSHOT_TABLE_NAME,
              JOURNAL_AID_INDEX_NAME,
              SNAPSHOT_AID_INDEX_NAME,
              32);
      var id = new UserAccountId(IdGenerator.generate().toString());
      var created = UserAccount.create(id, "x".repeat(100_000));
      store.persistEventAndSnapshot(created.getEvent(), created.getAggregate()).join();
      var aggregate = created.getAggregate();
      for (int i = 2; i <= 16; i++) {
        var changed = aggregate.changeName("x".repeat(100_000));
        store.persistEvent(changed.getEvent(), aggregate.getVersion()).join();
        aggregate = changed.getAggregate().withVersion(i);
      }
      var events = store.getEventsByIdSinceSequenceNumber(UserAccountEvent.class, id, 1).join();
      assertEquals(16, events.size());
      for (int i = 0; i < events.size(); i++) {
        assertEquals(i + 1L, events.get(i).getSequenceNumber());
      }
      assertEquals(
          6, store.getEventsByIdSinceSequenceNumber(UserAccountEvent.class, id, 11).join().size());
      assertEquals(
          0, store.getEventsByIdSinceSequenceNumber(UserAccountEvent.class, id, 17).join().size());
    }
  }

  @Test
  public void retainsNewestSnapshots() throws Exception {
    try (var client = DynamoDBAsyncUtils.createDynamoDbAsyncClient(localstack)) {
      DynamoDBAsyncUtils.createJournalTable(client, JOURNAL_TABLE_NAME, JOURNAL_AID_INDEX_NAME)
          .join();
      DynamoDBAsyncUtils.createSnapshotTable(client, SNAPSHOT_TABLE_NAME, SNAPSHOT_AID_INDEX_NAME)
          .join();
      EventStoreAsync<UserAccountId, UserAccount, UserAccountEvent> store =
          EventStoreAsync.<UserAccountId, UserAccount, UserAccountEvent>ofDynamoDB(
                  client,
                  JOURNAL_TABLE_NAME,
                  SNAPSHOT_TABLE_NAME,
                  JOURNAL_AID_INDEX_NAME,
                  SNAPSHOT_AID_INDEX_NAME,
                  32)
              .withKeepSnapshotCount(2);
      var id = new UserAccountId(IdGenerator.generate().toString());
      var created = UserAccount.create(id, "first");
      store.persistEventAndSnapshot(created.getEvent(), created.getAggregate()).join();
      var aggregate = created.getAggregate();
      for (int i = 2; i <= 5; i++) {
        var changed = aggregate.changeName("name-" + i);
        store.persistEventAndSnapshot(changed.getEvent(), changed.getAggregate()).join();
        aggregate = changed.getAggregate().withVersion(i);
      }
      var snapshots =
          client.scan(ScanRequest.builder().tableName(SNAPSHOT_TABLE_NAME).build()).join().items();
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
    try (var client = DynamoDBAsyncUtils.createDynamoDbAsyncClient(localstack)) {
      DynamoDBAsyncUtils.createJournalTable(client, JOURNAL_TABLE_NAME, JOURNAL_AID_INDEX_NAME)
          .join();
      DynamoDBAsyncUtils.createSnapshotTable(client, SNAPSHOT_TABLE_NAME, SNAPSHOT_AID_INDEX_NAME)
          .join();
      EventStoreAsync<UserAccountId, UserAccount, UserAccountEvent> store =
          EventStoreAsync.<UserAccountId, UserAccount, UserAccountEvent>ofDynamoDB(
                  client,
                  JOURNAL_TABLE_NAME,
                  SNAPSHOT_TABLE_NAME,
                  JOURNAL_AID_INDEX_NAME,
                  SNAPSHOT_AID_INDEX_NAME,
                  32)
              .withKeepSnapshotCount(100);
      var id = new UserAccountId(IdGenerator.generate().toString());
      var created = UserAccount.create(id, "x".repeat(100_000));
      store.persistEventAndSnapshot(created.getEvent(), created.getAggregate()).join();
      var aggregate = created.getAggregate();
      for (int i = 2; i <= 16; i++) {
        var changed = aggregate.changeName("x".repeat(100_000));
        store.persistEventAndSnapshot(changed.getEvent(), changed.getAggregate()).join();
        aggregate = changed.getAggregate().withVersion(i);
      }
      var changed = aggregate.changeName("trigger retention");
      store
          .withKeepSnapshotCount(2)
          .persistEvent(changed.getEvent(), aggregate.getVersion())
          .join();
      var snapshots =
          client.scan(ScanRequest.builder().tableName(SNAPSHOT_TABLE_NAME).build()).join().items();
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
    try (var client = DynamoDBAsyncUtils.createDynamoDbAsyncClient(localstack)) {
      DynamoDBAsyncUtils.createJournalTable(client, JOURNAL_TABLE_NAME, JOURNAL_AID_INDEX_NAME)
          .join();
      DynamoDBAsyncUtils.createSnapshotTable(client, SNAPSHOT_TABLE_NAME, SNAPSHOT_AID_INDEX_NAME)
          .join();
      EventStoreAsync<UserAccountId, UserAccount, UserAccountEvent> store =
          EventStoreAsync.<UserAccountId, UserAccount, UserAccountEvent>ofDynamoDB(
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
      store.persistEventAndSnapshot(created.getEvent(), created.getAggregate()).join();
      var changed = created.getAggregate().changeName("second");
      store.persistEventAndSnapshot(changed.getEvent(), changed.getAggregate()).join();
      var before = Instant.now().plusSeconds(3600).getEpochSecond();
      var third = changed.getAggregate().withVersion(2).changeName("third");
      store.persistEventAndSnapshot(third.getEvent(), third.getAggregate()).join();
      var after = Instant.now().plusSeconds(3600).getEpochSecond();
      var snapshots =
          client.scan(ScanRequest.builder().tableName(SNAPSHOT_TABLE_NAME).build()).join().items();
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
    try (var client = DynamoDBAsyncUtils.createDynamoDbAsyncClient(localstack)) {
      DynamoDBAsyncUtils.createJournalTable(client, JOURNAL_TABLE_NAME, JOURNAL_AID_INDEX_NAME)
          .join();
      DynamoDBAsyncUtils.createSnapshotTable(client, SNAPSHOT_TABLE_NAME, SNAPSHOT_AID_INDEX_NAME)
          .join();
      EventStoreAsync<UserAccountId, UserAccount, UserAccountEvent> store =
          EventStoreAsync.<UserAccountId, UserAccount, UserAccountEvent>ofDynamoDB(
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
      store.persistEventAndSnapshot(created.getEvent(), created.getAggregate()).join();
      var second = created.getAggregate().changeName("second");
      store.persistEventAndSnapshot(second.getEvent(), second.getAggregate()).join();
      var third = second.getAggregate().withVersion(2).changeName("third");
      store.persistEventAndSnapshot(third.getEvent(), third.getAggregate()).join();
      var keyResolver = new DefaultKeyResolver<UserAccountId>();
      var previousTtl = Instant.now().plusSeconds(60).getEpochSecond();
      client
          .updateItem(
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
                                  .s(keyResolver.resolveSortKey(id, 3))
                                  .build()))
                  .updateExpression("SET #ttl = :ttl")
                  .expressionAttributeNames(Map.of("#ttl", "ttl"))
                  .expressionAttributeValues(
                      Map.of(
                          ":ttl", AttributeValue.builder().n(String.valueOf(previousTtl)).build()))
                  .build())
          .join();
      var before = Instant.now().plusSeconds(3600).getEpochSecond();
      var fourth = third.getAggregate().withVersion(3).changeName("fourth");
      store
          .withKeepSnapshotCount(2)
          .persistEventAndSnapshot(fourth.getEvent(), fourth.getAggregate())
          .join();
      var after = Instant.now().plusSeconds(3600).getEpochSecond();
      var snapshots =
          client.scan(ScanRequest.builder().tableName(SNAPSHOT_TABLE_NAME).build()).join().items();
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
    try (var client = DynamoDBAsyncUtils.createDynamoDbAsyncClient(localstack)) {
      DynamoDBAsyncUtils.createJournalTable(client, JOURNAL_TABLE_NAME, JOURNAL_AID_INDEX_NAME)
          .join();
      DynamoDBAsyncUtils.createSnapshotTable(client, SNAPSHOT_TABLE_NAME, SNAPSHOT_AID_INDEX_NAME)
          .join();
      EventStoreAsync<UserAccountId, UserAccount, UserAccountEvent> store =
          EventStoreAsync.<UserAccountId, UserAccount, UserAccountEvent>ofDynamoDB(
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
      store.persistEventAndSnapshot(created.getEvent(), created.getAggregate()).join();
      var aggregate = created.getAggregate();
      for (int i = 2; i <= 16; i++) {
        var changed = aggregate.changeName("x".repeat(100_000));
        store.persistEventAndSnapshot(changed.getEvent(), changed.getAggregate()).join();
        aggregate = changed.getAggregate().withVersion(i);
      }
      var keyResolver = new DefaultKeyResolver<UserAccountId>();
      var marked = List.of(1L, 3L, 5L, 16L);
      var previousTtl = Instant.now().plusSeconds(60).getEpochSecond();
      for (var sequence : marked) {
        client
            .updateItem(
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
                        Map.of(
                            ":ttl",
                            AttributeValue.builder().n(String.valueOf(previousTtl)).build()))
                    .build())
            .join();
      }
      // Legacy rows without a TTL attribute also count as unmarked snapshots.
      client
          .updateItem(
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
                                  .s(keyResolver.resolveSortKey(id, 2))
                                  .build()))
                  .updateExpression("REMOVE #ttl")
                  .expressionAttributeNames(Map.of("#ttl", "ttl"))
                  .build())
          .join();
      var changed = aggregate.changeName("trigger retention");
      var before = Instant.now().plusSeconds(3600).getEpochSecond();
      store
          .withKeepSnapshotCount(2)
          .persistEventAndSnapshot(changed.getEvent(), changed.getAggregate())
          .join();
      var after = Instant.now().plusSeconds(3600).getEpochSecond();
      var ttls = new java.util.HashMap<Long, Long>();
      var request = ScanRequest.builder().tableName(SNAPSHOT_TABLE_NAME).build();
      while (true) {
        var response = client.scan(request).join();
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
      store.withKeepSnapshotCount(1).persistEvent(next.getEvent(), 17).join();
      var finalTtls = new java.util.HashMap<Long, Long>();
      request = ScanRequest.builder().tableName(SNAPSHOT_TABLE_NAME).build();
      while (true) {
        var response = client.scan(request).join();
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
