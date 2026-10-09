package com.github.j5ik2o.event.store.adapter.java.dynamodbtest;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.j5ik2o.event.store.adapter.java.core.*;
import com.github.j5ik2o.event.store.adapter.java.dynamodb.*;
import java.math.BigDecimal;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import software.amazon.awssdk.http.nio.netty.SdkEventLoopGroup;
import software.amazon.awssdk.services.dynamodb.model.*;

/** Public test bridge; all resource and fault machinery remains in the test package. */
public final class DynamoDbConfigurationFixture implements BeforeAllCallback {
  private final DynamoDbLocalExtension local = new DynamoDbLocalExtension();
  private CommunicationResources resources;

  @Override
  public void beforeAll(ExtensionContext context) {
    local.beforeAll(context);
    resources =
        context
            .getStore(ExtensionContext.Namespace.create(DynamoDbConfigurationFixture.class))
            .getOrComputeIfAbsent(
                CommunicationResources.class,
                key -> new CommunicationResources(context.getRequiredTestClass().getName()),
                CommunicationResources.class);
  }

  URI endpoint() {
    return local.endpoint();
  }

  SdkEventLoopGroup eventLoop() {
    return resources.eventLoop;
  }

  DynamoDbTestContext createContext() {
    return createContext(endpoint());
  }

  DynamoDbTestContext createContext(URI endpoint) {
    long started = System.nanoTime();
    DynamoDbTestContext context = new DynamoDbTestContext(endpoint, resources.eventLoop);
    ObjectNode observation = DynamoDbJson.object();
    observation.put("endpoint", endpoint.toString());
    observation.put("context_construct_ns", System.nanoTime() - started);
    observation.set(
        "tables", DynamoDbJson.mapper().valueToTree(DynamoDbConfigurationTables.names(context)));
    resources.contexts.put(context, observation);
    return context;
  }

  /** The class Store closes this after all scene-owned SDK clients and HTTP pools have closed. */
  private static final class CommunicationResources
      implements ExtensionContext.Store.CloseableResource {
    final SdkEventLoopGroup eventLoop = SdkEventLoopGroup.builder().numberOfThreads(2).build();
    final Map<DynamoDbTestContext, ObjectNode> contexts = new LinkedHashMap<>();
    private final String owner;

    CommunicationResources(String owner) {
      this.owner = owner;
    }

    @Override
    public void close() throws Exception {
      ObjectNode observation = DynamoDbJson.object().put("owner", owner);
      ArrayNode scenes = observation.putArray("contexts");
      boolean contextsTerminatedSuccessfully = false;
      try {
        for (Map.Entry<DynamoDbTestContext, ObjectNode> scene : contexts.entrySet()) {
          DynamoDbTestContext context = scene.getKey();
          scenes.add(scene.getValue().put("resources_closed", context.closed()));
          assertTrue(context.closed(), "Scene resources must close before the event loop");
          assertNull(context.finish(null).join(), "Scene termination must succeed");
        }
        contextsTerminatedSuccessfully = true;
      } finally {
        long quietPeriodSeconds = contextsTerminatedSuccessfully ? 0 : 2;
        observation.put("contexts_terminated_successfully", contextsTerminatedSuccessfully);
        observation.put("event_loop_quiet_period_seconds", quietPeriodSeconds);
        long started = System.nanoTime();
        io.netty.util.concurrent.Future<?> termination =
            eventLoop.eventLoopGroup().shutdownGracefully(quietPeriodSeconds, 15, TimeUnit.SECONDS);
        termination.awaitUninterruptibly();
        observation.put("event_loop_shutdown_ns", System.nanoTime() - started);
        observation.put("termination_future_success", termination.isSuccess());
        observation.put("event_loop_terminated", eventLoop.eventLoopGroup().isTerminated());
        Path directory = Path.of("build/reports/dynamodb-configuration-resources");
        Files.createDirectories(directory);
        Files.writeString(directory.resolve(owner + ".json"), observation.toPrettyString());
        assertTrue(termination.isSuccess(), () -> String.valueOf(termination.cause()));
        assertTrue(eventLoop.eventLoopGroup().isTerminated());
      }
    }
  }

  static EventStoreConfig<String, String> storeConfig() {
    return EventStoreConfig.<String, String>builder()
        .payloadSerializer(JsonPayloadSerializer.of(String.class))
        .snapshotSerializer(JsonPayloadSerializer.of(String.class))
        .build();
  }

  /** Executes both public SDK paths; expectations are used only after observing the result. */
  public ObjectNode configuration(JsonNode scenario) {
    ObjectNode result = DynamoDbJson.object();
    configuration(scenario, result);
    return result;
  }

