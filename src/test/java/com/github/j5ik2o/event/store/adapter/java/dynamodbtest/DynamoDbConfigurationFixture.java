package com.github.j5ik2o.event.store.adapter.java.dynamodbtest;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.j5ik2o.event.store.adapter.java.core.*;
import com.github.j5ik2o.event.store.adapter.java.dynamodb.*;
import java.math.BigDecimal;
import java.net.URI;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import software.amazon.awssdk.services.dynamodb.model.*;

/** Public test bridge; all resource and fault machinery remains in the test package. */
public final class DynamoDbConfigurationFixture implements BeforeAllCallback {
  private final DynamoDbLocalExtension local = new DynamoDbLocalExtension();

  @Override
  public void beforeAll(ExtensionContext context) {
    local.beforeAll(context);
  }

  URI endpoint() {
    return local.endpoint();
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
    configuration(scenario, false, result.putObject("sync"));
    configuration(scenario, true, result.putObject("async"));
    if (scenario.path("id").asText().equals("dynamodb-config-new")
        && result.at("/sync/result").asText().equals("success")
        && result.at("/async/result").asText().equals("success"))
      assertNotEquals(
          result.at("/sync/items/journal/store_id/S"),
          result.at("/async/items/journal/store_id/S"));
  }

  private void configuration(JsonNode scenario, boolean async, ObjectNode actual) {
    DynamoDbTestContext c = new DynamoDbTestContext(endpoint());
    try {
      RetentionPolicy policy = retention(scenario.path("store"));
      DynamoDbConfigurationTables.create(c, policy);
      install(c, scenario.path("seed").path("items"));
      Map<String, Map<String, AttributeValue>> before = stored(c);
      List<FaultRegistry.Fault> faults = register(c, scenario.path("faults"));
      DynamoDbTableConfig.Builder builder =
          DynamoDbConfigurationTables.config(c).retentionPolicy(policy);
      if (scenario.path("store").has("retry_limit"))
        builder.configurationReadRetryLimit(scenario.at("/store/retry_limit").intValue());
      DynamoDbTableConfig tables = builder.build();
      List<Long> waits = new ArrayList<>();
      FaultRegistry.Operation operation = c.faults.begin(0, false);
      Throwable failure = null;
      Object store = null;
      try {
        if (async)
          store =
              DynamoDbTestFactory.createAsync(
                      c.async,
                      tables,
                      storeConfig(),
                      millis -> {
                        waits.add(millis);
                        return CompletableFuture.completedFuture(null);
                      })
                  .join();
        else store = DynamoDbTestFactory.create(c.client, tables, storeConfig(), waits::add);
      } catch (RuntimeException error) {
        failure = EventStoreExceptions.unwrap(error);
      }
      c.recorder.requestsFinished(operation).join();
      actual.put("result", failure == null ? "success" : category(failure));
      actual.set("waits_ms", DynamoDbJson.mapper().valueToTree(waits));
      List<DynamoDbRequestRecorder.Request> requests = c.recorder.requests();
      actual.set("requests", requestsJson(requests));
      Map<String, Map<String, AttributeValue>> items = stored(c);
      actual.set("items", DynamoDbJson.sdk(items));
      int pending = c.faults.pending(operation);
      actual.put("pending", pending);
      assertEquals(0, pending);
      ArrayNode applications = actual.putArray("faults");
      for (FaultRegistry.Fault fault : faults) {
        int reservations = c.faults.reservations(fault);
        int count = c.faults.applications(fault);
        applications
            .addObject()
            .put("phase", fault.phase)
            .put("applications", count)
            .put("reservations", reservations);
        assertEquals(0, reservations);
        assertTrue(count > 0);
        if (fault.count != -1) assertEquals(fault.count, count);
      }
      assertEquals("passed", c.faults.finish(operation).status);
      if (failure == null) assertNotNull(store);
      JsonNode initialization = scenario.path("initialization");
      assertOutcome(initialization.path("expect"), failure);
      observe(c, initialization.path("observe"), requests, waits, items);
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
      try {
        close(c);
      } finally {
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
      for (JsonNode word : expected.at("/error/message/must_contain"))
        assertTrue(failure.getMessage().contains(word.asText()));
      for (JsonNode word : expected.at("/error/message/must_not_contain"))
        assertFalse(failure.getMessage().contains(word.asText()));
    } else {
      assertEquals("success", expected.path("result").asText());
      assertNull(failure);
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
          effect = DynamoDbFaultEffects.partialBatchGet(c.admin, pending);
          break;
        case "sdk-error":
          if ("TransactionCanceledException".equals(details.path("code").asText())) {
            Map<String, String> codes = new LinkedHashMap<>();
            for (JsonNode reason : details.path("cancellation_reasons"))
              codes.put(reason.path("target").asText(), reason.path("code").asText());
            effect =
                DynamoDbFaultEffects.transactionCanceled(
                    c.targets, codes, null, () -> install(c, details.path("install_items")));
          } else effect = DynamoDbFaultEffects.sdkError(details.path("code").asText());
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
    for (RetentionPolicy policy :
        List.of(RetentionPolicy.none(), RetentionPolicy.delete(2), RetentionPolicy.ttl(2, 60))) {
      String mode = policy.mode().map(m -> m.name().toLowerCase(Locale.ROOT)).orElse("none");
      DynamoDbTestContext c = new DynamoDbTestContext(endpoint());
      ObjectNode observed = actual.putObject(mode);
      try {
        DynamoDbConfigurationTables.create(c, policy);
        FaultRegistry.Operation operation = c.faults.begin(0, false);
        DynamoDbEventStore.create(
            c.client,
            DynamoDbConfigurationTables.config(c).retentionPolicy(policy).build(),
            storeConfig());
        c.recorder.requestsFinished(operation).join();
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
        int checked = 0;
        for (JsonNode item : expected.path("items")) {
          if (!"__config__".equals(item.at("/values/aid").asText())) continue;
          ObjectNode bound = item.deepCopy();
          // The fixture name store-a is bound to the identifier generated by the product.
          ((ObjectNode) bound.path("values")).put("store_id", generatedId);
          assertItem(bound, items.get(item.path("table").asText()));
          checked++;
        }
        assertEquals(3, checked);
      } finally {
        try {
          close(c);
        } finally {
          observed.put("resources_closed", c.closed());
        }
      }
    }
    actual.put("verified", "3テーブルのキー・履歴索引・Streams・モード別TTL・設定項目");
    actual.put("unverified", "journal・current/history/marked snapshot・headのデータ項目形状、書込み・読取り・保持本体");
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
