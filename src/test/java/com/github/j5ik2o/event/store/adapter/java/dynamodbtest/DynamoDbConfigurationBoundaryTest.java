package com.github.j5ik2o.event.store.adapter.java.dynamodbtest;

import static com.github.j5ik2o.event.store.adapter.java.dynamodbtest.DynamoDbConfigurationFixture.*;
import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.j5ik2o.event.store.adapter.java.core.*;
import com.github.j5ik2o.event.store.adapter.java.dynamodb.*;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import software.amazon.awssdk.services.dynamodb.model.*;

class DynamoDbConfigurationBoundaryTest {
  @RegisterExtension
  static final DynamoDbConfigurationFixture fixture = new DynamoDbConfigurationFixture();

  static ObjectNode scenario(String id) throws Exception {
    for (JsonNode c :
        DynamoDbJson.mapper()
            .readTree(Files.readAllBytes(Path.of("conformance/dynamodb/configuration.json")))
            .path("cases")) if (c.path("id").asText().equals(id)) return c.deepCopy();
    throw new IllegalArgumentException(id);
  }

  private static ObjectNode partial(List<String> keys, int count) {
    ObjectNode fault =
        DynamoDbJson.object()
            .put("operation", 0)
            .put("phase", "configuration-read")
            .put("kind", "sdk-response")
            .put("injection", "replace-response");
    fault.putObject("details").set("unprocessed_keys", DynamoDbJson.mapper().valueToTree(keys));
    fault.putObject("repeat").put("mode", "count").put("count", count);
    return fault;
  }

  private static void expected(ObjectNode c, String category, int reads, int creates) {
    ObjectNode initialization = c.putObject("initialization");
    ObjectNode expect = initialization.putObject("expect");
    if (category == null) expect.put("result", "success");
    else expect.putObject("error").put("category", category);
    initialization
        .putObject("observe")
        .putObject("request_count")
        .put("configuration-read", reads)
        .put("configuration-create", creates);
  }

  @Test
  void zeroLimitNeverWaitsOrRetries() throws Exception {
    ObjectNode c = scenario("dynamodb-config-matching");
    ((ObjectNode) c.path("store")).put("retry_limit", 0);
    c.putArray("faults").add(partial(List.of("snapshot:__config__:0"), 1));
    expected(c, "storage", 1, 0);
    ObjectNode result = fixture.configuration(c);
    for (JsonNode actual : result)
      assertEquals(
          List.of(), DynamoDbJson.mapper().convertValue(actual.path("waits_ms"), List.class));
  }

  @Test
  void defaultTenRetriesExcludeInitialRequestAndCapWaitAtOneSecond() throws Exception {
    ObjectNode c = scenario("dynamodb-config-new");
    c.putArray("faults")
        .add(
            partial(
                List.of("journal:__config__:0", "snapshot:__config__:0", "head:__config__"), 11));
    expected(c, "storage", 11, 0);
    for (JsonNode actual : fixture.configuration(c)) {
      assertEquals(
          DynamoDbJson.mapper()
              .valueToTree(List.of(50L, 100L, 200L, 400L, 800L, 1000L, 1000L, 1000L, 1000L, 1000L)),
          actual.path("waits_ms"));
      for (JsonNode item : actual.path("items")) assertTrue(item.isEmpty());
    }
  }

  @Test
  void lastAllowedRetryCanFinishSuccessfully() throws Exception {
    ObjectNode c = scenario("dynamodb-config-matching");
    ((ObjectNode) c.path("store")).put("retry_limit", 1);
    c.putArray("faults").add(partial(List.of("snapshot:__config__:0", "head:__config__"), 1));
    expected(c, null, 2, 0);
    for (JsonNode actual : fixture.configuration(c))
      assertEquals(DynamoDbJson.mapper().valueToTree(List.of(50L)), actual.path("waits_ms"));
  }

