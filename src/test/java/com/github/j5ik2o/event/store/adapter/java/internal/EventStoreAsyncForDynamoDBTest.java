package com.github.j5ik2o.event.store.adapter.java.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;
import static org.testcontainers.containers.localstack.LocalStackContainer.Service.DYNAMODB;

import com.github.j5ik2o.event.store.adapter.java.EventStoreAsync;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.localstack.LocalStackContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

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
  public void test_persist_and_get() {
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
        assertEquals(result.get().getAggregate().getId(), aggregateAndEvent.getAggregate().getId());
        LOGGER.info("result = {}", result.get());
      } else {
        fail("result is empty");
      }
    }
  }

  @Test
  public void test_repository_store_and_find_by_id() {
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
                  r -> {
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
}
