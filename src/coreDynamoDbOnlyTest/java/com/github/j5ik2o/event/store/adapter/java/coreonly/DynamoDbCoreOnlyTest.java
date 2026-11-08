package com.github.j5ik2o.event.store.adapter.java.coreonly;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.j5ik2o.event.store.adapter.java.core.AggregateId;
import com.github.j5ik2o.event.store.adapter.java.core.EventEnvelope;
import com.github.j5ik2o.event.store.adapter.java.core.EventStore;
import com.github.j5ik2o.event.store.adapter.java.core.EventStoreConfig;
import com.github.j5ik2o.event.store.adapter.java.core.JsonPayloadSerializer;
import com.github.j5ik2o.event.store.adapter.java.core.RetentionPolicy;
import com.github.j5ik2o.event.store.adapter.java.core.SnapshotEnvelope;
import com.github.j5ik2o.event.store.adapter.java.core.SnapshotReadResult;
import com.github.j5ik2o.event.store.adapter.java.dynamodb.DynamoDbEventStore;
import com.github.j5ik2o.event.store.adapter.java.dynamodb.DynamoDbTableConfig;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.http.apache5.Apache5HttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeDefinition;
import software.amazon.awssdk.services.dynamodb.model.BillingMode;
import software.amazon.awssdk.services.dynamodb.model.CreateTableRequest;
import software.amazon.awssdk.services.dynamodb.model.GlobalSecondaryIndex;
import software.amazon.awssdk.services.dynamodb.model.KeySchemaElement;
import software.amazon.awssdk.services.dynamodb.model.KeyType;
import software.amazon.awssdk.services.dynamodb.model.Projection;
import software.amazon.awssdk.services.dynamodb.model.ProjectionType;
import software.amazon.awssdk.services.dynamodb.model.ResourceNotFoundException;
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType;
import software.amazon.awssdk.services.dynamodb.model.StreamSpecification;
import software.amazon.awssdk.services.dynamodb.model.StreamViewType;
import software.amazon.awssdk.services.dynamodb.model.TableStatus;

/**
 * The public DynamoDB entry point must initialize and read/write with only the core and dynamodb
 * modules on the classpath. This source set deliberately excludes the other driver modules.
 */
final class DynamoDbCoreOnlyTest {

  private static final String IMAGE =
      "amazon/dynamodb-local@sha256:ff89bd48ff32cd8d9be5fee8873b65b8854dc408f1afe881be6eb00247bc0dab";

  @Test
  void initializesAndReadsAndWritesWithOnlyCoreAndDynamoDb() {
    try (GenericContainer<?> local =
        new GenericContainer<>(DockerImageName.parse(IMAGE))
            .withExposedPorts(8000)
            .withCommand("-jar", "DynamoDBLocal.jar", "-inMemory")) {
      local.start();
      URI endpoint = URI.create("http://" + local.getHost() + ":" + local.getMappedPort(8000));
      String prefix = "core-only-" + UUID.randomUUID();
      String journal = prefix + "-journal";
      String snapshot = prefix + "-snapshot";
      String head = prefix + "-head";
      String historyIndex = "history";

      try (DynamoDbClient client = newClient(endpoint)) {
        awaitReady(client);
        createTables(client, journal, snapshot, head, historyIndex);

        EventStoreConfig<String, String> config =
            EventStoreConfig.<String, String>builder()
                .payloadSerializer(JsonPayloadSerializer.of(String.class))
                .snapshotSerializer(JsonPayloadSerializer.of(String.class))
                .build();
        DynamoDbTableConfig tables =
            DynamoDbTableConfig.builder()
                .journalTableName(journal)
                .snapshotTableName(snapshot)
                .headTableName(head)
                .snapshotAidIndexName(historyIndex)
                .retentionPolicy(RetentionPolicy.none())
                .build();

        EventStore<String, String> store = DynamoDbEventStore.create(client, tables, config);
        AggregateId id = AggregateId.of("UserAccount", prefix);

        store.persistEventAndSnapshot(
            EventEnvelope.<String>builder()
                .aggregateId(id)
                .seqNr(1)
                .occurredAt(Instant.now())
                .payload("Alice")
                .build(),
            SnapshotEnvelope.<String>builder().aggregate("Alice").seqNr(1).build());

        Optional<SnapshotReadResult<String>> latest = store.getLatestSnapshotById(id);
        assertTrue(latest.isPresent());
        assertEquals(1, latest.get().headSeqNr());
        assertEquals("Alice", latest.get().snapshot().orElseThrow().aggregate());

        List<EventEnvelope<String>> events = store.getEventsByIdSinceSeqNr(id, 1);
        assertEquals(1, events.size());
        assertEquals(1, events.get(0).seqNr());
        assertEquals("Alice", events.get(0).payload());
      }
    }
  }

