package com.github.j5ik2o.event.store.adapter.java.dynamodbtest;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.j5ik2o.event.store.adapter.java.core.*;
import com.github.j5ik2o.event.store.adapter.java.dynamodb.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.dynamodb.model.*;

/** DELETE scenes reuse the accepted Local, SDK fault effects and resource ownership. */
public final class DynamoDbSnapshotRetentionFixture {
  private final DynamoDbConfigurationFixture configuration;
  private static final PayloadSerializer<JsonNode> JSON =
      JsonPayloadSerializer.of(DynamoDbJson.mapper(), JsonNode.class);

  public DynamoDbSnapshotRetentionFixture(DynamoDbConfigurationFixture configuration) {
    this.configuration = configuration;
  }

  public static String unsupported(JsonNode scenario) {
    if (scenario.at("/store/retention_mode").asText().equals("ttl")) return "TTL方式は未接続";
    for (JsonNode capability : scenario.path("requires"))
      if (capability.asText().equals("ttl")) return "TTL方式は未接続";
    if (scenario.has("initialization")) {
      if (!scenario.path("steps").isEmpty()
          || scenario.has("faults")
          || scenario.has("seed")
          || !scenario.at("/initialization/expect/error/category").asText().equals("configuration")
          || scenario.path("initialization").has("observe")) return "未接続の初期化期待";
    }
    for (JsonNode step : scenario.path("steps")) {
      if (!Set.of(
              "persistEvent",
              "persistEventAndSnapshot",
              "getLatestSnapshotById",
              "getEventsByIdSinceSeqNr")
          .contains(step.path("op").asText())) return "未接続の操作: " + step.path("op").asText();
      for (String field : fields(step.path("observe"))) {
        if (!Set.of(
                "history",
                "notifications",
                "requests",
                "request_count",
                "minimum_request_count",
                "no_requests_in_phases")
            .contains(field)) return "未接続の観測: " + field;
      }
      if (step.path("observe").has("history")
          && !fields(step.at("/observe/history")).equals(Set.of("active", "marked", "absent")))
        return "未接続の履歴観測";
      for (JsonNode request : step.at("/observe/requests")) {
        String api = request.path("api").asText(), phase = request.path("phase").asText();
        if (!(api.equals("Query") && phase.equals("retention-query"))
            && !(api.equals("BatchWriteItem") && phase.equals("retention-delete")))
          return "未接続の要求観測";
        for (String constraint : fields(request.path("constraints"))) {
          if (!(api.equals("Query")
                  ? Set.of(
                      "index",
                      "scan_index_forward",
                      "follow_last_evaluated_key",
                      "projection",
                      "include_just_written_history")
                  : Set.of("retry_unprocessed_items", "initial_batch_sizes"))
              .contains(constraint)) return "未接続の要求制約: " + constraint;
        }
      }
    }
    for (JsonNode fault : scenario.path("faults")) {
      String phase = fault.path("phase").asText(), kind = fault.path("kind").asText();
      if (!Set.of("retention-query", "retention-delete").contains(phase))
        return "未接続の障害段階: " + phase;
      Set<String> details = fields(fault.path("details"));
      if (kind.equals("sdk-response")) {
        if (phase.equals("retention-query")
            && details.contains("history_pages")
            && Set.of("history_pages", "omit_just_written_history").containsAll(details)) continue;
        if (phase.equals("retention-delete")
            && details.equals(Set.of("unprocessed_first_n"))
            && fault.path("injection").asText().equals("replace-request")) continue;
      }
      if (kind.equals("storage-error")
          && Set.of("message", "scope").containsAll(details)
          && (!details.contains("scope")
              || fault.at("/details/scope").asText().equals("final-retention-failure"))
          && fault.path("injection").asText().equals("replace-request")) continue;
      if (kind.equals("sdk-error")
          && Set.of("code", "message").containsAll(details)
          && Set.of("InternalServerError", "ProvisionedThroughputExceededException")
              .contains(fault.at("/details/code").asText())
          && fault.path("injection").asText().equals("replace-request")) continue;
      return "未接続の障害: " + kind + "/" + phase;
    }
    for (JsonNode item : scenario.at("/seed/items")) {
      if (!Set.of("journal", "head", "snapshot").contains(item.path("table").asText()))
        return "未接続のseedテーブル";
      for (JsonNode type : item.path("attributes"))
        if (!Set.of("S", "N", "B", "L").contains(type.asText())) return "未接続のseed属性型";
    }
    return null;
  }

