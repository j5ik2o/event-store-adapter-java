package com.github.j5ik2o.event.store.adapter.java.dynamodb;

import com.github.j5ik2o.event.store.adapter.java.core.ConfigurationException;
import com.github.j5ik2o.event.store.adapter.java.core.EventStoreException;
import com.github.j5ik2o.event.store.adapter.java.core.EventStoreExceptions;
import com.github.j5ik2o.event.store.adapter.java.core.StorageException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;

/** Configuration acquisition and validation only; no table provisioning or event operations. */
final class DynamoDbConfigurationInitializer {
  private final DynamoDbTableConfig tables;
  private final Sleeper sleeper;
  private final Map<String, Map<String, AttributeValue>> keys = new LinkedHashMap<>();

  DynamoDbConfigurationInitializer(DynamoDbTableConfig tables, Sleeper sleeper) {
    this.tables = tables;
    this.sleeper = sleeper;
    keys.put(tables.journalTableName(), key("seq_nr"));
    keys.put(tables.snapshotTableName(), key("skey"));
    keys.put(tables.headTableName(), key(null));
  }

  private static Map<String, AttributeValue> key(String sortKey) {
    Map<String, AttributeValue> key = new LinkedHashMap<>();
    key.put("aid", AttributeValue.fromS("__config__"));
    if (sortKey != null) key.put(sortKey, AttributeValue.fromN("0"));
    return Map.copyOf(key);
  }

  void initialize(DynamoDbClient client) {
    try {
      Map<String, Map<String, AttributeValue>> found = read(client);
      if (!found.isEmpty()) {
        validate(found);
        return;
      }
      try {
        client.transactWriteItems(createRequest());
      } catch (RuntimeException failure) {
        if (!creationConflict(failure)) throw failure;
        validateAfterConflict(read(client));
      }
    } catch (RuntimeException failure) {
      throw classify(failure);
    }
  }

  CompletableFuture<Void> initializeAsync(DynamoDbAsyncClient client) {
    return readAsync(client, new ReadState(), initialRequest(), 0, 50)
        .thenCompose(
            found -> {
              if (!found.isEmpty()) {
                validate(found);
                return CompletableFuture.completedFuture(null);
              }
              return invoke(() -> client.transactWriteItems(createRequest()))
                  .handle(
                      (response, error) -> {
                        if (error == null) return CompletableFuture.<Void>completedFuture(null);
                        if (!creationConflict(error))
                          return CompletableFuture.<Void>failedFuture(classify(error));
                        return readAsync(client, new ReadState(), initialRequest(), 0, 50)
                            .thenAccept(this::validateAfterConflict);
                      })
                  .thenCompose(future -> future);
            })
        .handle(
            (ignored, error) -> {
              if (error != null) throw classify(error);
              return null;
            });
  }

  private Map<String, Map<String, AttributeValue>> read(DynamoDbClient client) {
    ReadState state = new ReadState();
    BatchGetItemRequest request = initialRequest();
    int retries = 0;
    long delay = 50;
    while (true) {
      Map<String, KeysAndAttributes> pending = state.accept(client.batchGetItem(request));
      if (pending.isEmpty()) return state.found;
      checkLimit(retries);
      try {
        sleeper.sleep(delay);
      } catch (InterruptedException error) {
        Thread.currentThread().interrupt();
        throw new StorageException("Configuration read interrupted", error);
      }
      retries++;
      delay = Math.min(delay * 2, 1000);
      request = BatchGetItemRequest.builder().requestItems(pending).build();
    }
  }

  private CompletableFuture<Map<String, Map<String, AttributeValue>>> readAsync(
      DynamoDbAsyncClient client,
      ReadState state,
      BatchGetItemRequest request,
      int retries,
      long delay) {
    return invoke(() -> client.batchGetItem(request))
        .thenCompose(
            response -> {
              Map<String, KeysAndAttributes> pending = state.accept(response);
              if (pending.isEmpty()) return CompletableFuture.completedFuture(state.found);
              checkLimit(retries);
              return sleeper
                  .sleepAsync(delay)
                  .thenCompose(
                      ignored ->
                          readAsync(
                              client,
                              state,
                              BatchGetItemRequest.builder().requestItems(pending).build(),
                              retries + 1,
                              Math.min(delay * 2, 1000)));
            });
  }