  @Test
  void multiplePartialResponsesAccumulateAndOnlyReissuePendingKeys() throws Exception {
    ObjectNode c = scenario("dynamodb-config-matching");
    c.putArray("faults")
        .add(partial(List.of("snapshot:__config__:0", "head:__config__"), 1))
        .add(partial(List.of("head:__config__"), 1));
    expected(c, null, 3, 0);
    for (JsonNode actual : fixture.configuration(c)) {
      assertEquals(DynamoDbJson.mapper().valueToTree(List.of(50L, 100L)), actual.path("waits_ms"));
      assertEquals(
          Set.of("journal", "snapshot", "head"),
          requestRoles(actual.at("/requests/0/transmitted")));
      assertEquals(Set.of("snapshot", "head"), requestRoles(actual.at("/requests/1/transmitted")));
      assertEquals(Set.of("head"), requestRoles(actual.at("/requests/2/transmitted")));
    }
  }

  private static Set<String> requestRoles(JsonNode request) {
    Set<String> result = new HashSet<>();
    request
        .path("RequestItems")
        .fieldNames()
        .forEachRemaining(name -> result.add(name.substring(name.lastIndexOf('-') + 1)));
    return result;
  }

  @Test
  void creationWaitsUntilEveryUnprocessedKeyIsResolved() throws Exception {
    ObjectNode c = scenario("dynamodb-config-new");
    c.putArray("faults").add(partial(List.of("snapshot:__config__:0", "head:__config__"), 1));
    expected(c, null, 2, 1);
    for (JsonNode actual : fixture.configuration(c)) {
      assertEquals("BatchGetItem", actual.at("/requests/1/api").asText());
      assertEquals("TransactWriteItems", actual.at("/requests/2/api").asText());
      assertEquals(Set.of("snapshot", "head"), requestRoles(actual.at("/requests/1/transmitted")));
    }
  }

  @Test
  void transactionConflictFindsWinnerOnFullStrongReread() throws Exception {
    ObjectNode c = scenario("dynamodb-config-create-race");
    ((ObjectNode) c.at("/faults/0/details/cancellation_reasons/0"))
        .put("code", "TransactionConflict");
    fixture.configuration(c);
  }

  @Test
  void conflictWithNoWinnerStopsWithoutSecondCreation() throws Exception {
    for (String code : List.of("TransactionConflict", "ConditionalCheckFailed")) {
      ObjectNode c = scenario("dynamodb-config-create-race");
      ((ObjectNode) c.at("/faults/0/details/cancellation_reasons/0")).put("code", code);
      ((ObjectNode) c.at("/faults/0/details")).remove("install_items");
      expected(c, "storage", 2, 1);
      for (JsonNode actual : fixture.configuration(c)) {
        assertEquals(
            Set.of("journal", "snapshot", "head"),
            requestRoles(actual.at("/requests/2/transmitted")));
        for (JsonNode item : actual.path("items")) assertTrue(item.isEmpty());
      }
    }
  }

