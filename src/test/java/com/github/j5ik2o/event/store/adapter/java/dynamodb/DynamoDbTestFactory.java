package com.github.j5ik2o.event.store.adapter.java.dynamodb;

import com.github.j5ik2o.event.store.adapter.java.core.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.function.LongConsumer;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

/** Test-only access to the internal wait boundary. */
public final class DynamoDbTestFactory {
  private DynamoDbTestFactory() {}

  public static <P, A> EventStore<P, A> create(
      DynamoDbClient client,
      DynamoDbTableConfig tables,
      EventStoreConfig<P, A> config,
      LongConsumer wait) {
    return DynamoDbEventStore.create(
        client,
        tables,
        config,
        new Sleeper() {
          @Override
          public void sleep(long millis) {
            wait.accept(millis);
          }
        });
  }

  public static <P, A> CompletableFuture<AsyncEventStore<P, A>> createAsync(
      DynamoDbAsyncClient client,
      DynamoDbTableConfig tables,
      EventStoreConfig<P, A> config,
      Function<Long, CompletableFuture<Void>> wait) {
    return DynamoDbEventStore.createAsync(
        client,
        tables,
        config,
        new Sleeper() {
          @Override
          public CompletableFuture<Void> sleepAsync(long millis) {
            return wait.apply(millis);
          }
        });
  }
}