  /** Records observations in the caller's node, including when a comparison fails. */
  public void configuration(JsonNode scenario, ObjectNode result) {
    String unsupported = unsupportedConfiguration(scenario);
    if (unsupported != null) {
      result.put("unsupported", unsupported);
      return;
    }
    Throwable firstFailure = null;
    for (boolean async : List.of(false, true)) {
      ObjectNode actual = result.putObject(async ? "async" : "sync");
      try {
        configuration(scenario, async, actual);
      } catch (RuntimeException | AssertionError failure) {
        recordComparisonFailure(actual, failure);
        if (firstFailure == null) firstFailure = failure;
        else firstFailure.addSuppressed(failure);
      }
    }
    if (firstFailure instanceof AssertionError) throw (AssertionError) firstFailure;
    if (firstFailure != null) throw (RuntimeException) firstFailure;
    if (scenario.path("id").asText().equals("dynamodb-config-new")
        && result.at("/sync/result").asText().equals("success")
        && result.at("/async/result").asText().equals("success"))
      assertNotEquals(
          result.at("/sync/items/journal/store_id/S"),
          result.at("/async/items/journal/store_id/S"));
  }

  private static String unsupportedConfiguration(JsonNode scenario) {
    String binding =
        DynamoDbSnapshotRetentionFixture.unsupportedBindings(
            scenario.at("/initialization/observe/items"));
    if (binding != null) return binding;
    if (!scenario.path("steps").isEmpty()) return "未接続の初期化後操作";
    for (String field : stringsFromFields(scenario.at("/initialization/observe")))
      if (!Set.of(
              "requests",
              "request_count",
              "minimum_request_count",
              "no_requests_in_phases",
              "items",
              "notifications")
          .contains(field)) return "未接続の観測: " + field;
    for (JsonNode fault : scenario.path("faults")) {
      String kind = fault.path("kind").asText(), phase = fault.path("phase").asText();
      JsonNode details = fault.path("details");
      Set<String> fields = stringsFromFields(details);
      if (kind.equals("sdk-response")
          && phase.equals("configuration-read")
          && fields.contains("unprocessed_keys")
          && Set.of("responses", "unprocessed_keys").containsAll(fields)) {
        for (Iterator<Map.Entry<String, JsonNode>> it = details.path("responses").fields();
            it.hasNext(); ) {
          Map.Entry<String, JsonNode> entry = it.next();
          if (!Set.of("journal", "snapshot", "head").contains(entry.getKey())
              || !entry.getValue().asText().equals("seed-config")) return "未接続の障害応答";
        }
        for (JsonNode key : details.path("unprocessed_keys"))
          if (!Set.of("journal:__config__:0", "snapshot:__config__:0", "head:__config__")
              .contains(key.asText())) return "未接続の未処理キー";
        continue;
      }
      if (kind.equals("sdk-error")
          && Set.of("configuration-read", "configuration-create").contains(phase)
          && fault.path("injection").asText().equals("replace-request")
          && Set.of("code", "message", "cancellation_reasons", "install_items").containsAll(fields)
          && Set.of(
                  "InternalServerError",
                  "ProvisionedThroughputExceededException",
                  "TransactionCanceledException")
              .contains(details.path("code").asText())) {
        if (!details.path("code").asText().equals("TransactionCanceledException")
            && (details.has("cancellation_reasons") || details.has("install_items")))
          return "未接続のSDK障害条件";
        for (JsonNode reason : details.path("cancellation_reasons"))
          if (!stringsFromFields(reason).equals(Set.of("target", "code"))) return "未接続の取消理由条件";
        continue;
      }
      return "未接続の障害: " + kind + "/" + phase;
    }
    return null;
  }