  @Test
  void conflictRereadUsesItsOwnRetryLimitAndAccumulation() throws Exception {
    for (boolean exhausted : List.of(false, true)) {
      for (boolean async : List.of(false, true)) {
        ObjectNode c = scenario("dynamodb-config-create-race");
        try (DynamoDbTestContext context = fixture.createContext()) {
          DynamoDbConfigurationTables.create(context, RetentionPolicy.none());
          List<FaultRegistry.Fault> registered = register(context, c.path("faults"));
          FaultRegistry.Effect readPlan =
              new FaultRegistry.Effect() {
                int reads;
                FaultRegistry.Effect current;

                public boolean supports(FaultRegistry.Injection injection) {
                  return injection == FaultRegistry.Injection.REPLACE_RESPONSE;
                }

                public software.amazon.awssdk.core.SdkRequest prepare(
                    software.amazon.awssdk.core.SdkRequest request,
                    FaultRegistry.Injection injection) {
                  Map<String, List<Map<String, AttributeValue>>> pending;
                  if (reads == 0)
                    pending =
                        Map.of(
                            context.journal,
                            List.of(key("journal")),
                            context.snapshot,
                            List.of(key("snapshot")),
                            context.head,
                            List.of(key("head")));
                  else if (reads == 2 || (reads == 3 && exhausted))
                    pending =
                        Map.of(
                            context.snapshot,
                            List.of(key("snapshot")),
                            context.head,
                            List.of(key("head")));
                  else pending = Map.of();
                  reads++;
                  current =
                      ((DynamoDbFaultEffects.PartialBatchGet)
                              DynamoDbFaultEffects.partialBatchGet(context.admin, pending))
                          .forRequest();
                  return current.prepare(request, injection);
                }

                public software.amazon.awssdk.core.SdkResponse response(
                    DynamoDbRequestRecorder.Request request,
                    software.amazon.awssdk.core.SdkResponse response) {
                  return current.response(request, response);
                }
              };
          FaultRegistry.Fault readFault =
              context.faults.register(
                  0, "configuration-read", 4, FaultRegistry.Injection.REPLACE_RESPONSE, readPlan);
          FaultRegistry.Operation operation = context.faults.begin(0, false);
          List<Long> waits = new ArrayList<>();
          DynamoDbTableConfig tables =
              DynamoDbConfigurationTables.config(context).configurationReadRetryLimit(1).build();
          org.junit.jupiter.api.function.Executable generation =
              () -> {
                if (async)
                  assertNotNull(
                      DynamoDbTestFactory.createAsync(
                              context.async,
                              tables,
                              storeConfig(),
                              millis -> {
                                waits.add(millis);
                                return CompletableFuture.completedFuture(null);
                              })
                          .join());
                else
                  assertNotNull(
                      DynamoDbTestFactory.create(
                          context.client, tables, storeConfig(), waits::add));
              };
          if (exhausted)
            assertInstanceOf(
                StorageException.class,
                EventStoreExceptions.unwrap(assertThrows(Exception.class, generation)));
          else assertDoesNotThrow(generation);
          context.recorder.requestsFinished(operation).join();
          assertEquals(0, context.faults.pending(operation));
          assertEquals(0, context.faults.reservations(readFault));
          assertEquals(4, context.faults.applications(readFault));
          assertEquals(1, context.faults.applications(registered.get(0)));
          assertEquals("passed", context.faults.finish(operation).status);
          assertEquals(List.of(50L, 50L), waits);
          List<DynamoDbRequestRecorder.Request> requests = context.recorder.requests();
          assertEquals(5, requests.size());
          assertEquals(
              Set.of("journal:__config__:0", "snapshot:__config__:0", "head:__config__"),
              requested(context, requests.get(3).transmitted));
          assertEquals(
              Set.of("snapshot:__config__:0", "head:__config__"),
              requested(context, requests.get(4).transmitted));
          for (DynamoDbRequestRecorder.Request request : requests)
            if (request.api.equals("BatchGetItem")) strong(request.transmitted);
        }
      }
    }
  }

  @Test
  void readAndCreateSdkFailuresAreStorageWithoutReread() throws Exception {
    for (String phase : List.of("configuration-read", "configuration-create")) {
      for (String code : List.of("InternalServerError", "ProvisionedThroughputExceededException")) {
        ObjectNode c = scenario("dynamodb-config-new");
        ObjectNode fault =
            c.putArray("faults")
                .addObject()
                .put("operation", 0)
                .put("phase", phase)
                .put("kind", "sdk-error")
                .put("injection", "replace-request");
        fault.putObject("details").put("code", code);
        fault.putObject("repeat").put("mode", "count").put("count", 1);
        expected(c, "storage", 1, phase.equals("configuration-create") ? 1 : 0);
        fixture.configuration(c);
      }
    }
    ObjectNode c = scenario("dynamodb-config-create-race");
    ((ObjectNode) c.at("/faults/0/details/cancellation_reasons/0"))
        .put("code", "ProvisionedThroughputExceeded");
    ((ObjectNode) c.at("/faults/0/details")).remove("install_items");
    expected(c, "storage", 1, 1);
    fixture.configuration(c);
  }