  @Test
  void exposesCoreAndDynamoDbButNotTheOtherDriverModules() {
    ClassLoader loader = DynamoDbCoreOnlyTest.class.getClassLoader();
    assertDoesNotThrow(
        () ->
            Class.forName(
                "com.github.j5ik2o.event.store.adapter.java.core.EventStore", false, loader));
    assertDoesNotThrow(
        () ->
            Class.forName(
                "com.github.j5ik2o.event.store.adapter.java.memory.MemoryStorage", false, loader));
    assertDoesNotThrow(
        () ->
            Class.forName(
                "com.github.j5ik2o.event.store.adapter.java.dynamodb.DynamoDbEventStore",
                false,
                loader));
    for (String otherDriver :
        List.of(
            "com.github.j5ik2o.event.store.adapter.java.spanner.SpannerStorage",
            "com.github.j5ik2o.event.store.adapter.java.bigtable.BigtableStorage",
            "com.github.j5ik2o.event.store.adapter.java.sqlite.SqliteStorage")) {
      assertThrows(ClassNotFoundException.class, () -> Class.forName(otherDriver, false, loader));
    }
  }

  private static DynamoDbClient newClient(URI endpoint) {
    return DynamoDbClient.builder()
        .endpointOverride(endpoint)
        .region(Region.US_EAST_1)
        .credentialsProvider(
            StaticCredentialsProvider.create(
                AwsBasicCredentials.create("DynamoDbLocalTest", "DynamoDbLocalDummySecret")))
        .overrideConfiguration(
            ClientOverrideConfiguration.builder().apiCallTimeout(Duration.ofSeconds(20)).build())
        .httpClientBuilder(Apache5HttpClient.builder())
        .build();
  }

  private static void awaitReady(DynamoDbClient client) {
    long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
    while (true) {
      try {
        client.listTables();
        return;
      } catch (SdkException error) {
        if (System.nanoTime() >= deadline) throw error;
        sleep(50);
      }
    }
  }

  private static void createTables(
      DynamoDbClient client, String journal, String snapshot, String head, String historyIndex) {
    client.createTable(
        CreateTableRequest.builder()
            .tableName(journal)
            .billingMode(BillingMode.PAY_PER_REQUEST)
            .attributeDefinitions(
                attribute("aid", ScalarAttributeType.S), attribute("seq_nr", ScalarAttributeType.N))
            .keySchema(key("aid", KeyType.HASH), key("seq_nr", KeyType.RANGE))
            .build());
    client.createTable(
        CreateTableRequest.builder()
            .tableName(snapshot)
            .billingMode(BillingMode.PAY_PER_REQUEST)
            .attributeDefinitions(
                attribute("aid", ScalarAttributeType.S),
                attribute("skey", ScalarAttributeType.N),
                attribute("active_history_seq_nr", ScalarAttributeType.N))
            .keySchema(key("aid", KeyType.HASH), key("skey", KeyType.RANGE))
            .globalSecondaryIndexes(
                GlobalSecondaryIndex.builder()
                    .indexName(historyIndex)
                    .keySchema(
                        key("aid", KeyType.HASH), key("active_history_seq_nr", KeyType.RANGE))
                    .projection(
                        Projection.builder().projectionType(ProjectionType.KEYS_ONLY).build())
                    .build())
            .build());
    client.createTable(
        CreateTableRequest.builder()
            .tableName(head)
            .billingMode(BillingMode.PAY_PER_REQUEST)
            .attributeDefinitions(attribute("aid", ScalarAttributeType.S))
            .keySchema(key("aid", KeyType.HASH))
            .streamSpecification(
                StreamSpecification.builder()
                    .streamEnabled(true)
                    .streamViewType(StreamViewType.NEW_IMAGE)
                    .build())
            .build());
    awaitActive(client, journal);
    awaitActive(client, snapshot);
    awaitActive(client, head);
  }

  private static void awaitActive(DynamoDbClient client, String tableName) {
    long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
    while (true) {
      try {
        TableStatus status =
            client.describeTable(builder -> builder.tableName(tableName)).table().tableStatus();
        if (status == TableStatus.ACTIVE) return;
      } catch (ResourceNotFoundException ignored) {
        // The table may not be visible yet.
      }
      if (System.nanoTime() >= deadline) {
        throw new IllegalStateException("Table did not become active: " + tableName);
      }
      sleep(50);
    }
  }

  private static AttributeDefinition attribute(String name, ScalarAttributeType type) {
    return AttributeDefinition.builder().attributeName(name).attributeType(type).build();
  }

  private static KeySchemaElement key(String name, KeyType type) {
    return KeySchemaElement.builder().attributeName(name).keyType(type).build();
  }

  private static void sleep(long millis) {
    try {
      Thread.sleep(millis);
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Interrupted while waiting for DynamoDB Local", error);
    }
  }
}
