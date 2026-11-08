package com.github.j5ik2o.event.store.adapter.java.dynamodb;

import com.github.j5ik2o.event.store.adapter.java.core.*;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;

/** One non-atomic acquisition of the head and current snapshot, including unprocessed keys. */
final class DynamoDbSnapshotRead {
  private final AggregateId id;
  private final DynamoDbTableConfig tables;
  private final Sleeper sleeper;
  private final Map<String, Map<String, AttributeValue>> keys;

  DynamoDbSnapshotRead(AggregateId id, DynamoDbTableConfig tables, Sleeper sleeper) {
    EventStoreInputValidation.checkRead(id, 0);
    this.id = id;
    this.tables = tables;
    this.sleeper = sleeper;
    AttributeValue aid = AttributeValue.fromS(id.asString());
    keys =
        Map.of(
            tables.headTableName(), Map.of("aid", aid),
            tables.snapshotTableName(), Map.of("aid", aid, "skey", AttributeValue.fromN("0")));
  }

  <A> Optional<SnapshotReadResult<A>> read(DynamoDbClient client, PayloadSerializer<A> serializer) {
    ReadState state = new ReadState();
    BatchGetItemRequest request = initialRequest();
    int retries = 0;
    long delay = 50;
    try {
      while (true) {
        Map<String, KeysAndAttributes> pending = state.accept(client.batchGetItem(request));
        if (pending.isEmpty()) return state.restore(serializer);
        checkLimit(retries);
        try {
          sleeper.sleep(delay);
        } catch (InterruptedException failure) {
          Thread.currentThread().interrupt();
          throw new StorageException("DynamoDB snapshot read interrupted", failure);
        }
        request = BatchGetItemRequest.builder().requestItems(pending).build();
        retries++;
        delay = Math.min(delay * 2, 1000);
      }
    } catch (RuntimeException failure) {
      throw classify(failure);
    }
  }

  <A> CompletableFuture<Optional<SnapshotReadResult<A>>> readAsync(
      DynamoDbAsyncClient client, PayloadSerializer<A> serializer) {
    CompletableFuture<Optional<SnapshotReadResult<A>>> result = new CompletableFuture<>();
    readBatch(client, serializer, new ReadState(), initialRequest(), 0, 50, result);
    return result;
  }

  private <A> void readBatch(
      DynamoDbAsyncClient client,
      PayloadSerializer<A> serializer,
      ReadState state,
      BatchGetItemRequest request,
      int retries,
      long delay,
      CompletableFuture<Optional<SnapshotReadResult<A>>> result) {
    if (result.isDone()) return;
    try {
      client
          .batchGetItem(request)
          .whenComplete(
              (response, failure) -> {
                if (result.isDone()) return;
                if (failure != null) {
                  result.completeExceptionally(classify(failure));
                  return;
                }
                try {
                  Map<String, KeysAndAttributes> pending = state.accept(response);
                  if (pending.isEmpty()) {
                    result.complete(state.restore(serializer));
                    return;
                  }
                  checkLimit(retries);
                  sleeper
                      .sleepAsync(delay)
                      .whenComplete(
                          (ignored, waitFailure) -> {
                            if (waitFailure != null)
                              result.completeExceptionally(classify(waitFailure));
                            else
                              readBatch(
                                  client,
                                  serializer,
                                  state,
                                  BatchGetItemRequest.builder().requestItems(pending).build(),
                                  retries + 1,
                                  Math.min(delay * 2, 1000),
                                  result);
                          });
                } catch (RuntimeException invalidResponse) {
                  result.completeExceptionally(classify(invalidResponse));
                }
              });
    } catch (RuntimeException failure) {
      result.completeExceptionally(classify(failure));
    }
  }

  private BatchGetItemRequest initialRequest() {
    Map<String, KeysAndAttributes> requests = new LinkedHashMap<>();
    keys.forEach(
        (table, key) ->
            requests.put(
                table,
                KeysAndAttributes.builder().keys(List.of(key)).consistentRead(true).build()));
    return BatchGetItemRequest.builder().requestItems(requests).build();
  }