  private void configuration(JsonNode scenario, boolean async, ObjectNode actual) {
    DynamoDbTestContext c = createContext();
    ObjectNode timing = resources.contexts.get(c);
    timing.put("case_id", scenario.path("id").asText());
    timing.put("sdk_path", async ? "async" : "sync");
    try {
      long preparing = System.nanoTime();
      RetentionPolicy policy = retention(scenario.path("store"));
      DynamoDbConfigurationTables.create(c, policy);
      install(c, scenario.path("seed").path("items"));
      Map<String, Map<String, AttributeValue>> before = stored(c);
      timing.put("table_prepare_ns", System.nanoTime() - preparing);
      timing.set("initial_items", DynamoDbJson.sdk(before));
      List<FaultRegistry.Fault> faults = register(c, scenario.path("faults"));
      DynamoDbTableConfig.Builder builder =
          DynamoDbConfigurationTables.config(c).retentionPolicy(policy);
      if (scenario.path("store").has("retry_limit"))
        builder.configurationReadRetryLimit(scenario.at("/store/retry_limit").intValue());
      DynamoDbTableConfig tables = builder.build();
      List<Long> waits = new ArrayList<>();
      List<RetentionFailure> notifications = new ArrayList<>();
      EventStoreConfig<String, String> config =
          EventStoreConfig.<String, String>builder()
              .payloadSerializer(JsonPayloadSerializer.of(String.class))
              .snapshotSerializer(JsonPayloadSerializer.of(String.class))
              .retentionFailureListener(notifications::add)
              .build();
      FaultRegistry.Operation operation = c.faults.begin(0, false);
      Throwable failure = null;
      Object store = null;
      long requesting = System.nanoTime();
      try {
        if (async)
          store =
              DynamoDbTestFactory.createAsync(
                      c.async,
                      tables,
                      config,
                      millis -> {
                        waits.add(millis);
                        return CompletableFuture.completedFuture(null);
                      })
                  .join();
        else store = DynamoDbTestFactory.create(c.client, tables, config, waits::add);
      } catch (RuntimeException error) {
        failure = EventStoreExceptions.unwrap(error);
      }
      c.recorder.requestsFinished(operation).join();
      timing.put("request_ns", System.nanoTime() - requesting);
      actual.put("result", failure == null ? "success" : category(failure));
      if (failure != null) {
        actual.put("exception", failure.getClass().getName()).put("message", failure.getMessage());
        if (failure.getCause() != null)
          actual
              .put("cause", failure.getCause().getClass().getName())
              .put("cause_message", failure.getCause().getMessage());
      }
      actual.set("waits_ms", DynamoDbJson.mapper().valueToTree(waits));
      List<DynamoDbRequestRecorder.Request> requests = c.recorder.requests();
      actual.set("requests", requestsJson(requests));
      Map<String, Map<String, AttributeValue>> items = stored(c);
      actual.set("items", DynamoDbJson.sdk(items));
      int pending = c.faults.pending(operation);
      actual.put("pending", pending);
      actual.put("request_terminal", c.recorder.requestsFinished(operation).isDone());
      ArrayNode observedNotifications = actual.putArray("notifications");
      notifications.forEach(notification -> observedNotifications.add("retention-failure"));
      assertEquals(0, pending);
      ArrayNode applications = actual.putArray("faults");
      for (FaultRegistry.Fault fault : faults) {
        int reservations = c.faults.reservations(fault);
        int count = c.faults.applications(fault);
        applications
            .addObject()
            .put("phase", fault.phase)
            .put("applications", count)
            .put("count", fault.count)
            .put("reservations", reservations);
        assertEquals(0, reservations);
        assertTrue(count > 0);
        if (fault.count != -1) assertEquals(fault.count, count);
      }
      String faultStatus = c.faults.finish(operation).status;
      actual.put("fault_status", faultStatus);
      assertEquals("passed", faultStatus);
      if (failure == null) assertNotNull(store);
      JsonNode initialization = scenario.path("initialization");
      actual.put("comparison_position", "/initialization/expect");
      assertOutcome(initialization.path("expect"), failure);
      actual.put("comparison_position", "/initialization/observe");
      observe(c, initialization.path("observe"), requests, waits, items);
      if (initialization.path("observe").has("notifications"))
        assertEquals(initialization.at("/observe/notifications"), observedNotifications);
      actual.remove("comparison_position");
      if (!scenario.has("faults") && !before.values().stream().allMatch(Map::isEmpty))
        assertEquals(before, items);
      assertTrue(
          c.admin
              .query(
                  QueryRequest.builder()
                      .tableName(c.snapshot)
                      .indexName(c.historyIndex)
                      .keyConditionExpression("aid = :aid")
                      .expressionAttributeValues(Map.of(":aid", AttributeValue.fromS("__config__")))
                      .build())
              .items()
              .isEmpty());
      FaultRegistry.Operation borrowed = c.faults.begin(1, false);
      assertFalse(c.client.listTables().tableNames().isEmpty());
      assertFalse(c.async.listTables().join().tableNames().isEmpty());
      c.recorder.requestsFinished(borrowed).join();
      assertEquals(0, c.faults.pending(borrowed));
      assertEquals("passed", c.faults.finish(borrowed).status);
      actual.put("borrowed_clients_usable", true);
    } finally {
      long closing = System.nanoTime();
      try {
        close(c);
      } finally {
        timing.put("context_close_ns", System.nanoTime() - closing);
        actual.put("resources_closed", c.closed());
      }
    }
  }

  static String category(Throwable failure) {
    assertInstanceOf(EventStoreException.class, failure);
    return ((EventStoreException) failure).category().name().toLowerCase(Locale.ROOT);
  }

  static void assertOutcome(JsonNode expected, Throwable failure) {
    if (expected.has("error")) {
      assertNotNull(failure);
      assertEquals(expected.at("/error/category").asText(), category(failure));
      if (expected.path("error").has("rule"))
        assertEquals(
            expected.at("/error/rule").asText(),
            assertInstanceOf(ContractViolationException.class, failure).rule());
      for (JsonNode word : expected.at("/error/message/must_contain"))
        assertTrue(failure.getMessage().contains(word.asText()));
      for (JsonNode word : expected.at("/error/message/must_not_contain"))
        assertFalse(failure.getMessage().contains(word.asText()));
    } else {
      assertEquals("success", expected.path("result").asText());
      assertNull(failure);
    }
  }

