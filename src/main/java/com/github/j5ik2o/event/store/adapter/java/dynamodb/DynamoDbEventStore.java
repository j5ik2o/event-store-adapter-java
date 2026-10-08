package com.github.j5ik2o.event.store.adapter.java.dynamodb;

import com.github.j5ik2o.event.store.adapter.java.core.*;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

/**
 * Validates the configuration using caller-owned SDK clients. Event operations are supplied in the
 * following implementation stage. / 呼出し側が所有するSDKで生成時照合を行います。4操作は次工程で提供します。
 */
public final class DynamoDbEventStore {
  private DynamoDbEventStore() {}

  /** Reads or atomically initializes configuration; never provisions tables. */
  public static <P, A> EventStore<P, A> create(
      DynamoDbClient client, DynamoDbTableConfig tables, EventStoreConfig<P, A> config) {
    return create(client, tables, config, Sleeper.SYSTEM);
  }

  /** Generation failures complete the returned future exceptionally. */
  public static <P, A> CompletableFuture<AsyncEventStore<P, A>> createAsync(
      DynamoDbAsyncClient client, DynamoDbTableConfig tables, EventStoreConfig<P, A> config) {
    return createAsync(client, tables, config, Sleeper.SYSTEM);
  }

  static <P, A> EventStore<P, A> create(
      DynamoDbClient client,
      DynamoDbTableConfig tables,
      EventStoreConfig<P, A> config,
      Sleeper sleeper) {
    requireSettings(client, tables, config);
    new DynamoDbConfigurationInitializer(tables, sleeper).initialize(client);
    return new Synchronous<>();
  }

  static <P, A> CompletableFuture<AsyncEventStore<P, A>> createAsync(
      DynamoDbAsyncClient client,
      DynamoDbTableConfig tables,
      EventStoreConfig<P, A> config,
      Sleeper sleeper) {
    try {
      requireSettings(client, tables, config);
      return new DynamoDbConfigurationInitializer(tables, sleeper)
          .initializeAsync(client)
          .thenApply(ignored -> new Asynchronous<>());
    } catch (ConfigurationException failure) {
      return CompletableFuture.failedFuture(failure);
    }
  }

  private static void requireSettings(
      Object client, DynamoDbTableConfig tables, EventStoreConfig<?, ?> config) {
    if (client == null || tables == null || config == null) {
      throw new ConfigurationException("client, tables and config are required");
    }
  }

  private static UnsupportedOperationException unavailable() {
    return new UnsupportedOperationException("DynamoDB event operations are not yet provided");
  }

  private static final class Synchronous<P, A> implements EventStore<P, A> {
    public void persistEvent(EventEnvelope<P> event) {
      throw unavailable();
    }

    public void persistEventAndSnapshot(EventEnvelope<P> event, SnapshotEnvelope<A> snapshot) {
      throw unavailable();
    }

    public Optional<SnapshotReadResult<A>> getLatestSnapshotById(AggregateId id) {
      throw unavailable();
    }

    public List<EventEnvelope<P>> getEventsByIdSinceSeqNr(AggregateId id, long seqNr) {
      throw unavailable();
    }
  }

  private static final class Asynchronous<P, A> implements AsyncEventStore<P, A> {
    public CompletableFuture<Void> persistEvent(EventEnvelope<P> event) {
      return CompletableFuture.failedFuture(unavailable());
    }

    public CompletableFuture<Void> persistEventAndSnapshot(
        EventEnvelope<P> event, SnapshotEnvelope<A> snapshot) {
      return CompletableFuture.failedFuture(unavailable());
    }

    public CompletableFuture<Optional<SnapshotReadResult<A>>> getLatestSnapshotById(
        AggregateId id) {
      return CompletableFuture.failedFuture(unavailable());
    }

    public CompletableFuture<List<EventEnvelope<P>>> getEventsByIdSinceSeqNr(
        AggregateId id, long seqNr) {
      return CompletableFuture.failedFuture(unavailable());
    }
  }
}