  private void checkLimit(int retries) {
    if (retries >= tables.configurationReadRetryLimit())
      throw new StorageException("DynamoDB snapshot read retry limit reached");
  }

  private void checkKey(String table, Map<String, AttributeValue> item) {
    try {
      Map<String, AttributeValue> key = keys.get(table);
      if (key == null || !required(item, "aid", AttributeValue.Type.S).equals(key.get("aid")))
        throw new IllegalArgumentException("Unexpected snapshot read aid or table");
      if (table.equals(tables.snapshotTableName()) && integer(item, "skey") != 0)
        throw new IllegalArgumentException("Unexpected current snapshot key");
    } catch (RuntimeException failure) {
      throw new StorageException("DynamoDB returned an invalid snapshot read key", failure);
    }
  }

  private final class ReadState {
    private final Map<String, Map<String, AttributeValue>> found = new LinkedHashMap<>();

    Map<String, KeysAndAttributes> accept(BatchGetItemResponse response) {
      response
          .responses()
          .forEach(
              (table, items) ->
                  items.forEach(
                      item -> {
                        checkKey(table, item);
                        found.put(table, item);
                      }));
      Map<String, KeysAndAttributes> pending = new LinkedHashMap<>();
      response
          .unprocessedKeys()
          .forEach(
              (table, attributes) -> {
                if (attributes.keys().isEmpty()) return;
                attributes.keys().forEach(key -> checkKey(table, key));
                pending.put(table, attributes.toBuilder().consistentRead(true).build());
              });
      return pending;
    }

    <A> Optional<SnapshotReadResult<A>> restore(PayloadSerializer<A> serializer) {
      Map<String, AttributeValue> head = found.get(tables.headTableName());
      if (head == null) return Optional.empty();
      long headSeqNr;
      SnapshotEnvelope<byte[]> stored;
      try {
        headSeqNr = integer(head, "seq_nr");
        EventStoreInputValidation.checkRead(id, headSeqNr);
        Map<String, AttributeValue> current = found.get(tables.snapshotTableName());
        if (current == null) return Optional.of(SnapshotReadResult.withoutSnapshot(headSeqNr));
        stored =
            SnapshotEnvelope.<byte[]>builder()
                .seqNr(integer(current, "seq_nr"))
                .manifest(required(current, "manifest", AttributeValue.Type.S).s())
                .aggregate(required(current, "payload", AttributeValue.Type.B).b().asByteArray())
                .build();
      } catch (RuntimeException failure) {
        throw new StorageException("DynamoDB returned an invalid head or snapshot item", failure);
      }
      A aggregate;
      try {
        aggregate = serializer.deserialize(stored.aggregate());
        if (aggregate == null)
          throw new SerializationException("failed to deserialize snapshot: result is null");
      } catch (SerializationException failure) {
        throw failure;
      } catch (Exception failure) {
        throw new SerializationException("failed to deserialize snapshot", failure);
      }
      return Optional.of(
          SnapshotReadResult.of(
              SnapshotEnvelope.<A>builder()
                  .seqNr(stored.seqNr())
                  .manifest(stored.manifest())
                  .aggregate(aggregate)
                  .build(),
              headSeqNr));
    }
  }

  private static AttributeValue required(
      Map<String, AttributeValue> item, String name, AttributeValue.Type type) {
    AttributeValue value = item.get(name);
    if (value == null || value.type() != type)
      throw new IllegalArgumentException("Missing or invalid snapshot read attribute: " + name);
    return value;
  }

  private static long integer(Map<String, AttributeValue> item, String name) {
    return new BigDecimal(required(item, name, AttributeValue.Type.N).n()).longValueExact();
  }

  private static EventStoreException classify(Throwable failure) {
    Throwable cause = EventStoreExceptions.unwrap(failure);
    if (cause instanceof EventStoreException) return (EventStoreException) cause;
    return new StorageException("DynamoDB snapshot read failed", cause);
  }
}