  @Test
  void duplicateTableNamesAreRejectedBeforeEitherSdkEntryCommunicates() {
    DynamoDbTestContext c = fixture.createContext();
    try {
      DynamoDbConfigurationTables.create(c, RetentionPolicy.none());
      Map<String, Map<String, AttributeValue>> before = stored(c);
      FaultRegistry.Operation operation = c.faults.begin(0, false);
      for (List<String> tables :
          List.of(
              List.of(c.journal, c.journal, c.head),
              List.of(c.journal, c.snapshot, c.journal),
              List.of(c.journal, c.snapshot, c.snapshot),
              List.of(c.head, c.head, c.head))) {
        DynamoDbTableConfig.Builder builder =
            DynamoDbConfigurationTables.config(c)
                .journalTableName(tables.get(0))
                .snapshotTableName(tables.get(1))
                .headTableName(tables.get(2));
        assertThrows(
            ConfigurationException.class,
            () -> DynamoDbEventStore.create(c.client, builder.build(), storeConfig()));
        assertThrows(
            ConfigurationException.class,
            () -> DynamoDbEventStore.createAsync(c.async, builder.build(), storeConfig()));
        assertTrue(c.recorder.requests().isEmpty(), tables.toString());
        assertEquals(before, stored(c));
      }
      c.recorder.requestsFinished(operation).join();
      assertEquals(0, c.faults.pending(operation));
      assertEquals("passed", c.faults.finish(operation).status);
    } finally {
      close(c);
    }
  }

  @Test
  void publicFactoriesCommunicateWithBothSdkClientsOnTheSameRealTables() {
    DynamoDbTestContext c = fixture.createContext();
    try {
      DynamoDbConfigurationTables.create(c, RetentionPolicy.none());
      DynamoDbTableConfig tables = DynamoDbConfigurationTables.config(c).build();
      FaultRegistry.Operation sync = c.faults.begin(0, false);
      assertNotNull(DynamoDbEventStore.create(c.client, tables, storeConfig()));
      c.recorder.requestsFinished(sync).join();
      assertEquals("passed", c.faults.finish(sync).status);
      Map<String, Map<String, AttributeValue>> created = stored(c);
      assertFalse(created.get("journal").get("store_id").s().isEmpty());
      FaultRegistry.Operation async = c.faults.begin(1, false);
      assertNotNull(DynamoDbEventStore.createAsync(c.async, tables, storeConfig()).join());
      c.recorder.requestsFinished(async).join();
      assertEquals(0, c.faults.pending(async));
      assertEquals("passed", c.faults.finish(async).status);
      assertEquals(created, stored(c));
      List<DynamoDbRequestRecorder.Request> requests = c.recorder.requests();
      assertEquals(3, requests.size());
      assertEquals("BatchGetItem", requests.get(0).api);
      assertEquals("TransactWriteItems", requests.get(1).api);
      assertEquals("BatchGetItem", requests.get(2).api);
      assertEquals(
          Set.of("journal:__config__:0", "snapshot:__config__:0", "head:__config__"),
          requested(c, requests.get(2).transmitted));
      strong(requests.get(0).transmitted);
      strong(requests.get(2).transmitted);
    } finally {
      close(c);
    }
    // Closing one scene must leave both async communication paths usable in the next scene.
    try (DynamoDbTestContext next = fixture.createContext()) {
      assertNotEquals(c.journal, next.journal);
      DynamoDbConfigurationTables.create(next, RetentionPolicy.none());
      for (Map<String, AttributeValue> item : stored(next).values()) assertTrue(item.isEmpty());
      assertFalse(next.adminAsync.listTables().join().tableNames().isEmpty());
      FaultRegistry.Operation operation = next.faults.begin(0, false);
      assertNotNull(
          DynamoDbEventStore.createAsync(
                  next.async, DynamoDbConfigurationTables.config(next).build(), storeConfig())
              .join());
      next.recorder.requestsFinished(operation).join();
      assertEquals(0, next.faults.pending(operation));
      assertEquals("passed", next.faults.finish(operation).status);
    }
  }