  public void execute(JsonNode scenario, ObjectNode actual) {
    String unsupported = unsupported(scenario);
    if (unsupported != null) {
      actual.put("unsupported", unsupported);
      return;
    }
    Throwable firstFailure = null;
    for (boolean async : List.of(false, true)) {
      ObjectNode path = actual.putObject(async ? "async" : "sync");
      int operation = 0;
      try {
        if (scenario.has("initialization")) initialize(scenario, async, path);
        else
          try (Scene scene = scene(scenario.path("id").asText(), async, scenario, path)) {
            register(scene, scenario);
            for (JsonNode step : scenario.path("steps")) {
              operation = scene.nextOperation;
              Throwable failure = null;
              ObjectNode outcome;
              try {
                outcome =
                    scene.operate(
                        step.path("op").asText().startsWith("persist"),
                        () -> perform(scene, scenario, step));
              } catch (RuntimeException error) {
                failure = EventStoreExceptions.unwrap(error);
                outcome = DynamoDbJson.object();
                ObjectNode actualError =
                    outcome
                        .putObject("error")
                        .put(
                            "category",
                            DynamoDbConfigurationFixture.category(failure).replace('_', '-'));
                if (failure instanceof ContractViolationException)
                  actualError.put("rule", ((ContractViolationException) failure).rule());
              }
              scene.last().set("outcome", outcome);
              DynamoDbSnapshotReadFixture.compare(scenario, step.path("expect"), outcome, failure);
              observe(scene, step, scenario);
            }
          }
      } catch (Exception | AssertionError failure) {
        path.put("failed_operation", operation);
        if (firstFailure == null) {
          actual.put("failed_operation", operation);
          firstFailure = failure;
        } else firstFailure.addSuppressed(failure);
      }
    }
    if (firstFailure instanceof AssertionError) throw (AssertionError) firstFailure;
    if (firstFailure != null)
      throw new IllegalStateException("Retention scene failed", firstFailure);
  }

  private void initialize(JsonNode scenario, boolean async, ObjectNode actual) throws Exception {
    DynamoDbTestContext c = configuration.createContext();
    try {
      DynamoDbConfigurationTables.create(c, RetentionPolicy.none());
      FaultRegistry.Operation operation = c.faults.begin(0, false);
      Throwable failure = null;
      try {
        DynamoDbTableConfig tables =
            DynamoDbConfigurationTables.config(c)
                .retentionPolicy(DynamoDbConfigurationFixture.retention(scenario.path("store")))
                .build();
        EventStoreConfig<JsonNode, JsonNode> config =
            EventStoreConfig.<JsonNode, JsonNode>builder()
                .payloadSerializer(JSON)
                .snapshotSerializer(JSON)
                .build();
        if (async) DynamoDbEventStore.createAsync(c.async, tables, config).join();
        else DynamoDbEventStore.create(c.client, tables, config);
      } catch (RuntimeException error) {
        failure = EventStoreExceptions.unwrap(error);
      }
      c.recorder.requestsFinished(operation).join();
      actual.put(
          "result", failure == null ? "success" : DynamoDbConfigurationFixture.category(failure));
      actual.set("requests", DynamoDbConfigurationFixture.requestsJson(c.recorder.requests()));
      actual
          .put("pending", c.faults.pending(operation))
          .put("request_terminal", c.recorder.requestsFinished(operation).isDone());
      assertEquals(0, c.faults.pending(operation));
      assertEquals("passed", c.faults.finish(operation).status);
      ObjectNode outcome = actual.putObject("outcome");
      if (failure != null) {
        ObjectNode error =
            outcome
                .putObject("error")
                .put("category", DynamoDbConfigurationFixture.category(failure).replace('_', '-'));
        if (failure instanceof ContractViolationException)
          error.put("rule", ((ContractViolationException) failure).rule());
      } else outcome.put("result", "success");
      DynamoDbSnapshotReadFixture.compare(
          scenario, scenario.at("/initialization/expect"), outcome, failure);
    } finally {
      DynamoDbConfigurationFixture.close(c);
      actual.put("resources_closed", c.closed());
    }
  }

