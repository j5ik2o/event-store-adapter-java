package com.github.j5ik2o.event.store.adapter.java.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;
import static org.testcontainers.containers.localstack.LocalStackContainer.Service.DYNAMODB;

import com.github.j5ik2o.event.store.adapter.java.*;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.localstack.LocalStackContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

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
          DeserializationException  {
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
}