  static void recordComparisonFailure(ObjectNode actual, Throwable failure) {
    ObjectNode comparison = actual.putObject("comparison_failure");
    comparison.put("position", actual.path("comparison_position").asText());
    comparison.put("reason", failure.toString());
    if (failure instanceof org.opentest4j.AssertionFailedError) {
      org.opentest4j.AssertionFailedError assertion = (org.opentest4j.AssertionFailedError) failure;
      if (assertion.isExpectedDefined())
        comparison.set(
            "expected", DynamoDbJson.mapper().valueToTree(assertion.getExpected().getValue()));
      if (assertion.isActualDefined())
        comparison.set(
            "actual", DynamoDbJson.mapper().valueToTree(assertion.getActual().getValue()));
    }
  }

  static RetentionPolicy retention(JsonNode store) {
    if (!store.hasNonNull("retention_count")) return RetentionPolicy.none();
    return "ttl".equals(store.path("retention_mode").asText())
        ? RetentionPolicy.ttl(
            store.path("retention_count").intValue(), store.path("ttl_grace_seconds").longValue())
        : RetentionPolicy.delete(store.path("retention_count").intValue());
  }

  static String table(DynamoDbTestContext c, String role) {
    switch (role) {
      case "journal":
        return c.journal;
      case "snapshot":
        return c.snapshot;
      case "head":
        return c.head;
      default:
        throw new IllegalArgumentException("Unknown table role: " + role);
    }
  }

  static Map<String, AttributeValue> key(String role) {
    Map<String, AttributeValue> key = new LinkedHashMap<>();
    key.put("aid", AttributeValue.fromS("__config__"));
    if (!role.equals("head"))
      key.put(role.equals("journal") ? "seq_nr" : "skey", AttributeValue.fromN("0"));
    return key;
  }

  static void install(DynamoDbTestContext c, JsonNode definitions) {
    for (JsonNode definition : definitions) {
      Map<String, AttributeValue> attributes = new LinkedHashMap<>();
      definition
          .path("attributes")
          .fields()
          .forEachRemaining(
              entry -> {
                String value = definition.path("values").path(entry.getKey()).asText();
                switch (entry.getValue().asText()) {
                  case "S":
                    attributes.put(entry.getKey(), AttributeValue.fromS(value));
                    break;
                  case "N":
                    attributes.put(entry.getKey(), AttributeValue.fromN(value));
                    break;
                  default:
                    throw new IllegalArgumentException("Unsupported configuration attribute");
                }
              });
      c.admin.putItem(
          PutItemRequest.builder()
              .tableName(table(c, definition.path("table").asText()))
              .item(attributes)
              .build());
    }
  }

  static Map<String, Map<String, AttributeValue>> stored(DynamoDbTestContext c) {
    Map<String, Map<String, AttributeValue>> result = new LinkedHashMap<>();
    for (String role : List.of("journal", "snapshot", "head"))
      result.put(
          role,
          c.admin
              .getItem(
                  GetItemRequest.builder()
                      .tableName(table(c, role))
                      .key(key(role))
                      .consistentRead(true)
                      .build())
              .item());
    return result;
  }

  static List<FaultRegistry.Fault> register(DynamoDbTestContext c, JsonNode definitions) {
    List<FaultRegistry.Fault> result = new ArrayList<>();
    for (JsonNode fault : definitions) {
      JsonNode details = fault.path("details");
      FaultRegistry.Effect effect;
      switch (fault.path("kind").asText()) {
        case "sdk-response":
          Map<String, List<Map<String, AttributeValue>>> pending = new LinkedHashMap<>();
          for (JsonNode token : details.path("unprocessed_keys")) {
            String role = token.asText().split(":")[0];
            pending.computeIfAbsent(table(c, role), ignored -> new ArrayList<>()).add(key(role));
          }
          Set<String> responseTables = details.has("responses") ? new HashSet<>() : null;
          if (responseTables != null)
            details
                .path("responses")
                .fieldNames()
                .forEachRemaining(role -> responseTables.add(table(c, role)));
          effect = DynamoDbFaultEffects.partialBatchGet(c.admin, pending, responseTables);
          break;
        case "sdk-error":
          if ("TransactionCanceledException".equals(details.path("code").asText())) {
            Map<String, String> codes = new LinkedHashMap<>();
            for (JsonNode reason : details.path("cancellation_reasons"))
              codes.put(reason.path("target").asText(), reason.path("code").asText());
            effect =
                DynamoDbFaultEffects.transactionCanceled(
                    c.targets,
                    codes,
                    null,
                    details.path("message").asText("Injected DynamoDB test failure"),
                    () -> install(c, details.path("install_items")));
          } else
            effect =
                DynamoDbFaultEffects.sdkError(
                    details.path("code").asText(),
                    details.path("message").asText("Injected DynamoDB test failure"));
          break;
        default:
          throw new IllegalArgumentException("Unsupported configuration fault");
      }
      result.add(
          c.faults.register(
              fault.path("operation").intValue(),
              fault.path("phase").asText(),
              fault.at("/repeat/mode").asText().equals("count")
                  ? fault.at("/repeat/count").intValue()
                  : -1,
              fault.path("injection").asText().equals("replace-request")
                  ? FaultRegistry.Injection.REPLACE_REQUEST
                  : FaultRegistry.Injection.REPLACE_RESPONSE,
              effect));
    }
    return result;
  }