  private static ObjectNode perform(Scene scene, JsonNode scenario, JsonNode step) {
    JsonNode args = step.path("arguments");
    switch (step.path("op").asText()) {
      case "persistEvent":
        scene.persist(
            DynamoDbSnapshotReadFixture.event(
                scenario.at("/fixtures/events").path(args.path("event").asText())),
            null);
        return DynamoDbJson.object().put("result", "success");
      case "persistEventAndSnapshot":
        scene.persist(
            DynamoDbSnapshotReadFixture.event(
                scenario.at("/fixtures/events").path(args.path("event").asText())),
            DynamoDbSnapshotReadFixture.snapshot(
                scenario.at("/fixtures/snapshots").path(args.path("snapshot").asText())));
        return DynamoDbJson.object().put("result", "success");
      case "getLatestSnapshotById":
        AggregateId id = DynamoDbSnapshotReadFixture.aid(args.path("aggregate_id"));
        return DynamoDbSnapshotReadFixture.latest(
            scene.asynchronous
                ? scene.asyncStore.getLatestSnapshotById(id).join()
                : scene.syncStore.getLatestSnapshotById(id));
      case "getEventsByIdSinceSeqNr":
        AggregateId aid = DynamoDbSnapshotReadFixture.aid(args.path("aggregate_id"));
        long seq = args.path("seq_nr").bigIntegerValue().longValueExact();
        ObjectNode result = DynamoDbJson.object().put("result", "events");
        result.set(
            "events",
            DynamoDbEventReadFixture.envelopes(
                scene.asynchronous
                    ? scene.asyncStore.getEventsByIdSinceSeqNr(aid, seq).join()
                    : scene.syncStore.getEventsByIdSinceSeqNr(aid, seq)));
        return result;
      default:
        throw new IllegalArgumentException("Unsupported operation");
    }
  }

  private static AggregateId stepAid(JsonNode scenario, JsonNode step) {
    return DynamoDbSnapshotReadFixture.aid(
        step.path("arguments").has("event")
            ? scenario
                .at("/fixtures/events")
                .path(step.at("/arguments/event").asText())
                .path("aggregate_id")
            : step.at("/arguments/aggregate_id"));
  }

  private static void register(Scene scene, JsonNode scenario) {
    for (JsonNode fault : scenario.path("faults")) {
      int operation = fault.path("operation").intValue();
      JsonNode details = fault.path("details");
      FaultRegistry.Effect effect;
      if (fault.path("kind").asText().equals("sdk-response")) {
        if (fault.path("phase").asText().equals("retention-query")) {
          JsonNode step = scenario.path("steps").get(operation - 1);
          long committed =
              scenario
                  .at("/fixtures/events")
                  .path(step.at("/arguments/event").asText())
                  .path("seq_nr")
                  .longValue();
          List<List<Long>> pages = new ArrayList<>();
          for (JsonNode page : details.path("history_pages")) {
            List<Long> numbers = new ArrayList<>();
            for (JsonNode number : page) numbers.add(number.bigIntegerValue().longValueExact());
            pages.add(numbers);
          }
          effect =
              DynamoDbFaultEffects.historyPages(
                  scene.c.admin,
                  scene.c.snapshot,
                  stepAid(scenario, step).asString(),
                  pages,
                  committed,
                  details.path("omit_just_written_history").asBoolean());
        } else
          effect =
              DynamoDbFaultEffects.unprocessedFirst(details.path("unprocessed_first_n").intValue());
      } else
        effect =
            DynamoDbFaultEffects.sdkError(
                fault.path("kind").asText().equals("sdk-error")
                    ? details.path("code").asText()
                    : "InternalServerError");
      scene.register(
          operation,
          fault.path("phase").asText(),
          fault.at("/repeat/mode").asText().equals("count")
              ? fault.at("/repeat/count").intValue()
              : -1,
          fault.path("injection").asText().equals("replace-request")
              ? FaultRegistry.Injection.REPLACE_REQUEST
              : FaultRegistry.Injection.REPLACE_RESPONSE,
          effect);
    }
  }