  @Test
  void realConnectionFailureIsStorageAndHasOneTerminalRequestPerEntry() {
    for (boolean async : List.of(false, true)) {
      DynamoDbTestContext c = fixture.createContext(URI.create("http://127.0.0.1:1"));
      try {
        FaultRegistry.Operation operation = c.faults.begin(0, false);
        DynamoDbTableConfig tables = DynamoDbConfigurationTables.config(c).build();
        Throwable error =
            async
                ? EventStoreExceptions.unwrap(
                    assertThrows(
                        CompletionException.class,
                        () ->
                            DynamoDbEventStore.createAsync(c.async, tables, storeConfig()).join()))
                : assertThrows(
                    StorageException.class,
                    () -> DynamoDbEventStore.create(c.client, tables, storeConfig()));
        assertInstanceOf(StorageException.class, error);
        c.recorder.requestsFinished(operation).join();
        assertEquals(0, c.faults.pending(operation));
        assertEquals("passed", c.faults.finish(operation).status);
        assertEquals(1, c.recorder.requests().size());
        assertEquals("BatchGetItem", c.recorder.requests().get(0).api);
      } finally {
        c.close();
      }
      assertTrue(c.closed());
    }
  }

  @Test
  void asyncGenerationRemainsPendingUntilItsWaitCompletesAndKeepsClientBorrowed() throws Exception {
    DynamoDbTestContext c = fixture.createContext();
    CompletableFuture<Void> gate = new CompletableFuture<>();
    CompletableFuture<Long> entered = new CompletableFuture<>();
    CompletableFuture<AsyncEventStore<String, String>> generation = null;
    try {
      DynamoDbConfigurationTables.create(c, RetentionPolicy.none());
      install(c, scenario("dynamodb-config-matching").at("/seed/items"));
      c.faults.register(
          0,
          "configuration-read",
          1,
          FaultRegistry.Injection.REPLACE_RESPONSE,
          DynamoDbFaultEffects.partialBatchGet(c.admin, Map.of(c.head, List.of(key("head")))));
      FaultRegistry.Operation operation = c.faults.begin(0, false);
      generation =
          DynamoDbTestFactory.createAsync(
              c.async,
              DynamoDbConfigurationTables.config(c).build(),
              storeConfig(),
              millis -> {
                entered.complete(millis);
                return gate;
              });
      assertEquals(50L, entered.get(10, TimeUnit.SECONDS));
      assertFalse(generation.isDone());
      assertEquals(1, c.recorder.requests().size());
      gate.complete(null);
      AsyncEventStore<String, String> store = generation.get(10, TimeUnit.SECONDS);
      c.recorder.requestsFinished(operation).join();
      assertEquals(0, c.faults.pending(operation));
      assertEquals("passed", c.faults.finish(operation).status);
      assertThrows(CompletionException.class, () -> store.persistEvent(null).join());
      FaultRegistry.Operation borrowed = c.faults.begin(1, false);
      assertFalse(c.client.listTables().tableNames().isEmpty());
      assertFalse(c.async.listTables().join().tableNames().isEmpty());
      c.recorder.requestsFinished(borrowed).join();
      assertEquals("passed", c.faults.finish(borrowed).status);
    } finally {
      gate.complete(null);
      if (generation != null) generation.handle((store, error) -> null).join();
      close(c);
    }
  }
}
