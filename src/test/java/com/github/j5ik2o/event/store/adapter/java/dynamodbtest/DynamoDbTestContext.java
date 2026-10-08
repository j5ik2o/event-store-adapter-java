package com.github.j5ik2o.event.store.adapter.java.dynamodbtest;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import software.amazon.awssdk.core.interceptor.ExecutionInterceptor;
import software.amazon.awssdk.http.apache5.Apache5HttpClient;
import software.amazon.awssdk.http.nio.netty.SdkEventLoopGroup;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;

/** Test resource ownership only; this does not implement or validate a storage layout. */
final class DynamoDbTestContext implements AutoCloseable {
  final String journal;
  final String snapshot;
  final String head;
  final String historyIndex = "test-history";
  final FaultRegistry faults = new FaultRegistry();
  final DynamoDbRequestTargets targets;
  final DynamoDbRequestRecorder recorder;
  final DynamoDbClient admin;
  final DynamoDbAsyncClient adminAsync;
  final DynamoDbClient client;
  final DynamoDbAsyncClient async;
  private final FaultHttpClient http;
  private final FaultAsyncHttpClient asyncHttp;

  private static final class Acquisition {
    final String tableName;
    final CompletableFuture<Throwable> settled = new CompletableFuture<>();

    Acquisition(String tableName) {
      this.tableName = tableName;
    }
  }

  private final List<Acquisition> acquisitions = new ArrayList<>();
  private final ExecutorService cleanup = Executors.newSingleThreadExecutor();
  private CompletableFuture<Throwable> termination;
  private boolean closed;

  DynamoDbTestContext(URI endpoint, ExecutionInterceptor... acquisitionInterceptors) {
    this(endpoint, null, acquisitionInterceptors);
  }

  /** The caller owns a supplied event loop; this context still owns every client and HTTP pool. */
  DynamoDbTestContext(
      URI endpoint,
      SdkEventLoopGroup borrowedEventLoop,
      ExecutionInterceptor... acquisitionInterceptors) {
    String prefix = "fault-test-" + UUID.randomUUID();
    journal = prefix + "-journal";
    snapshot = prefix + "-snapshot";
    head = prefix + "-head";
    targets = new DynamoDbRequestTargets(journal, snapshot, head, historyIndex);
    recorder = new DynamoDbRequestRecorder(faults, targets);
    // Each acquired client/transport is closed if a later construction fails.
    List<AutoCloseable> acquired = new ArrayList<>();
    try {
      admin = DynamoDbTestClients.admin(endpoint);
      acquired.add(admin);
      adminAsync =
          borrowedEventLoop == null
              ? DynamoDbTestClients.adminAsync(endpoint, acquisitionInterceptors)
              : DynamoDbTestClients.adminAsync(
                  endpoint, borrowedEventLoop, acquisitionInterceptors);
      acquired.add(adminAsync);
      http = new FaultHttpClient(Apache5HttpClient.builder().build(), recorder);
      acquired.add(http);
      asyncHttp =
          new FaultAsyncHttpClient(
              DynamoDbTestClients.asyncHttp(borrowedEventLoop).build(), recorder);
      acquired.add(asyncHttp);
      client = DynamoDbTestClients.observed(endpoint, recorder, http);
      acquired.add(client);
      async = DynamoDbTestClients.observedAsync(endpoint, recorder, asyncHttp);
      acquired.add(async);
    } catch (Throwable error) {
      for (int i = acquired.size() - 1; i >= 0; i--) {
        try {
          acquired.get(i).close();
        } catch (Throwable failure) {
          error.addSuppressed(failure);
        }
      }
      cleanup.shutdown();
      throw error;
    }
  }

  static CreateTableRequest table(String name, String sortKey) {
    CreateTableRequest.Builder builder =
        CreateTableRequest.builder()
            .tableName(name)
            .billingMode(BillingMode.PAY_PER_REQUEST)
            .attributeDefinitions(
                AttributeDefinition.builder()
                    .attributeName("aid")
                    .attributeType(ScalarAttributeType.S)
                    .build())
            .keySchema(
                KeySchemaElement.builder().attributeName("aid").keyType(KeyType.HASH).build());
    if (sortKey != null) {
      builder.attributeDefinitions(
          AttributeDefinition.builder()
              .attributeName("aid")
              .attributeType(ScalarAttributeType.S)
              .build(),
          AttributeDefinition.builder()
              .attributeName(sortKey)
              .attributeType(ScalarAttributeType.N)
              .build());
      builder.keySchema(
          KeySchemaElement.builder().attributeName("aid").keyType(KeyType.HASH).build(),
          KeySchemaElement.builder().attributeName(sortKey).keyType(KeyType.RANGE).build());
    }
    return builder.build();
  }

