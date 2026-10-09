package com.github.j5ik2o.event.store.adapter.java.dynamodb;

import com.github.j5ik2o.event.store.adapter.java.core.*;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.core.SdkPlugin;
import software.amazon.awssdk.core.SdkResponse;
import software.amazon.awssdk.core.SdkServiceClientConfiguration;
import software.amazon.awssdk.core.interceptor.Context;
import software.amazon.awssdk.core.interceptor.ExecutionAttributes;
import software.amazon.awssdk.core.interceptor.ExecutionInterceptor;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;

/** DELETE retention for one committed snapshot; no state is shared between writes. */
final class DynamoDbSnapshotRetention {
  private static final Logger LOG = LoggerFactory.getLogger(DynamoDbSnapshotRetention.class);
  private static final int RETRY_LIMIT = 10;
  private final AggregateId id;
  private final DynamoDbTableConfig tables;
  private final Optional<RetentionFailureListener> listener;
  private final Sleeper sleeper;
  private final NavigableSet<Long> history = new TreeSet<>(Comparator.reverseOrder());

  DynamoDbSnapshotRetention(
      AggregateId id,
      long committedSeqNr,
      DynamoDbTableConfig tables,
      Optional<RetentionFailureListener> listener,
      Sleeper sleeper) {
    this.id = id;
    this.tables = tables;
    this.listener = listener;
    this.sleeper = sleeper;
    history.add(committedSeqNr);
  }

  void retain(DynamoDbClient client) {
    if (!enabled()) return;
    try {
      QueryRequest request = query();
      while (true) {
        QueryResponse response = client.query(request);
        accept(response);
        if (response.lastEvaluatedKey().isEmpty()) break;
        request = request.toBuilder().exclusiveStartKey(response.lastEvaluatedKey()).build();
      }
      for (List<WriteRequest> batch : batches()) {
        BatchWriteItemRequest deletion = deletion(batch);
        int retries = 0;
        long delay = 50;
        while (true) {
          List<WriteRequest> pending = pending(client.batchWriteItem(deletion), deletion);
          if (pending.isEmpty()) break;
          checkLimit(retries);
          sleeper.sleep(delay);
          deletion = deletion(pending);
          retries++;
          delay = Math.min(delay * 2, 1000);
        }
      }
    } catch (InterruptedException failure) {
      Thread.currentThread().interrupt();
      notifyFailure(failure);
    } catch (RuntimeException failure) {
      notifyFailure(failure);
    }
  }

  CompletableFuture<Void> retainAsync(DynamoDbAsyncClient client) {
    if (!enabled()) return CompletableFuture.completedFuture(null);
    return readPage(client, query())
        .thenCompose(
            success ->
                success
                    ? deleteBatches(client, batches(), 0)
                    : CompletableFuture.completedFuture(false))
        .handle(
            (ignored, failure) -> {
              // SDK terminal failures and invalid responses have already been notified.
              // Only request startup and asynchronous wait failures reach this handler.
              if (failure != null) notifyFailure(failure);
              return null;
            });
  }

  private CompletableFuture<Boolean> readPage(DynamoDbAsyncClient client, QueryRequest request) {
    AsyncCompletion<QueryResponse> completion =
        new AsyncCompletion<>(
            response -> {
              QueryResponse page = (QueryResponse) response;
              accept(page);
              return page;
            });
    try {
      return client
          .query(
              request.toBuilder()
                  .overrideConfiguration(overrides -> overrides.addPlugin(completion))
                  .build())
          .handle((ignored, failure) -> completion.result(failure))
          .thenCompose(
              outcome -> {
                if (outcome.isEmpty()) return CompletableFuture.completedFuture(false);
                QueryResponse response = outcome.get();
                return response.lastEvaluatedKey().isEmpty()
                    ? CompletableFuture.completedFuture(true)
                    : readPage(
                        client,
                        request.toBuilder().exclusiveStartKey(response.lastEvaluatedKey()).build());
              });
    } catch (RuntimeException failure) {
      return completion.failure == null
          ? CompletableFuture.failedFuture(failure)
          : CompletableFuture.completedFuture(false);
    }
  }

  private CompletableFuture<Boolean> deleteBatches(
      DynamoDbAsyncClient client, List<List<WriteRequest>> batches, int index) {
    if (index == batches.size()) return CompletableFuture.completedFuture(true);
    return deleteBatch(client, deletion(batches.get(index)), 0, 50)
        .thenCompose(
            success ->
                success
                    ? deleteBatches(client, batches, index + 1)
                    : CompletableFuture.completedFuture(false));
  }

  private CompletableFuture<Boolean> deleteBatch(
      DynamoDbAsyncClient client, BatchWriteItemRequest request, int retries, long delay) {
    AsyncCompletion<List<WriteRequest>> completion =
        new AsyncCompletion<>(
            response -> {
              List<WriteRequest> remaining = pending((BatchWriteItemResponse) response, request);
              if (!remaining.isEmpty()) checkLimit(retries);
              return remaining;
            });
    try {
      return client
          .batchWriteItem(
              request.toBuilder()
                  .overrideConfiguration(overrides -> overrides.addPlugin(completion))
                  .build())
          .handle((ignored, failure) -> completion.result(failure))
          .thenCompose(
              outcome -> {
                if (outcome.isEmpty()) return CompletableFuture.completedFuture(false);
                List<WriteRequest> pending = outcome.get();
                if (pending.isEmpty()) return CompletableFuture.completedFuture(true);
                return sleeper
                    .sleepAsync(delay)
                    .thenCompose(
                        ignored ->
                            deleteBatch(
                                client, deletion(pending), retries + 1, Math.min(delay * 2, 1000)));
              });
    } catch (RuntimeException failure) {
      return completion.failure == null
          ? CompletableFuture.failedFuture(failure)
          : CompletableFuture.completedFuture(false);
    }
  }