  static ArrayNode requestsJson(List<DynamoDbRequestRecorder.Request> requests) {
    ArrayNode result = DynamoDbJson.mapper().createArrayNode();
    for (DynamoDbRequestRecorder.Request r : requests) {
      ObjectNode entry =
          result
              .addObject()
              .put("api", r.api)
              .put("phase", r.phase)
              .put("http_attempts", r.httpAttempts)
              .put("transmissions", r.transmissions);
      entry.set("original", r.original);
      entry.set("marshalled", r.marshalled);
      entry.set("transmitted", r.transmitted);
      entry.set("structure", r.structure);
      entry.set("original_response", r.originalResponse);
      entry.set("effective_response", r.effectiveResponse);
    }
    return result;
  }

  private static void observe(
      DynamoDbTestContext c,
      JsonNode expected,
      List<DynamoDbRequestRecorder.Request> requests,
      List<Long> waits,
      Map<String, Map<String, AttributeValue>> items) {
    assertFalse(requests.isEmpty());
    assertEquals(
        Set.of("journal:__config__:0", "snapshot:__config__:0", "head:__config__"),
        requested(c, requests.get(0).original));
    for (DynamoDbRequestRecorder.Request r : requests) {
      assertTrue(r.api.equals("BatchGetItem") || r.api.equals("TransactWriteItems"));
      assertEquals(1, r.httpAttempts);
      if (r.transmissions > 0) assertEquals(r.marshalled, r.transmitted);
      if (r.api.equals("BatchGetItem")) {
        strong(r.original);
        strong(r.marshalled);
        if (r.transmitted != null) strong(r.transmitted);
      } else assertTransaction(c, r, items);
    }
    int cursor = 0;
    for (JsonNode exp : expected.path("requests")) {
      while (cursor < requests.size()
          && !(requests.get(cursor).api.equals(exp.path("api").asText())
              && requests.get(cursor).phase.equals(exp.path("phase").asText()))) cursor++;
      assertTrue(cursor < requests.size(), "Expected ordered request was not observed");
      DynamoDbRequestRecorder.Request r = requests.get(cursor++);
      JsonNode constraints = exp.path("constraints");
      constraints
          .fields()
          .forEachRemaining(
              field -> {
                switch (field.getKey()) {
                  case "keys":
                    assertEquals(strings(field.getValue()), requested(c, r.original));
                    break;
                  case "consistent_read_all_tables":
                    assertTrue(field.getValue().booleanValue());
                    strong(r.original);
                    break;
                  case "only_unprocessed_keys":
                    assertTrue(field.getValue().booleanValue());
                    assertEquals(
                        Set.of("snapshot:__config__:0", "head:__config__"),
                        requested(c, r.original));
                    break;
                  case "exponential_backoff":
                    assertTrue(field.getValue().booleanValue());
                    assertEquals(List.of(50L), waits);
                    break;
                  case "put_tables":
                    Set<String> roles = new HashSet<>();
                    for (JsonNode action : r.original.path("TransactItems"))
                      roles.add(c.targets.role(action.at("/Put/TableName").asText()));
                    assertEquals(strings(field.getValue()), roles);
                    break;
                  case "condition":
                    assertEquals("aid", field.getValue().path("attribute_not_exists").asText());
                    break;
                  case "same_store_id":
                    assertTrue(field.getValue().booleanValue());
                    break;
                  case "layout_version":
                    assertEquals(1, field.getValue().intValue());
                    break;
                  default:
                    fail("Unconnected configuration constraint: " + field.getKey());
                }
              });
    }
    expected
        .path("request_count")
        .fields()
        .forEachRemaining(
            entry ->
                assertEquals(
                    entry.getValue().longValue(),
                    requests.stream().filter(r -> r.phase.equals(entry.getKey())).count()));
    expected
        .path("minimum_request_count")
        .fields()
        .forEachRemaining(
            entry ->
                assertTrue(
                    requests.stream().filter(r -> r.phase.equals(entry.getKey())).count()
                        >= entry.getValue().longValue()));
    for (JsonNode phase : expected.path("no_requests_in_phases"))
      assertEquals(0, requests.stream().filter(r -> r.phase.equals(phase.asText())).count());
    String boundId = null;
    for (JsonNode item : expected.path("items")) {
      Map<String, AttributeValue> stored = items.get(item.path("table").asText());
      assertItem(item, stored);
      if (item.path("bindings").has("store_id")) {
        assertEquals("generated-store-id", item.at("/bindings/store_id").asText());
        String id = stored.get("store_id").s();
        assertFalse(id.isEmpty());
        if (boundId == null) boundId = id;
        else assertEquals(boundId, id);
      }
    }
  }