  private void checkLimit(int retries) {
    if (retries >= tables.configurationReadRetryLimit()) {
      throw new StorageException("Configuration read retry limit reached");
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

  private TransactWriteItemsRequest createRequest() {
    String storeId = UUID.randomUUID().toString();
    List<TransactWriteItem> writes = new ArrayList<>();
    keys.forEach(
        (table, key) -> {
          Map<String, AttributeValue> item = new LinkedHashMap<>(key);
          item.put("store_id", AttributeValue.fromS(storeId));
          item.put("layout_version", AttributeValue.fromN("1"));
          writes.add(
              TransactWriteItem.builder()
                  .put(
                      Put.builder()
                          .tableName(table)
                          .item(item)
                          .conditionExpression("attribute_not_exists(aid)")
                          .build())
                  .build());
        });
    return TransactWriteItemsRequest.builder().transactItems(writes).build();
  }

  private void validateAfterConflict(Map<String, Map<String, AttributeValue>> found) {
    if (found.isEmpty()) throw new StorageException("Configuration absent after creation conflict");
    validate(found);
  }

  private void validate(Map<String, Map<String, AttributeValue>> found) {
    if (found.size() != 3) throw new ConfigurationException("Configuration is partially present");
    String storeId = null;
    for (Map<String, AttributeValue> item : found.values()) {
      AttributeValue id = item.get("store_id");
      AttributeValue version = item.get("layout_version");
      if (id == null
          || id.s() == null
          || id.s().isEmpty()
          || version == null
          || !numberEquals(version.n(), "1")) {
        throw new ConfigurationException("Invalid or unsupported stored configuration");
      }
      if (storeId != null && !storeId.equals(id.s())) {
        throw new ConfigurationException("Stored configuration identifiers differ");
      }
      storeId = id.s();
    }
  }

  private static boolean numberEquals(String actual, String expected) {
    if (actual == null) return false;
    try {
      return new BigDecimal(actual).compareTo(new BigDecimal(expected)) == 0;
    } catch (NumberFormatException error) {
      return false;
    }
  }

  private boolean matchesKey(String table, Map<String, AttributeValue> item) {
    Map<String, AttributeValue> key = keys.get(table);
    if (key == null) return false;
    for (Map.Entry<String, AttributeValue> part : key.entrySet()) {
      AttributeValue value = item.get(part.getKey());
      if (value == null) return false;
      if (part.getValue().s() != null) {
        if (!part.getValue().s().equals(value.s())) return false;
      } else if (!numberEquals(value.n(), part.getValue().n())) return false;
    }
    return true;
  }

  private final class ReadState {
    final Map<String, Map<String, AttributeValue>> found = new LinkedHashMap<>();

    Map<String, KeysAndAttributes> accept(BatchGetItemResponse response) {
      response
          .responses()
          .forEach(
              (table, items) ->
                  items.forEach(
                      item -> {
                        if (!matchesKey(table, item))
                          throw new StorageException("Unexpected configuration response key");
                        found.put(table, item);
                      }));
      Map<String, KeysAndAttributes> pending = new LinkedHashMap<>();
      response
          .unprocessedKeys()
          .forEach(
              (table, attributes) -> {
                if (attributes.keys().isEmpty()) return;
                for (Map<String, AttributeValue> key : attributes.keys()) {
                  if (!matchesKey(table, key))
                    throw new StorageException("Unexpected unprocessed configuration key");
                }
                pending.put(table, attributes.toBuilder().consistentRead(true).build());
              });
      return pending;
    }
  }

  private static boolean creationConflict(Throwable failure) {
    Throwable cause = EventStoreExceptions.unwrap(failure);
    if (!(cause instanceof TransactionCanceledException)) return false;
    return ((TransactionCanceledException) cause)
        .cancellationReasons().stream()
            .anyMatch(
                reason ->
                    "ConditionalCheckFailed".equals(reason.code())
                        || "TransactionConflict".equals(reason.code()));
  }

  private static RuntimeException classify(Throwable error) {
    Throwable cause = EventStoreExceptions.unwrap(error);
    return cause instanceof EventStoreException
        ? (EventStoreException) cause
        : new StorageException("DynamoDB configuration operation failed", cause);
  }

  private static <T> CompletableFuture<T> invoke(Supplier<CompletableFuture<T>> operation) {
    try {
      return operation.get();
    } catch (RuntimeException error) {
      return CompletableFuture.failedFuture(error);
    }
  }
}