  /** Processes each final SDK response before its future can be returned already completed. */
  private final class AsyncCompletion<T> implements SdkPlugin, ExecutionInterceptor {
    private final Function<SdkResponse, T> process;
    private T value;
    private Throwable failure;

    AsyncCompletion(Function<SdkResponse, T> process) {
      this.process = process;
    }

    @Override
    public void configureClient(SdkServiceClientConfiguration.Builder builder) {
      builder.overrideConfiguration(
          builder.overrideConfiguration().toBuilder().addExecutionInterceptor(this).build());
    }

    @Override
    public void afterExecution(Context.AfterExecution context, ExecutionAttributes attributes) {
      try {
        value = process.apply(context.response());
      } catch (RuntimeException invalidResponse) {
        fail(invalidResponse);
      }
    }

    @Override
    public void onExecutionFailure(
        Context.FailedExecution context, ExecutionAttributes attributes) {
      fail(context.exception());
    }

    private void fail(Throwable cause) {
      if (failure != null) return;
      failure = cause;
      notifyFailure(cause);
    }

    Optional<T> result(Throwable sdkFailure) {
      if (failure != null) return Optional.empty();
      if (sdkFailure != null) throw new CompletionException(sdkFailure);
      return Optional.of(value);
    }
  }

  private boolean enabled() {
    return tables.retentionPolicy().mode().orElse(null) == RetentionMode.DELETE;
  }

  private QueryRequest query() {
    return QueryRequest.builder()
        .tableName(tables.snapshotTableName())
        .indexName(tables.snapshotAidIndexName())
        .keyConditionExpression("aid = :aid")
        .expressionAttributeValues(Map.of(":aid", AttributeValue.fromS(id.asString())))
        .scanIndexForward(false)
        .consistentRead(false)
        .build();
  }

  private void accept(QueryResponse response) {
    for (Map<String, AttributeValue> item : response.items()) {
      AttributeValue aid = item.get("aid");
      long seqNr = integer(item, "skey");
      if (aid == null
          || aid.type() != AttributeValue.Type.S
          || !id.asString().equals(aid.s())
          || seqNr < 1
          || seqNr != integer(item, "active_history_seq_nr")
          || item.containsKey("ttl"))
        throw new StorageException("DynamoDB returned an invalid active history key");
      EventStoreInputValidation.checkRead(id, seqNr);
      history.add(seqNr);
    }
  }

  private List<List<WriteRequest>> batches() {
    List<WriteRequest> candidates = new ArrayList<>();
    history.stream()
        .skip(tables.retentionPolicy().keepCount().orElseThrow())
        .forEach(
            seqNr ->
                candidates.add(
                    WriteRequest.builder()
                        .deleteRequest(
                            DeleteRequest.builder()
                                .key(
                                    Map.of(
                                        "aid", AttributeValue.fromS(id.asString()),
                                        "skey", AttributeValue.fromN(Long.toString(seqNr))))
                                .build())
                        .build()));
    List<List<WriteRequest>> batches = new ArrayList<>();
    for (int start = 0; start < candidates.size(); start += 25)
      batches.add(List.copyOf(candidates.subList(start, Math.min(start + 25, candidates.size()))));
    return batches;
  }

  private BatchWriteItemRequest deletion(List<WriteRequest> writes) {
    return BatchWriteItemRequest.builder()
        .requestItems(Map.of(tables.snapshotTableName(), writes))
        .build();
  }

  private List<WriteRequest> pending(BatchWriteItemResponse response, BatchWriteItemRequest sent) {
    for (Map.Entry<String, List<WriteRequest>> entry : response.unprocessedItems().entrySet()) {
      List<WriteRequest> submitted = sent.requestItems().get(entry.getKey());
      if (submitted == null
          || !submitted.containsAll(entry.getValue())
          || new HashSet<>(entry.getValue()).size() != entry.getValue().size())
        throw new StorageException("DynamoDB returned invalid unprocessed history deletes");
    }
    return response.unprocessedItems().getOrDefault(tables.snapshotTableName(), List.of());
  }

  private static long integer(Map<String, AttributeValue> item, String name) {
    AttributeValue value = item.get(name);
    if (value == null || value.type() != AttributeValue.Type.N)
      throw new StorageException("DynamoDB returned a missing or invalid history attribute");
    return new BigDecimal(value.n()).longValueExact();
  }

  private static void checkLimit(int retries) {
    if (retries >= RETRY_LIMIT)
      throw new StorageException("DynamoDB retention delete retry limit reached");
  }

  private void notifyFailure(Throwable failure) {
    RetentionFailure notification =
        new RetentionFailure(id, RetentionMode.DELETE, EventStoreExceptions.unwrap(failure));
    try {
      LOG.warn("DynamoDB snapshot retention failed: aid={}, mode=DELETE", id.asString());
    } catch (Exception loggingFailure) {
      // Logging cannot change the success of a committed write.
    }
    try {
      listener.ifPresent(callback -> callback.onRetentionFailure(notification));
    } catch (Exception listenerFailure) {
      // The callback cannot change the success of a committed write.
    }
  }
}