  static void assertItem(JsonNode expected, Map<String, AttributeValue> actual) {
    JsonNode json = DynamoDbJson.sdk(actual);
    assertEquals(stringsFromFields(expected.path("attributes")), actual.keySet());
    expected
        .path("attributes")
        .fields()
        .forEachRemaining(
            entry ->
                assertEquals(
                    Set.of(entry.getValue().asText()),
                    stringsFromFields(json.path(entry.getKey()))));
    expected
        .path("values")
        .fields()
        .forEachRemaining(
            entry -> {
              AttributeValue value = actual.get(entry.getKey());
              if (value.n() != null)
                assertEquals(
                    0,
                    new BigDecimal(entry.getValue().asText()).compareTo(new BigDecimal(value.n())));
              else assertEquals(entry.getValue().asText(), value.s());
            });
  }

  private static void assertTransaction(
      DynamoDbTestContext c,
      DynamoDbRequestRecorder.Request request,
      Map<String, Map<String, AttributeValue>> stored) {
    assertEquals(3, request.original.path("TransactItems").size());
    Set<String> roles = new HashSet<>();
    String id = null;
    for (JsonNode action : request.original.path("TransactItems")) {
      JsonNode put = action.path("Put");
      String role = c.targets.role(put.path("TableName").asText());
      assertTrue(roles.add(role));
      JsonNode attributes = put.path("Item");
      Set<String> names = new HashSet<>(key(role).keySet());
      names.addAll(Set.of("store_id", "layout_version"));
      assertEquals(names, stringsFromFields(attributes));
      key(role)
          .forEach((name, value) -> assertEquals(DynamoDbJson.sdk(value), attributes.path(name)));
      assertEquals(Set.of("S"), stringsFromFields(attributes.path("store_id")));
      assertEquals(Set.of("N"), stringsFromFields(attributes.path("layout_version")));
      assertEquals(
          0, BigDecimal.ONE.compareTo(new BigDecimal(attributes.at("/layout_version/N").asText())));
      String next = attributes.at("/store_id/S").asText();
      assertFalse(next.isEmpty());
      if (id == null) id = next;
      else assertEquals(id, next);
      JsonNode condition = DynamoDbRequestStructure.parse(put).at("/condition/all");
      assertEquals(1, condition.size());
      assertEquals("aid", condition.get(0).path("attribute").asText());
      assertEquals("attribute_not_exists", condition.get(0).path("operator").asText());
      if (request.transmissions > 0) assertEquals(attributes, DynamoDbJson.sdk(stored.get(role)));
    }
    assertEquals(Set.of("journal", "snapshot", "head"), roles);
  }

  static Set<String> requested(DynamoDbTestContext c, JsonNode request) {
    Set<String> result = new HashSet<>();
    request
        .path("RequestItems")
        .fields()
        .forEachRemaining(
            entry -> {
              String role = c.targets.role(entry.getKey());
              for (JsonNode k : entry.getValue().path("Keys")) {
                assertEquals(DynamoDbJson.sdk(key(role)), k);
                assertTrue(result.add(role + ":__config__" + (role.equals("head") ? "" : ":0")));
              }
            });
    return result;
  }

  static void strong(JsonNode request) {
    assertTrue(request.path("RequestItems").size() > 0);
    request
        .path("RequestItems")
        .forEach(keys -> assertTrue(keys.path("ConsistentRead").booleanValue()));
  }

  static Set<String> strings(JsonNode array) {
    Set<String> r = new HashSet<>();
    array.forEach(n -> r.add(n.asText()));
    return r;
  }

  static Set<String> stringsFromFields(JsonNode object) {
    Set<String> r = new HashSet<>();
    object.fieldNames().forEachRemaining(r::add);
    return r;
  }

  static void close(DynamoDbTestContext c) {
    Throwable failure =
        c.finish(
                null,
                () -> {
                  for (String name : DynamoDbConfigurationTables.names(c))
                    assertThrows(
                        ResourceNotFoundException.class,
                        () ->
                            c.admin.describeTable(
                                DescribeTableRequest.builder().tableName(name).build()));
                })
            .join();
    assertNull(failure, () -> String.valueOf(failure));
    assertTrue(c.closed());
  }