  private static void observe(Scene scene, JsonNode step, JsonNode scenario) {
    JsonNode expected = step.path("observe");
    List<DynamoDbRequestRecorder.Request> requests = scene.lastRequests();
    AggregateId id = stepAid(scenario, step);
    if (expected.has("history")) {
      ObjectNode history = scene.history(id);
      scene.last().set("history", history);
      compareJson(expected.at("/history/active"), history.path("active"));
      compareJson(expected.at("/history/marked"), history.path("marked"));
      ArrayNode absenceReads = scene.last().putArray("absence_reads");
      for (JsonNode seq : expected.at("/history/absent")) {
        Map<String, AttributeValue> item = scene.stored("snapshot", id, seq.longValue());
        absenceReads.addObject().put("skey", seq.longValue()).set("item", DynamoDbJson.sdk(item));
        assertTrue(item.isEmpty());
      }
    }
    if (expected.has("notifications")) {
      ArrayNode notifications = DynamoDbJson.mapper().createArrayNode();
      scene.failures.forEach(failure -> notifications.add("retention-failure"));
      assertEquals(expected.path("notifications"), notifications);
    }
    int index = 0;
    for (JsonNode exp : expected.path("requests")) {
      while (index < requests.size()
          && !(requests.get(index).api.equals(exp.path("api").asText())
              && requests.get(index).phase.equals(exp.path("phase").asText()))) index++;
      assertTrue(index < requests.size(), "Expected ordered request was not observed");
      DynamoDbRequestRecorder.Request request = requests.get(index++);
      for (String name : fields(exp.path("constraints"))) {
        JsonNode value = exp.path("constraints").get(name);
        switch (name) {
          case "index":
            assertEquals("configured-history-index", value.asText());
            assertEquals(scene.c.historyIndex, request.original.path("IndexName").asText());
            break;
          case "scan_index_forward":
            assertEquals(
                value.booleanValue(), request.original.path("ScanIndexForward").booleanValue());
            break;
          case "projection":
            assertEquals(
                value.asText(),
                scene
                    .c
                    .admin
                    .describeTable(
                        DescribeTableRequest.builder().tableName(scene.c.snapshot).build())
                    .table()
                    .globalSecondaryIndexes()
                    .get(0)
                    .projection()
                    .projectionTypeAsString());
            break;
          case "follow_last_evaluated_key":
            assertTrue(value.booleanValue());
            List<DynamoDbRequestRecorder.Request> queries =
                requests.stream()
                    .filter(r -> r.phase.equals("retention-query"))
                    .collect(Collectors.toList());
            for (int i = 1; i < queries.size(); i++)
              assertEquals(
                  queries.get(i - 1).effectiveResponse.path("LastEvaluatedKey"),
                  queries.get(i).original.path("ExclusiveStartKey"));
            assertFalse(queries.get(queries.size() - 1).effectiveResponse.has("LastEvaluatedKey"));
            break;
          case "include_just_written_history":
            assertTrue(value.booleanValue());
            long committed =
                scenario
                    .at("/fixtures/events")
                    .path(step.at("/arguments/event").asText())
                    .path("seq_nr")
                    .longValue();
            assertFalse(scene.stored("snapshot", id, committed).isEmpty());
            boolean active = false;
            for (JsonNode number : scene.history(id).path("active"))
              if (number.longValue() == committed) active = true;
            assertTrue(active);
            break;
          case "retry_unprocessed_items":
            assertTrue(value.booleanValue());
            scene.initialBatchSizes();
            break;
          case "initial_batch_sizes":
            compareJson(value, DynamoDbJson.sdk(scene.initialBatchSizes()));
            break;
          default:
            fail("Unconnected constraint");
        }
      }
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
  }

  private static Set<String> fields(JsonNode node) {
    return DynamoDbConfigurationFixture.stringsFromFields(node);
  }

  private static void compareJson(JsonNode expected, JsonNode actual) {
    assertEquals(
        DynamoDbJson.read(DynamoDbJson.bytes(expected)),
        DynamoDbJson.read(DynamoDbJson.bytes(actual)));
  }

  Scene scene(String name, boolean async, RetentionPolicy policy) {
    ObjectNode settings = DynamoDbJson.object();
    ObjectNode store = settings.putObject("store");
    if (policy.keepCount().isPresent()) store.put("retention_count", policy.keepCount().getAsInt());
    store.put("retention_mode", "delete");
    return scene(name, async, settings, DynamoDbJson.object());
  }

  private Scene scene(String name, boolean async, JsonNode settings, ObjectNode actual) {
    return new Scene(name, async, settings, actual);
  }

  final class Scene implements AutoCloseable {
    final DynamoDbTestContext c = configuration.createContext();
    final String name;
    final boolean asynchronous;
    final ObjectNode observation;
    final ArrayNode operations;
    final List<Long> waits = new ArrayList<>();
    final List<RetentionFailure> failures = new ArrayList<>();
    final List<String> notificationThreads = new ArrayList<>();
    final Map<Integer, List<FaultRegistry.Fault>> registered = new LinkedHashMap<>();
    final EventStore<JsonNode, JsonNode> syncStore;
    final AsyncEventStore<JsonNode, JsonNode> asyncStore;
    RetentionFailureListener listener = failure -> {};
    int nextOperation = 1;

    Scene(String name, boolean async, JsonNode settings, ObjectNode actual) {
      this.name = name;
      asynchronous = async;
      observation = actual;
      operations = actual.putArray("operations");
      actual.put("case", name).put("sdk_path", async ? "async" : "sync");
      actual.set("tables", DynamoDbJson.sdk(DynamoDbConfigurationTables.names(c)));
      try {
        RetentionPolicy policy = DynamoDbConfigurationFixture.retention(settings.path("store"));
        DynamoDbConfigurationTables.create(c, policy);
        install(settings.at("/seed/items"));
        EventStoreConfig<JsonNode, JsonNode> config =
            EventStoreConfig.<JsonNode, JsonNode>builder()
                .payloadSerializer(JSON)
                .snapshotSerializer(JSON)
                .retentionFailureListener(
                    failure -> {
                      failures.add(failure);
                      notificationThreads.add(Thread.currentThread().getName());
                      listener.onRetentionFailure(failure);
                    })
                .build();
        DynamoDbTableConfig tables =
            DynamoDbConfigurationTables.config(c).retentionPolicy(policy).build();
        FaultRegistry.Operation operation = c.faults.begin(0, false);
        if (async) {
          asyncStore =
              DynamoDbTestFactory.createAsync(
                      c.async,
                      tables,
                      config,
                      delay -> {
                        waits.add(delay);
                        return CompletableFuture.completedFuture(null);
                      })
                  .join();
          syncStore = null;
        } else {
          syncStore = DynamoDbTestFactory.create(c.client, tables, config, waits::add);
          asyncStore = null;
        }
        finish(operation, null);
        actual.set("configuration_items", DynamoDbJson.sdk(DynamoDbConfigurationFixture.stored(c)));
      } catch (Throwable failure) {
        c.close();
        throw failure;
      }
    }

    void install(JsonNode definitions) {
      ArrayNode saved = observation.putArray("seed_items");
      for (JsonNode definition : definitions) {
        Map<String, AttributeValue> item = attributes(definition);
        String role = definition.path("table").asText();
        c.admin.putItem(
            PutItemRequest.builder()
                .tableName(DynamoDbConfigurationFixture.table(c, role))
                .item(item)
                .build());
        Map<String, AttributeValue> key = new LinkedHashMap<>();
        key.put("aid", item.get("aid"));
        if (!role.equals("head"))
          key.put(
              role.equals("snapshot") ? "skey" : "seq_nr",
              item.get(role.equals("snapshot") ? "skey" : "seq_nr"));
        Map<String, AttributeValue> stored =
            c.admin
                .getItem(
                    GetItemRequest.builder()
                        .tableName(DynamoDbConfigurationFixture.table(c, role))
                        .consistentRead(true)
                        .key(key)
                        .build())
                .item();
        assertEquals(item, stored);
        saved.add(DynamoDbJson.sdk(stored));
      }
    }

    private Map<String, AttributeValue> attributes(JsonNode definition) {
      JsonNode types = definition.path("attributes"),
          values = definition.path("values"),
          binary = definition.path("binary_json");
      Map<String, AttributeValue> item = new LinkedHashMap<>();
      types
          .fields()
          .forEachRemaining(
              entry -> {
                String attr = entry.getKey();
                switch (entry.getValue().asText()) {
                  case "S":
                    item.put(attr, AttributeValue.fromS(values.path(attr).asText()));
                    break;
                  case "N":
                    item.put(attr, AttributeValue.fromN(values.path(attr).asText()));
                    break;
                  case "B":
                    item.put(
                        attr,
                        AttributeValue.fromB(
                            SdkBytes.fromByteArray(JSON.serialize(binary.get(attr)))));
                    break;
                  case "L":
                    List<AttributeValue> list = new ArrayList<>();
                    int index = 0;
                    for (JsonNode member : values.path(attr)) {
                      Map<String, AttributeValue> nested = new LinkedHashMap<>();
                      String memberPath = attr + "[" + index++ + "]";
                      definition
                          .path("nested_attributes")
                          .path(memberPath)
                          .fields()
                          .forEachRemaining(
                              field -> {
                                switch (field.getValue().asText()) {
                                  case "S":
                                    nested.put(
                                        field.getKey(),
                                        AttributeValue.fromS(member.path(field.getKey()).asText()));
                                    break;
                                  case "N":
                                    nested.put(
                                        field.getKey(),
                                        AttributeValue.fromN(member.path(field.getKey()).asText()));
                                    break;
                                  case "B":
                                    nested.put(
                                        field.getKey(),
                                        AttributeValue.fromB(
                                            SdkBytes.fromByteArray(
                                                JSON.serialize(
                                                    binary.get(
                                                        memberPath + "." + field.getKey())))));
                                    break;
                                  default:
                                    throw new IllegalArgumentException(
                                        "Unsupported nested seed type");
                                }
                              });
                      list.add(AttributeValue.fromM(nested));
                    }
                    item.put(attr, AttributeValue.fromL(list));
                    break;
                  default:
                    throw new IllegalArgumentException("Unsupported seed type");
                }
              });
      return item;
    }

    void register(
        int operation,
        String phase,
        int count,
        FaultRegistry.Injection injection,
        FaultRegistry.Effect effect) {
      registered
          .computeIfAbsent(operation, ignored -> new ArrayList<>())
          .add(c.faults.register(operation, phase, count, injection, effect));
    }

    void register(
        String phase, int count, FaultRegistry.Injection injection, FaultRegistry.Effect effect) {
      register(nextOperation, phase, count, injection, effect);
    }

    void persist(EventEnvelope<JsonNode> event, SnapshotEnvelope<JsonNode> snapshot) {
      if (asynchronous)
        (snapshot == null
                ? asyncStore.persistEvent(event)
                : asyncStore.persistEventAndSnapshot(event, snapshot))
            .join();
      else if (snapshot == null) syncStore.persistEvent(event);
      else syncStore.persistEventAndSnapshot(event, snapshot);
    }

    void write(EventEnvelope<JsonNode> event, SnapshotEnvelope<JsonNode> snapshot) {
      operate(
          true,
          () -> {
            persist(event, snapshot);
            return null;
          });
    }

    <T> T operate(boolean writing, Supplier<T> action) {
      FaultRegistry.Operation operation = c.faults.begin(nextOperation++, writing);
      waits.clear();
      failures.clear();
      notificationThreads.clear();
      Throwable failure = null;
      try {
        return action.get();
      } catch (RuntimeException | AssertionError error) {
        failure = EventStoreExceptions.unwrap(error);
        throw error;
      } finally {
        finish(operation, failure);
      }
    }

    void finish(FaultRegistry.Operation operation, Throwable failure) {
      c.recorder.requestsFinished(operation).join();
      ObjectNode entry =
          operations
              .addObject()
              .put("operation", operation.number)
              .put("result", failure == null ? "success" : failure.getClass().getName());
      List<DynamoDbRequestRecorder.Request> requests = requests(operation.number);
      ArrayNode actualRequests = DynamoDbConfigurationFixture.requestsJson(requests);
      for (int i = 0; i < requests.size(); i++) {
        DynamoDbRequestRecorder.Request request = requests.get(i);
        ((ObjectNode) actualRequests.get(i)).set("original_sdk_response", request.originalResponse);
        ((ObjectNode) actualRequests.get(i)).set("effective_response", request.effectiveResponse);
        ((ObjectNode) actualRequests.get(i))
            .put("response_from_transmission", request.transmissions > 0);
      }
      entry.set("requests", actualRequests);
      entry.set("waits_ms", DynamoDbJson.sdk(waits));
      entry
          .put("pending", c.faults.pending(operation))
          .put("request_terminal", c.recorder.requestsFinished(operation).isDone());
      ArrayNode notifications = entry.putArray("notifications");
      for (int i = 0; i < failures.size(); i++) {
        RetentionFailure notified = failures.get(i);
        notifications
            .addObject()
            .put("category", "retention-failure")
            .put("aid", notified.aggregateId().asString())
            .put("mode", notified.mode().name())
            .put("cause", notified.cause().getClass().getName())
            .put("thread", notificationThreads.get(i));
      }
      ArrayNode faultObservations = entry.putArray("faults");
      for (FaultRegistry.Fault fault : registered.getOrDefault(operation.number, List.of())) {
        int applications = c.faults.applications(fault);
        faultObservations
            .addObject()
            .put("phase", fault.phase)
            .put("applications", applications)
            .put("reservations", c.faults.reservations(fault));
        assertEquals(0, c.faults.reservations(fault));
        assertTrue(applications > 0);
        if (fault.count != -1) assertEquals(fault.count, applications);
      }
      assertEquals(0, c.faults.pending(operation));
      FaultRegistry.Result result = c.faults.finish(operation);
      entry.put("fault_result", result.status);
      assertEquals("passed", result.status, () -> result.reasons.toString());
      assertFalse(c.closed());
    }

    List<DynamoDbRequestRecorder.Request> requests(int operation) {
      return c.recorder.requests().stream()
          .filter(r -> r.operation == operation)
          .collect(Collectors.toList());
    }

    List<DynamoDbRequestRecorder.Request> lastRequests() {
      return requests(nextOperation - 1);
    }

    ObjectNode last() {
      return (ObjectNode) operations.get(operations.size() - 1);
    }

    List<Integer> initialBatchSizes() {
      List<Integer> sizes = new ArrayList<>();
      JsonNode pending = null;
      for (DynamoDbRequestRecorder.Request request : lastRequests())
        if (request.phase.equals("retention-delete")) {
          JsonNode writes = request.original.path("RequestItems").path(c.snapshot);
          assertEquals(Set.of(c.snapshot), fields(request.original.path("RequestItems")));
          if (pending == null || pending.isEmpty()) sizes.add(writes.size());
          else assertEquals(pending, writes);
          pending =
              request.effectiveResponse == null
                  ? null
                  : request.effectiveResponse.path("UnprocessedItems").path(c.snapshot);
        }
      return sizes;
    }

    Map<String, AttributeValue> stored(String role, AggregateId id, long seq) {
      Map<String, AttributeValue> key = new LinkedHashMap<>();
      key.put("aid", AttributeValue.fromS(id.asString()));
      if (!role.equals("head"))
        key.put(
            role.equals("snapshot") ? "skey" : "seq_nr", AttributeValue.fromN(Long.toString(seq)));
      return c.admin
          .getItem(
              GetItemRequest.builder()
                  .tableName(DynamoDbConfigurationFixture.table(c, role))
                  .key(key)
                  .consistentRead(true)
                  .build())
          .item();
    }

    List<Map<String, AttributeValue>> rows(AggregateId id) {
      List<Map<String, AttributeValue>> rows = new ArrayList<>();
      c.admin
          .queryPaginator(
              QueryRequest.builder()
                  .tableName(c.snapshot)
                  .consistentRead(true)
                  .keyConditionExpression("aid = :aid")
                  .expressionAttributeValues(Map.of(":aid", AttributeValue.fromS(id.asString())))
                  .build())
          .forEach(page -> rows.addAll(page.items()));
      last().set("stored_snapshot_items", DynamoDbJson.sdk(rows));
      return rows;
    }

    ObjectNode history(AggregateId id) {
      ObjectNode history = DynamoDbJson.object();
      ArrayNode active = history.putArray("active"), marked = history.putArray("marked");
      for (Map<String, AttributeValue> item : rows(id)) {
        long key = Long.parseLong(item.get("skey").n());
        if (key == 0) continue;
        if (item.containsKey("active_history_seq_nr")) active.add(key);
        if (item.containsKey("ttl"))
          marked.addObject().put("seq_nr", key).put("ttl", Long.parseLong(item.get("ttl").n()));
      }
      return history;
    }

    @Override
    public void close() throws Exception {
      try {
        DynamoDbConfigurationFixture.close(c);
      } finally {
        observation
            .put("resources_closed", c.closed())
            .put("sdk_clients_and_http_pools_closed", c.closed());
        Path directory = Path.of("build/reports/dynamodb-snapshot-retention");
        Files.createDirectories(directory);
        Files.writeString(
            directory.resolve(name + (asynchronous ? "-async" : "-sync") + ".json"),
            observation.toPrettyString());
      }
    }
  }
}