  void createMinimalTables() {
    acquire(table(journal, "seq_nr"));
    acquire(table(snapshot, "skey"));
    acquire(table(head, null));
  }

  void acquire(CreateTableRequest request) {
    acquireAsync(request, () -> {}).join();
  }

  synchronized CompletableFuture<CreateTableResponse> acquireAsync(
      CreateTableRequest request, Runnable afterActualCreation) {
    if (termination != null) throw new IllegalStateException("Context is closing");
    CompletableFuture<CreateTableResponse> result = new CompletableFuture<>();
    // Retain the candidate before invoking the SDK: creation can succeed despite a lost response.
    Acquisition acquisition = new Acquisition(request.tableName());
    acquisitions.add(acquisition);
    CompletableFuture<CreateTableResponse> actual;
    try {
      actual = adminAsync.createTable(request);
    } catch (Throwable failure) {
      acquisition.settled.complete(failure);
      result.completeExceptionally(failure);
      return result;
    }
    actual.whenComplete(
        (response, error) -> {
          Throwable failure = error;
          try {
            if (error == null) {
              // This runs after real creation, even if the caller cancels its result.
              afterActualCreation.run();
            }
          } catch (Throwable acquisitionFailure) {
            failure = acquisitionFailure;
          } finally {
            // Keep the SDK outcome separate from failure/cancellation of the caller's result.
            acquisition.settled.complete(error);
          }
          // Notify callers only after acquisition has reached its independent terminal state.
          if (failure == null) result.complete(response);
          else result.completeExceptionally(failure);
        });
    return result;
  }

  CompletableFuture<Throwable> finish(Throwable primary) {
    return finish(primary, () -> {});
  }

  synchronized CompletableFuture<Throwable> finish(Throwable primary, Runnable afterDeletion) {
    if (termination != null) return termination;
    List<Acquisition> pending = List.copyOf(acquisitions);
    termination =
        CompletableFuture.allOf(
                pending.stream().map(a -> a.settled).toArray(CompletableFuture<?>[]::new))
            .thenApplyAsync(
                ignored -> {
                  Throwable failure = primary;
                  Set<String> candidates = new LinkedHashSet<>();
                  for (Acquisition acquisition : pending) {
                    Throwable outcome = acquisition.settled.join();
                    while (outcome instanceof CompletionException && outcome.getCause() != null) {
                      outcome = outcome.getCause();
                    }
                    if (outcome instanceof ResourceInUseException) continue;
                    String name = acquisition.tableName;
                    // Unknown outcomes confer cleanup responsibility only for our generated names.
                    if (outcome == null || List.of(journal, snapshot, head).contains(name)) {
                      candidates.add(name);
                    }
                  }
                  for (String name : candidates) {
                    try {
                      admin.deleteTable(DeleteTableRequest.builder().tableName(name).build());
                    } catch (ResourceNotFoundException absent) {
                      // A failed acquisition may never have created its candidate.
                    } catch (Throwable error) {
                      failure = retain(failure, error);
                    }
                  }
                  try {
                    afterDeletion.run();
                  } catch (Throwable error) {
                    failure = retain(failure, error);
                  }
                  for (AutoCloseable resource :
                      List.of(async, client, asyncHttp, http, adminAsync, admin)) {
                    try {
                      resource.close();
                    } catch (Throwable error) {
                      failure = retain(failure, error);
                    }
                  }
                  synchronized (DynamoDbTestContext.this) {
                    closed = true;
                  }
                  cleanup.shutdown();
                  return failure;
                },
                cleanup);
    return termination;
  }

  private static Throwable retain(Throwable primary, Throwable cleanup) {
    if (primary == null) return cleanup;
    if (primary != cleanup) primary.addSuppressed(cleanup);
    return primary;
  }

  synchronized boolean closed() {
    return closed;
  }

  @Override
  public void close() {
    Throwable failure = finish(null).join();
    if (failure != null) throw new CompletionException(failure);
  }
}
