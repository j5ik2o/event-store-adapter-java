package com.github.j5ik2o.event.store.adapter.java.dynamodb;

import com.github.j5ik2o.event.store.adapter.java.core.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryResponse;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsRequest;

/**
 * Validates configuration, writes and reads events and snapshots using caller-owned SDK clients.
 * Latest snapshot reads acquire the head and current snapshot non-atomically, with strong
 * consistency for each item; their sequence numbers may differ in either direction. /
 * 呼出し側が所有するSDKで生成時照合、イベントとsnapshotの書込み・読取りを行います。 最新snapshotとheadは各項目を強整合で非原子的に読み、どちらの番号が新しい組も返します。
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
    return new Synchronous<>(
        client, tables, config.payloadSerializer(), config.snapshotSerializer(), sleeper);
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
          .thenApply(
              ignored ->
                  new Asynchronous<>(
                      client,
                      tables,
                      config.payloadSerializer(),
                      config.snapshotSerializer(),
                      sleeper));
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

  private static final class Synchronous<P, A> implements EventStore<P, A> {
    private final DynamoDbClient client;
    private final DynamoDbTableConfig tables;
    private final PayloadSerializer<P> serializer;
    private final PayloadSerializer<A> snapshotSerializer;
    private final Sleeper sleeper;

    Synchronous(
        DynamoDbClient client,
        DynamoDbTableConfig tables,
        PayloadSerializer<P> serializer,
        PayloadSerializer<A> snapshotSerializer,
        Sleeper sleeper) {
      this.client = client;
      this.tables = tables;
      this.serializer = serializer;
      this.snapshotSerializer = snapshotSerializer;
      this.sleeper = sleeper;
    }

    public void persistEvent(EventEnvelope<P> event) {
      TransactWriteItemsRequest request = DynamoDbEventWrite.prepare(event, tables, serializer);
      try {
        client.transactWriteItems(request);
      } catch (RuntimeException failure) {
        throw DynamoDbEventWrite.classify(event, failure);
      }
    }

    public void persistEventAndSnapshot(EventEnvelope<P> event, SnapshotEnvelope<A> snapshot) {
      TransactWriteItemsRequest request =
          DynamoDbEventWrite.prepare(event, snapshot, tables, serializer, snapshotSerializer);
      try {
        client.transactWriteItems(request);
      } catch (RuntimeException failure) {
        throw DynamoDbEventWrite.classify(event, failure);
      }
    }

    public Optional<SnapshotReadResult<A>> getLatestSnapshotById(AggregateId id) {
      return new DynamoDbSnapshotRead(id, tables, sleeper).read(client, snapshotSerializer);
    }

    public List<EventEnvelope<P>> getEventsByIdSinceSeqNr(AggregateId id, long seqNr) {
      QueryRequest request = DynamoDbEventRead.prepare(id, seqNr, tables);
      List<EventEnvelope<P>> events = new ArrayList<>();
      try {
        while (true) {
          QueryResponse response = client.query(request);
          DynamoDbEventRead.append(response, serializer, events);
          if (response.lastEvaluatedKey().isEmpty()) return List.copyOf(events);
          request = request.toBuilder().exclusiveStartKey(response.lastEvaluatedKey()).build();
        }
      } catch (RuntimeException failure) {
        throw DynamoDbEventRead.classify(failure);
      }
    }
  }

  private static final class Asynchronous<P, A> implements AsyncEventStore<P, A> {
    private final DynamoDbAsyncClient client;
    private final DynamoDbTableConfig tables;
    private final PayloadSerializer<P> serializer;
    private final PayloadSerializer<A> snapshotSerializer;
    private final Sleeper sleeper;

    Asynchronous(
        DynamoDbAsyncClient client,
        DynamoDbTableConfig tables,
        PayloadSerializer<P> serializer,
        PayloadSerializer<A> snapshotSerializer,
        Sleeper sleeper) {
      this.client = client;
      this.tables = tables;
      this.serializer = serializer;
      this.snapshotSerializer = snapshotSerializer;
      this.sleeper = sleeper;
    }

    public CompletableFuture<Void> persistEvent(EventEnvelope<P> event) {
      CompletableFuture<Void> result = new CompletableFuture<>();
      try {
        TransactWriteItemsRequest request = DynamoDbEventWrite.prepare(event, tables, serializer);
        client
            .transactWriteItems(request)
            .whenComplete(
                (response, failure) -> {
                  if (failure == null) result.complete(null);
                  else result.completeExceptionally(DynamoDbEventWrite.classify(event, failure));
                });
      } catch (RuntimeException failure) {
        result.completeExceptionally(DynamoDbEventWrite.classify(event, failure));
      }
      return result;
    }

    public CompletableFuture<Void> persistEventAndSnapshot(
        EventEnvelope<P> event, SnapshotEnvelope<A> snapshot) {
      CompletableFuture<Void> result = new CompletableFuture<>();
      try {
        TransactWriteItemsRequest request =
            DynamoDbEventWrite.prepare(event, snapshot, tables, serializer, snapshotSerializer);
        client
            .transactWriteItems(request)
            .whenComplete(
                (response, failure) -> {
                  if (failure == null) result.complete(null);
                  else result.completeExceptionally(DynamoDbEventWrite.classify(event, failure));
                });
      } catch (RuntimeException failure) {
        result.completeExceptionally(DynamoDbEventWrite.classify(event, failure));
      }
      return result;
    }

    public CompletableFuture<Optional<SnapshotReadResult<A>>> getLatestSnapshotById(
        AggregateId id) {
      try {
        return new DynamoDbSnapshotRead(id, tables, sleeper).readAsync(client, snapshotSerializer);
      } catch (RuntimeException failure) {
        return CompletableFuture.failedFuture(failure);
      }
    }

    public CompletableFuture<List<EventEnvelope<P>>> getEventsByIdSinceSeqNr(
        AggregateId id, long seqNr) {
      QueryRequest request;
      try {
        request = DynamoDbEventRead.prepare(id, seqNr, tables);
      } catch (RuntimeException failure) {
        return CompletableFuture.failedFuture(failure);
      }
      CompletableFuture<List<EventEnvelope<P>>> result = new CompletableFuture<>();
      readPage(request, new ArrayList<>(), result);
      return result;
    }

    private void readPage(
        QueryRequest request,
        List<EventEnvelope<P>> events,
        CompletableFuture<List<EventEnvelope<P>>> result) {
      try {
        client
            .query(request)
            .whenComplete(
                (response, failure) -> {
                  if (failure != null) {
                    result.completeExceptionally(DynamoDbEventRead.classify(failure));
                    return;
                  }
                  try {
                    DynamoDbEventRead.append(response, serializer, events);
                    if (response.lastEvaluatedKey().isEmpty()) result.complete(List.copyOf(events));
                    else
                      readPage(
                          request.toBuilder()
                              .exclusiveStartKey(response.lastEvaluatedKey())
                              .build(),
                          events,
                          result);
                  } catch (RuntimeException invalidResponse) {
                    result.completeExceptionally(DynamoDbEventRead.classify(invalidResponse));
                  }
                });
      } catch (RuntimeException failure) {
        result.completeExceptionally(DynamoDbEventRead.classify(failure));
      }
    }
  }
}
