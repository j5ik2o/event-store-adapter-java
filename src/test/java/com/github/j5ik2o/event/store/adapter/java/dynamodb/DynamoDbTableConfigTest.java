package com.github.j5ik2o.event.store.adapter.java.dynamodb;

import static org.junit.jupiter.api.Assertions.*;

import com.github.j5ik2o.event.store.adapter.java.core.*;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

class DynamoDbTableConfigTest {
  static DynamoDbTableConfig.Builder names() {
    return DynamoDbTableConfig.builder()
        .journalTableName("journal")
        .snapshotTableName("snapshot")
        .headTableName("head")
        .snapshotAidIndexName("history");
  }

  @Test
  void defaultsAndAllConfiguredNames() {
    DynamoDbTableConfig c = names().build();
    assertEquals("journal", c.journalTableName());
    assertEquals("snapshot", c.snapshotTableName());
    assertEquals("head", c.headTableName());
    assertEquals("history", c.snapshotAidIndexName());
    assertEquals(10, c.configurationReadRetryLimit());
    assertTrue(c.retentionPolicy().mode().isEmpty());
    assertEquals(Clock.systemUTC(), c.clock());
  }

  @Test
  void explicitSettingsAreImmutableAfterBuilderReuse() {
    Clock clock = Clock.fixed(Instant.ofEpochSecond(4102444800L), ZoneOffset.UTC);
    RetentionPolicy policy = RetentionPolicy.ttl(2, 0);
    DynamoDbTableConfig.Builder b =
        names().retentionPolicy(policy).clock(clock).configurationReadRetryLimit(0);
    DynamoDbTableConfig c = b.build();
    b.journalTableName("other")
        .retentionPolicy(RetentionPolicy.delete(3))
        .configurationReadRetryLimit(7);
    assertEquals("journal", c.journalTableName());
    assertEquals(policy, c.retentionPolicy());
    assertEquals(clock, c.clock());
    assertEquals(0, c.configurationReadRetryLimit());
    assertEquals(7, b.build().configurationReadRetryLimit());
    assertEquals(RetentionMode.DELETE, b.build().retentionPolicy().mode().orElseThrow());
  }

  @Test
  void requiredSettingsAndNegativeRetryAreConfigurationFailures() {
    List<Consumer<DynamoDbTableConfig.Builder>> invalid =
        List.of(
            b -> b.journalTableName(null),
            b -> b.snapshotTableName(null),
            b -> b.headTableName(null),
            b -> b.snapshotAidIndexName(null),
            b -> b.journalTableName(""),
            b -> b.snapshotTableName(" "),
            b -> b.headTableName(""),
            b -> b.snapshotAidIndexName(""),
            b -> b.retentionPolicy(null),
            b -> b.clock(null),
            b -> b.configurationReadRetryLimit(-1));
    for (Consumer<DynamoDbTableConfig.Builder> change : invalid) {
      DynamoDbTableConfig.Builder b = names();
      change.accept(b);
      assertThrows(ConfigurationException.class, b::build);
    }
    assertThrows(ConfigurationException.class, () -> DynamoDbTableConfig.builder().build());
    assertThrows(
        ConfigurationException.class, () -> names().retentionPolicy(RetentionPolicy.delete(0)));
    assertThrows(
        ConfigurationException.class, () -> names().retentionPolicy(RetentionPolicy.ttl(1, -1)));
  }

  @Test
  void duplicateTableNamesAreConfigurationFailures() {
    for (List<String> tables :
        List.of(
            List.of("journal", "journal", "head"),
            List.of("journal", "snapshot", "journal"),
            List.of("journal", "snapshot", "snapshot"),
            List.of("head", "head", "head"))) {
      assertThrows(
          ConfigurationException.class,
          () ->
              names()
                  .journalTableName(tables.get(0))
                  .snapshotTableName(tables.get(1))
                  .headTableName(tables.get(2))
                  .build(),
          tables.toString());
    }
    // Index names belong to the snapshot table and may equal a table name.
    assertEquals("journal", names().snapshotAidIndexName("journal").build().snapshotAidIndexName());
  }

  @Test
  void absentFactorySettingsFailAtTheCorrectEntryBoundary() {
    assertThrows(
        ConfigurationException.class, () -> DynamoDbEventStore.create(null, names().build(), null));
    assertInstanceOf(
        ConfigurationException.class,
        EventStoreExceptions.unwrap(
            assertThrows(
                java.util.concurrent.CompletionException.class,
                () -> DynamoDbEventStore.createAsync(null, names().build(), null).join())));
  }
}