  public void layout(JsonNode expected, ObjectNode actual) {
    String binding = DynamoDbSnapshotRetentionFixture.unsupportedBindings(expected.path("items"));
    if (binding != null) {
      actual.put("unsupported", binding);
      return;
    }
    Throwable firstFailure = null;
    for (boolean async : List.of(false, true)) {
      ObjectNode sdkPath = actual.putObject(async ? "async" : "sync");
      for (RetentionPolicy policy :
          List.of(RetentionPolicy.none(), RetentionPolicy.delete(2), RetentionPolicy.ttl(1, 60))) {
        String mode = policy.mode().map(m -> m.name().toLowerCase(Locale.ROOT)).orElse("none");
        DynamoDbTestContext c = createContext();
        ObjectNode timing = resources.contexts.get(c);
        timing.put("layout_mode", mode);
        ObjectNode observed = sdkPath.putObject(mode);
        observed.put("sdk_path", async ? "async" : "sync");
        observed.set("tables", DynamoDbJson.sdk(DynamoDbConfigurationTables.names(c)));
        try {
          long preparing = System.nanoTime();
          DynamoDbConfigurationTables.create(c, policy);
          timing.put("table_prepare_ns", System.nanoTime() - preparing);
          FaultRegistry.Operation operation = c.faults.begin(0, false);
          long requesting = System.nanoTime();
          PayloadSerializer<JsonNode> json =
              JsonPayloadSerializer.of(DynamoDbJson.mapper(), JsonNode.class);
          EventStoreConfig<JsonNode, JsonNode> config =
              EventStoreConfig.<JsonNode, JsonNode>builder()
                  .payloadSerializer(json)
                  .snapshotSerializer(json)
                  .build();
          DynamoDbTableConfig tables =
              DynamoDbConfigurationTables.config(c)
                  .retentionPolicy(policy)
                  .clock(Clock.fixed(Instant.ofEpochSecond(4102444800L), ZoneOffset.UTC))
                  .build();
          EventStore<JsonNode, JsonNode> syncStore =
              async ? null : DynamoDbEventStore.create(c.client, tables, config);
          AsyncEventStore<JsonNode, JsonNode> asyncStore =
              async ? DynamoDbEventStore.createAsync(c.async, tables, config).join() : null;
          c.recorder.requestsFinished(operation).join();
          timing.put("request_ns", System.nanoTime() - requesting);
          observed.set("requests", requestsJson(c.recorder.requests()));
          Map<String, Map<String, AttributeValue>> items = stored(c);
          observed.set("configuration_items", DynamoDbJson.sdk(items));
          assertEquals("passed", c.faults.finish(operation).status);
          observed.put("region", c.client.serviceClientConfiguration().region().id());
          assertEquals(DynamoDbTestClients.REGION, c.client.serviceClientConfiguration().region());
          assertEquals(
              c.client.serviceClientConfiguration().region(),
              c.admin.serviceClientConfiguration().region());
          assertEquals(
              c.client.serviceClientConfiguration().region(),
              c.async.serviceClientConfiguration().region());
          // Fixed input is independent of the layout expectation.
          ArrayNode writes = observed.putArray("operations");
          for (long seq = 1; seq <= 3; seq++) {
            FaultRegistry.Operation writing = c.faults.begin((int) seq, true);
            EventEnvelope<JsonNode> event =
                EventEnvelope.<JsonNode>builder()
                    .aggregateId(AggregateId.of("Order", "1"))
                    .seqNr(seq)
                    .occurredAt(Instant.parse("1970-01-01T00:00:00.123000000Z"))
                    .payload(DynamoDbJson.object().put("number", seq))
                    .build();
            SnapshotEnvelope<JsonNode> snapshot =
                SnapshotEnvelope.<JsonNode>builder()
                    .seqNr(seq)
                    .aggregate(DynamoDbJson.object().put("total", seq))
                    .build();
            if (async) asyncStore.persistEventAndSnapshot(event, snapshot).join();
            else syncStore.persistEventAndSnapshot(event, snapshot);
            c.recorder.requestsFinished(writing).join();
            ObjectNode write =
                writes
                    .addObject()
                    .put("operation", seq)
                    .put("pending", c.faults.pending(writing))
                    .put("request_terminal", c.recorder.requestsFinished(writing).isDone());
            List<DynamoDbRequestRecorder.Request> sent = new ArrayList<>();
            for (DynamoDbRequestRecorder.Request request : c.recorder.requests())
              if (request.operation == seq) sent.add(request);
            write.set("requests", requestsJson(sent));
            assertEquals(0, c.faults.pending(writing));
            FaultRegistry.Result finished = c.faults.finish(writing);
            write.put("fault_result", finished.status);
            assertEquals("passed", finished.status);
          }
          for (JsonNode table : expected.path("tables")) {
            String role = table.path("name").asText();
            TableDescription description =
                c.admin
                    .describeTable(DescribeTableRequest.builder().tableName(table(c, role)).build())
                    .table();
            TimeToLiveDescription ttl =
                c.admin
                    .describeTimeToLive(
                        DescribeTimeToLiveRequest.builder().tableName(table(c, role)).build())
                    .timeToLiveDescription();
            ObjectNode observation = observed.putObject(role);
            observation.set("describe_table", DynamoDbJson.sdk(description));
            observation.set("describe_ttl", DynamoDbJson.sdk(ttl));
            assertKeys(table, description.keySchema(), description.attributeDefinitions());
            assertEquals(table.path("gsi").size(), description.globalSecondaryIndexes().size());
            for (JsonNode gsi : table.path("gsi")) {
              GlobalSecondaryIndexDescription index = description.globalSecondaryIndexes().get(0);
              assertEquals("configured-history-index", gsi.path("name_binding").asText());
              assertEquals(c.historyIndex, index.indexName());
              assertKeys(gsi, index.keySchema(), description.attributeDefinitions());
              assertEquals(
                  gsi.path("projection").asText(), index.projection().projectionTypeAsString());
              assertTrue(index.projection().nonKeyAttributes().isEmpty());
            }
            boolean enabled =
                description.streamSpecification() != null
                    && Boolean.TRUE.equals(description.streamSpecification().streamEnabled());
            assertEquals(table.at("/streams/enabled").booleanValue(), enabled);
            if (enabled)
              assertEquals(
                  table.at("/streams/view_type").asText(),
                  description.streamSpecification().streamViewTypeAsString());
            else assertTrue(table.at("/streams/view_type").isNull());
            assertTrue(
                Set.of("never", "retention-mode-ttl")
                    .contains(table.at("/ttl/enabled_when").asText()));
            if (table.at("/ttl/enabled_when").asText().equals("never"))
              assertTrue(table.at("/ttl/attribute").isNull());
            boolean ttlEnabled =
                policy.mode().orElse(null) == RetentionMode.TTL
                    && table.at("/ttl/enabled_when").asText().equals("retention-mode-ttl");
            assertEquals(
                ttlEnabled ? TimeToLiveStatus.ENABLED : TimeToLiveStatus.DISABLED,
                ttl.timeToLiveStatus());
            if (ttlEnabled) assertEquals(table.at("/ttl/attribute").asText(), ttl.attributeName());
            else assertNull(ttl.attributeName());
          }
          String generatedId = items.get("journal").get("store_id").s();
          assertFalse(generatedId.isEmpty());
          ArrayNode checked = observed.putArray("items");
          String declaredStoreId = null;
          for (JsonNode item : expected.path("items")) {
            ObjectNode entry = checked.addObject().put("table", item.path("table").asText());
            boolean history = item.at("/values/skey").asText().equals("3");
            boolean marked = item.path("attributes").has("ttl");
            if ((history && policy.keepCount().isEmpty())
                || (marked && policy.mode().orElse(null) != RetentionMode.TTL)) {
              entry
                  .put("status", "not-applicable")
                  .put("reason", marked ? "TTL方式の印付き履歴のため" : "履歴を保持しない設定のため");
              continue;
            }
            String role = item.path("table").asText();
            Map<String, AttributeValue> key = new LinkedHashMap<>();
            key.put("aid", AttributeValue.fromS(item.at("/values/aid").asText()));
            if (!role.equals("head")) {
              String sort = role.equals("journal") ? "seq_nr" : "skey";
              key.put(sort, AttributeValue.fromN(item.path("values").path(sort).asText()));
            }
            Map<String, AttributeValue> saved =
                c.admin
                    .getItem(
                        GetItemRequest.builder()
                            .tableName(table(c, role))
                            .key(key)
                            .consistentRead(true)
                            .build())
                    .item();
            entry.set("item", DynamoDbJson.sdk(saved));
            ObjectNode bound = item.deepCopy();
            if (item.at("/values/aid").asText().equals("__config__")) {
              String nextId = item.at("/values/store_id").asText();
              assertFalse(nextId.isEmpty());
              if (declaredStoreId == null) declaredStoreId = nextId;
              else assertEquals(declaredStoreId, nextId);
              ((ObjectNode) bound.path("values")).put("store_id", generatedId);
              entry.put("generated_store_id", generatedId);
            }
            observed.put("comparison_position", "/items/" + (checked.size() - 1));
            DynamoDbSnapshotRetentionFixture.compareAttributes(
                DynamoDbSnapshotRetentionFixture.attributes(bound), saved);
            entry.put("status", "passed");
          }
          observed.remove("comparison_position");
        } catch (RuntimeException | AssertionError failure) {
          recordComparisonFailure(observed, failure);
          if (firstFailure == null) firstFailure = failure;
          else firstFailure.addSuppressed(failure);
        } finally {
          long closing = System.nanoTime();
          try {
            close(c);
          } finally {
            timing.put("context_close_ns", System.nanoTime() - closing);
            observed.put("resources_closed", c.closed());
          }
        }
      }
    }
    if (firstFailure instanceof AssertionError) throw (AssertionError) firstFailure;
    if (firstFailure != null) throw (RuntimeException) firstFailure;
    actual.put("verified", "両SDKの3表・索引・Streams・モード別TTL・全8種類の項目");
  }

  private static void assertKeys(
      JsonNode expected, List<KeySchemaElement> keys, List<AttributeDefinition> attributes) {
    assertEquals(expected.hasNonNull("sort_key") ? 2 : 1, keys.size());
    assertKey(expected.path("partition_key"), KeyType.HASH, keys, attributes);
    if (expected.hasNonNull("sort_key"))
      assertKey(expected.path("sort_key"), KeyType.RANGE, keys, attributes);
  }

  private static void assertKey(
      JsonNode expected,
      KeyType type,
      List<KeySchemaElement> keys,
      List<AttributeDefinition> attributes) {
    KeySchemaElement key = keys.stream().filter(k -> k.keyType() == type).findFirst().orElseThrow();
    assertEquals(expected.path("name").asText(), key.attributeName());
    AttributeDefinition attribute =
        attributes.stream()
            .filter(a -> a.attributeName().equals(key.attributeName()))
            .findFirst()
            .orElseThrow();
    assertEquals(expected.path("type").asText(), attribute.attributeTypeAsString());
  }
}
