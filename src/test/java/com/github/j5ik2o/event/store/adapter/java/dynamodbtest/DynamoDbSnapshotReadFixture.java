package com.github.j5ik2o.event.store.adapter.java.dynamodbtest;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.j5ik2o.event.store.adapter.java.core.*;
import com.github.j5ik2o.event.store.adapter.java.dynamodb.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.dynamodb.model.*;

/**
 * Latest-snapshot scenes use the existing configuration, fault and resource ownership machinery.
 */
public final class DynamoDbSnapshotReadFixture {
  private final DynamoDbConfigurationFixture configuration;

  public DynamoDbSnapshotReadFixture(DynamoDbConfigurationFixture configuration) {
    this.configuration = configuration;
  }

  /** Checks connection of every operation and observation before running either SDK path. */
  public static String unsupported(JsonNode scenario) {
    if (scenario.path("store").hasNonNull("retention_count") || scenario.has("initialization"))
      return "保持整理または初期化期待はこの最新snapshot実行器に未接続";
    for (JsonNode step : scenario.path("steps")) {
      if (!Set.of(
              "persistEvent",
              "persistEventAndSnapshot",
              "getLatestSnapshotById",
              "getEventsByIdSinceSeqNr")
          .contains(step.path("op").asText())) return "未接続の操作: " + step.path("op").asText();
      Iterator<String> fields = step.path("observe").fieldNames();
      while (fields.hasNext()) {
        String field = fields.next();
        if (!Set.of("requests", "request_count", "no_requests_in_phases").contains(field))
          return "未接続の観測: " + field;
      }
      for (JsonNode request : step.at("/observe/requests")) {
        if (!request.path("api").asText().equals("BatchGetItem")
            || !request.path("phase").asText().equals("read-snapshot")) return "未接続の要求観測";
        Iterator<String> constraints = request.path("constraints").fieldNames();
        while (constraints.hasNext()) {
          String constraint = constraints.next();
          if (!Set.of(
                  "consistent_read_all_tables",
                  "head_and_current_snapshot",
                  "only_unprocessed_keys")
              .contains(constraint)) return "未接続の要求制約: " + constraint;
        }
      }
    }
    for (JsonNode fault : scenario.path("faults")) {
      String kind = fault.path("kind").asText(), phase = fault.path("phase").asText();
      if (kind.equals("serialization-error")
          && Set.of(
                  "serialize-event",
                  "serialize-snapshot",
                  "deserialize-event",
                  "deserialize-snapshot")
              .contains(phase)) continue;
      if (kind.equals("storage-error")
          && Set.of("commit", "read-events", "read-snapshot").contains(phase)) continue;
      if (kind.equals("sdk-response")
          && phase.equals("read-snapshot")
          && fault.at("/details/unprocessed_keys").isArray()
          && DynamoDbConfigurationFixture.stringsFromFields(fault.path("details"))
              .equals(Set.of("responses", "unprocessed_keys"))) continue;
      if (kind.equals("read-interleave")
          && phase.equals("read-snapshot")
          && fault.at("/details/after").asText().equals("capture-head-before-batch")
          && fault.at("/details/then").asText().equals("replace-batch-response-head")
          && fault
              .at("/details/interleaved_operation/op")
              .asText()
              .equals("persistEventAndSnapshot")) continue;
      return "未接続の障害: " + kind + "/" + phase;
    }
    for (JsonNode seed : scenario.at("/seed/items")) {
      if (!seed.path("table").asText().equals("snapshot")) return "未接続のseedテーブル";
      Iterator<JsonNode> types = seed.path("attributes").elements();
      while (types.hasNext())
        if (!Set.of("S", "N", "B").contains(types.next().asText())) return "未接続のseed属性型";
    }
    return null;
  }

  /** Returned values are produced by the public stores before expectations are consulted. */
  public void execute(JsonNode scenario, ObjectNode actual) {
    String unsupported = unsupported(scenario);
    if (unsupported != null) {
      actual.put("unsupported", unsupported);
      return;
    }
    Throwable firstFailure = null;
    for (boolean async : List.of(false, true)) {
      actual.put("current_operation", 0);
      ObjectNode path = actual.putObject(async ? "async" : "sync");
      PayloadSerializer<JsonNode> json =
          JsonPayloadSerializer.of(DynamoDbJson.mapper(), JsonNode.class);
      try (Scene<JsonNode, JsonNode> scene =
          new Scene<>(scenario.path("id").asText(), async, json, json, scenario, path)) {
        register(scene, scenario);
        for (JsonNode step : scenario.path("steps")) {
          actual.put("current_operation", scene.nextOperation);
          ObjectNode outcome;
          Throwable failure = null;
          try {
            outcome =
                scene.operate(
                    step.path("op").asText().startsWith("persist"),
                    () -> perform(scene, scenario, step));
          } catch (RuntimeException error) {
            failure = EventStoreExceptions.unwrap(error);
            outcome = DynamoDbJson.object();
            ObjectNode actualError = outcome.putObject("error").put("category", category(failure));
            if (failure instanceof ContractViolationException)
              actualError.put("rule", ((ContractViolationException) failure).rule());
          }
          scene.last().set("outcome", outcome);
          compare(scenario, step.path("expect"), outcome, failure);
          observe(scene, step, scenario);
        }
      } catch (RuntimeException | AssertionError failure) {
        path.put("failed_operation", actual.path("current_operation").intValue());
        if (firstFailure == null) {
          actual.put("failed_operation", actual.path("current_operation").intValue());
          firstFailure = failure;
        } else firstFailure.addSuppressed(failure);
      } catch (Exception failure) {
        path.put("failed_operation", actual.path("current_operation").intValue());
        if (firstFailure == null) {
          actual.put("failed_operation", actual.path("current_operation").intValue());
          firstFailure = new IllegalStateException("Snapshot-read scene failed", failure);
        } else firstFailure.addSuppressed(failure);
      }
    }
    actual.remove("current_operation");
    if (firstFailure instanceof AssertionError) throw (AssertionError) firstFailure;
    if (firstFailure != null) throw (RuntimeException) firstFailure;
  }

  private static ObjectNode perform(
      Scene<JsonNode, JsonNode> scene, JsonNode scenario, JsonNode step) {
    JsonNode arguments = step.path("arguments");
    switch (step.path("op").asText()) {
      case "persistEvent":
        scene.persist(
            event(scenario.at("/fixtures/events").path(arguments.path("event").asText())));
        return DynamoDbJson.object().put("result", "success");
      case "persistEventAndSnapshot":
        scene.persist(
            event(scenario.at("/fixtures/events").path(arguments.path("event").asText())),
            snapshot(scenario.at("/fixtures/snapshots").path(arguments.path("snapshot").asText())));
        return DynamoDbJson.object().put("result", "success");
      case "getLatestSnapshotById":
        return latest(scene.latest(aid(arguments.path("aggregate_id"))));
      case "getEventsByIdSinceSeqNr":
        ObjectNode result = DynamoDbJson.object().put("result", "events");
        result.set(
            "events",
            DynamoDbEventReadFixture.envelopes(
                scene.events(
                    aid(arguments.path("aggregate_id")),
                    arguments.path("seq_nr").bigIntegerValue().longValueExact())));
        return result;
      default:
        throw new IllegalArgumentException("Unsupported operation");
    }
  }

  static void compare(JsonNode scenario, JsonNode expected, ObjectNode actual, Throwable failure) {
    if (expected.has("error")) {
      assertNotNull(failure);
      assertEquals(expected.at("/error/category").asText(), category(failure));
      if (expected.path("error").has("rule")) {
        ContractViolationException violation =
            assertInstanceOf(ContractViolationException.class, failure);
        assertEquals(expected.at("/error/rule").asText(), violation.rule());
      }
      for (JsonNode word : expected.at("/error/message/must_contain"))
        assertTrue(failure.getMessage().contains(word.asText()));
      for (JsonNode word : expected.at("/error/message/must_not_contain"))
        assertFalse(failure.getMessage().contains(word.asText()));
      return;
    }
    assertNull(failure, () -> String.valueOf(failure));
    ObjectNode normalized = expected.deepCopy();
    if (expected.hasNonNull("snapshot")) {
      ObjectNode value =
          scenario.at("/fixtures/snapshots").path(expected.path("snapshot").asText()).deepCopy();
      value.put("manifest", value.path("manifest").asText(""));
      normalized.set("snapshot", value);
    }
    if (expected.has("events")) {
      ArrayNode events = normalized.putArray("events");
      for (JsonNode reference : expected.path("events")) {
        ObjectNode value = scenario.at("/fixtures/events").path(reference.asText()).deepCopy();
        value.put("manifest", value.path("manifest").asText(""));
        value.put("occurred_at", Instant.parse(value.path("occurred_at").asText()).toString());
        events.add(value);
      }
    }
    // Parse both through the same JSON reader; do not coerce booleans or strings.
    try {
      assertEquals(
          DynamoDbJson.mapper().readTree(normalized.toString()),
          DynamoDbJson.mapper().readTree(actual.toString()));
    } catch (java.io.IOException error) {
      throw new IllegalStateException(error);
    }
  }

  private static String category(Throwable failure) {
    return DynamoDbConfigurationFixture.category(failure).replace('_', '-');
  }

  static AggregateId aid(JsonNode value) {
    return AggregateId.of(value.path("type_name").asText(), value.path("value").asText());
  }

  static EventEnvelope<JsonNode> event(JsonNode value) {
    return EventEnvelope.<JsonNode>builder()
        .aggregateId(aid(value.path("aggregate_id")))
        .seqNr(value.path("seq_nr").bigIntegerValue().longValueExact())
        .occurredAt(Instant.parse(value.path("occurred_at").asText()))
        .manifest(value.path("manifest").asText(""))
        .payload(value.get("payload"))
        .build();
  }

  static SnapshotEnvelope<JsonNode> snapshot(JsonNode value) {
    return SnapshotEnvelope.<JsonNode>builder()
        .seqNr(value.path("seq_nr").bigIntegerValue().longValueExact())
        .manifest(value.path("manifest").asText(""))
        .aggregate(value.get("aggregate"))
        .build();
  }

  static ObjectNode latest(Optional<? extends SnapshotReadResult<?>> result) {
    ObjectNode actual = DynamoDbJson.object();
    if (result.isEmpty()) return actual.put("result", "none");
    actual.put("result", "snapshot").put("head_seq_nr", result.get().headSeqNr());
    if (result.get().snapshot().isPresent()) {
      SnapshotEnvelope<?> snapshot = result.get().snapshot().orElseThrow();
      ObjectNode value =
          actual
              .putObject("snapshot")
              .put("seq_nr", snapshot.seqNr())
              .put("manifest", snapshot.manifest());
      value.set("aggregate", DynamoDbJson.mapper().valueToTree(snapshot.aggregate()));
    } else actual.putNull("snapshot");
    return actual;
  }

  private static void register(Scene<JsonNode, JsonNode> scene, JsonNode scenario) {
    for (JsonNode fault : scenario.path("faults")) {
      JsonNode details = fault.path("details");
      FaultRegistry.Effect effect;
      switch (fault.path("kind").asText()) {
        case "serialization-error":
          effect =
              DynamoDbFaultEffects.serializationError(
                  new SerializationException(details.path("message").asText()));
          break;
        case "storage-error":
          effect = DynamoDbFaultEffects.sdkError("InternalServerError");
          break;
        case "sdk-response":
          Map<String, List<Map<String, AttributeValue>>> pending = new LinkedHashMap<>();
          for (JsonNode token : details.path("unprocessed_keys")) {
            String[] parts = token.asText().split(":", -1);
            pending
                .computeIfAbsent(
                    DynamoDbConfigurationFixture.table(scene.c, parts[0]),
                    ignored -> new ArrayList<>())
                .add(dataKey(parts[0], parts[1]));
          }
          effect = DynamoDbFaultEffects.partialBatchGet(scene.c.admin, pending);
          break;
        case "read-interleave":
          JsonNode arguments = details.at("/interleaved_operation/arguments");
          effect =
              DynamoDbFaultEffects.readInterleave(
                  scene.c.admin,
                  scene.c.head,
                  () -> {
                    EventStore<JsonNode, JsonNode> writer =
                        DynamoDbEventStore.create(
                            scene.c.admin,
                            DynamoDbConfigurationTables.config(scene.c).build(),
                            scene.config);
                    Map<String, AttributeValue> before =
                        scene.stored(
                            "head",
                            aid(
                                scenario
                                    .at("/fixtures/events")
                                    .path(arguments.path("event").asText())
                                    .path("aggregate_id")));
                    writer.persistEventAndSnapshot(
                        event(
                            scenario.at("/fixtures/events").path(arguments.path("event").asText())),
                        snapshot(
                            scenario
                                .at("/fixtures/snapshots")
                                .path(arguments.path("snapshot").asText())));
                    scene.observation.set("interleave_head_before", DynamoDbJson.sdk(before));
                    scene.observation.set(
                        "interleave_current_after",
                        DynamoDbJson.sdk(
                            scene.stored(
                                "snapshot",
                                aid(
                                    scenario
                                        .at("/fixtures/events")
                                        .path(arguments.path("event").asText())
                                        .path("aggregate_id")))));
                  });
          break;
        default:
          throw new IllegalArgumentException("Unsupported fault");
      }
      scene.register(
          fault.path("operation").intValue(),
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

  private static void observe(Scene<?, ?> scene, JsonNode step, JsonNode scenario) {
    JsonNode expected = step.path("observe");
    List<DynamoDbRequestRecorder.Request> requests = scene.lastRequests();
    if (step.path("op").asText().equals("getLatestSnapshotById") && !requests.isEmpty())
      scene.assertBatch(aid(step.at("/arguments/aggregate_id")), requests.size());
    int index = 0;
    for (JsonNode exp : expected.path("requests")) {
      assertTrue(index < requests.size());
      DynamoDbRequestRecorder.Request request = requests.get(index++);
      assertEquals(exp.path("api").asText(), request.api);
      assertEquals(exp.path("phase").asText(), request.phase);
      for (Iterator<Map.Entry<String, JsonNode>> entries = exp.path("constraints").fields();
          entries.hasNext(); ) {
        Map.Entry<String, JsonNode> constraint = entries.next();
        assertTrue(constraint.getValue().booleanValue());
        switch (constraint.getKey()) {
          case "head_and_current_snapshot":
            assertEquals(
                Set.of(scene.c.head, scene.c.snapshot),
                fields(request.original.path("RequestItems")));
            break;
          case "consistent_read_all_tables":
            DynamoDbConfigurationFixture.strong(request.original);
            break;
          case "only_unprocessed_keys":
            Map<String, List<Map<String, AttributeValue>>> pending = new LinkedHashMap<>();
            for (JsonNode fault : scenario.path("faults"))
              if (fault.path("operation").intValue() == scene.nextOperation - 1)
                for (JsonNode token : fault.at("/details/unprocessed_keys")) {
                  String[] parts = token.asText().split(":", -1);
                  pending
                      .computeIfAbsent(
                          DynamoDbConfigurationFixture.table(scene.c, parts[0]),
                          ignored -> new ArrayList<>())
                      .add(dataKey(parts[0], parts[1]));
                }
            assertEquals(pending.keySet(), fields(request.original.path("RequestItems")));
            pending.forEach(
                (table, keys) ->
                    assertEquals(
                        DynamoDbJson.sdk(keys),
                        request.original.at("/RequestItems/" + table + "/Keys")));
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
    for (JsonNode phase : expected.path("no_requests_in_phases"))
      assertEquals(0, requests.stream().filter(r -> r.phase.equals(phase.asText())).count());
  }

  private static Set<String> fields(JsonNode node) {
    return DynamoDbConfigurationFixture.stringsFromFields(node);
  }

  static Map<String, AttributeValue> dataKey(String role, String aid) {
    return role.equals("head")
        ? Map.of("aid", AttributeValue.fromS(aid))
        : Map.of("aid", AttributeValue.fromS(aid), "skey", AttributeValue.fromN("0"));
  }

  <P, A> Scene<P, A> scene(
      String name, boolean async, PayloadSerializer<P> events, PayloadSerializer<A> snapshots) {
    ObjectNode settings = DynamoDbJson.object();
    settings.putObject("store").put("retry_limit", 3);
    return new Scene<>(name, async, events, snapshots, settings, DynamoDbJson.object());
  }

  final class Scene<P, A> implements AutoCloseable {
    final DynamoDbTestContext c = configuration.createContext();
    final ObjectNode observation;
    final ArrayNode operations;
    final boolean asynchronous;
    final String name;
    final Map<Integer, List<FaultRegistry.Fault>> registered = new LinkedHashMap<>();
    final List<Long> waits = new ArrayList<>();
    final EventStoreConfig<P, A> config;
    final EventStore<P, A> syncStore;
    final AsyncEventStore<P, A> asyncStore;
    int nextOperation = 1;

    Scene(
        String name,
        boolean async,
        PayloadSerializer<P> events,
        PayloadSerializer<A> snapshots,
        JsonNode settings,
        ObjectNode observation) {
      this.name = name;
      this.asynchronous = async;
      this.observation = observation;
      operations = observation.putArray("operations");
      observation.put("case", name).put("sdk_path", async ? "async" : "sync");
      observation.set("tables", DynamoDbJson.sdk(DynamoDbConfigurationTables.names(c)));
      config =
          EventStoreConfig.<P, A>builder()
              .payloadSerializer(new FaultPayloadSerializer<>(events, c.faults, false))
              .snapshotSerializer(new FaultPayloadSerializer<>(snapshots, c.faults, true))
              .build();
      try {
        DynamoDbConfigurationTables.create(c, RetentionPolicy.none());
        for (JsonNode definition : settings.at("/seed/items")) {
          Map<String, AttributeValue> item = new LinkedHashMap<>();
          definition
              .path("attributes")
              .fields()
              .forEachRemaining(
                  entry -> {
                    String value = definition.path("values").path(entry.getKey()).asText();
                    switch (entry.getValue().asText()) {
                      case "S":
                        item.put(entry.getKey(), AttributeValue.fromS(value));
                        break;
                      case "N":
                        item.put(entry.getKey(), AttributeValue.fromN(value));
                        break;
                      case "B":
                        item.put(
                            entry.getKey(),
                            AttributeValue.fromB(
                                SdkBytes.fromByteArray(
                                    JsonPayloadSerializer.of(DynamoDbJson.mapper(), JsonNode.class)
                                        .serialize(
                                            definition.path("binary_json").get(entry.getKey())))));
                        break;
                      default:
                        throw new IllegalArgumentException("Unsupported seed attribute");
                    }
                  });
          c.admin.putItem(PutItemRequest.builder().tableName(c.snapshot).item(item).build());
          observation.set("seed_items", DynamoDbJson.sdk(item));
        }
        DynamoDbTableConfig tables =
            DynamoDbConfigurationTables.config(c)
                .configurationReadRetryLimit(settings.at("/store/retry_limit").asInt(10))
                .build();
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
        finish(operation, null, null);
        observation.set(
            "configuration_items", DynamoDbJson.sdk(DynamoDbConfigurationFixture.stored(c)));
      } catch (Throwable failure) {
        c.close();
        throw failure;
      }
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

    void persist(EventEnvelope<P> event) {
      if (asynchronous) asyncStore.persistEvent(event).join();
      else syncStore.persistEvent(event);
    }

    void persist(EventEnvelope<P> event, SnapshotEnvelope<A> snapshot) {
      if (asynchronous) asyncStore.persistEventAndSnapshot(event, snapshot).join();
      else syncStore.persistEventAndSnapshot(event, snapshot);
    }

    Optional<SnapshotReadResult<A>> latest(AggregateId id) {
      return asynchronous
          ? asyncStore.getLatestSnapshotById(id).join()
          : syncStore.getLatestSnapshotById(id);
    }

    List<EventEnvelope<P>> events(AggregateId id, long seq) {
      return asynchronous
          ? asyncStore.getEventsByIdSinceSeqNr(id, seq).join()
          : syncStore.getEventsByIdSinceSeqNr(id, seq);
    }

    void write(EventEnvelope<P> event) {
      operate(
          true,
          () -> {
            persist(event);
            return null;
          });
    }

    void write(EventEnvelope<P> event, SnapshotEnvelope<A> snapshot) {
      operate(
          true,
          () -> {
            persist(event, snapshot);
            return null;
          });
    }

    Optional<SnapshotReadResult<A>> read(AggregateId id) {
      return operate(false, () -> latest(id));
    }

    List<EventEnvelope<P>> readEvents(AggregateId id, long seq) {
      return operate(false, () -> events(id, seq));
    }

    <T> T operate(boolean writing, Supplier<T> action) {
      FaultRegistry.Operation operation = c.faults.begin(nextOperation++, writing);
      waits.clear();
      Throwable failure = null;
      T result = null;
      try {
        result = action.get();
        return result;
      } catch (RuntimeException | AssertionError error) {
        failure = EventStoreExceptions.unwrap(error);
        throw error;
      } finally {
        finish(operation, failure, result);
      }
    }

    void finish(FaultRegistry.Operation operation, Throwable failure, Object result) {
      c.recorder.requestsFinished(operation).join();
      ObjectNode entry = operations.addObject().put("operation", operation.number);
      entry.put(
          "result",
          failure == null
              ? "success"
              : failure instanceof EventStoreException
                  ? category(failure)
                  : failure.getClass().getName());
      if (failure != null) entry.put("exception", failure.getClass().getName());
      if (result instanceof Optional) {
        @SuppressWarnings("unchecked")
        Optional<SnapshotReadResult<A>> snapshot = (Optional<SnapshotReadResult<A>>) result;
        entry.set("outcome", DynamoDbSnapshotReadFixture.latest(snapshot));
      }
      if (result instanceof List) {
        @SuppressWarnings("unchecked")
        List<EventEnvelope<P>> events = (List<EventEnvelope<P>>) result;
        entry.set("events", DynamoDbEventReadFixture.envelopes(events));
      }
      entry.set("requests", DynamoDbConfigurationFixture.requestsJson(requests(operation.number)));
      entry.set("waits_ms", DynamoDbJson.sdk(waits));
      entry
          .put("pending", c.faults.pending(operation))
          .put("request_terminal", c.recorder.requestsFinished(operation).isDone());
      ArrayNode faults = entry.putArray("faults");
      for (FaultRegistry.Fault fault : registered.getOrDefault(operation.number, List.of())) {
        int applications = c.faults.applications(fault);
        faults
            .addObject()
            .put("phase", fault.phase)
            .put("applications", applications)
            .put("reservations", c.faults.reservations(fault));
        assertEquals(0, c.faults.reservations(fault));
        assertTrue(applications > 0);
        if (fault.count != -1) assertEquals(fault.count, applications);
      }
      assertEquals(0, c.faults.pending(operation));
      FaultRegistry.Result outcome = c.faults.finish(operation);
      entry.put("fault_result", outcome.status);
      assertEquals("passed", outcome.status, () -> outcome.reasons.toString());
      assertFalse(c.closed());
    }

    ObjectNode last() {
      return (ObjectNode) operations.get(operations.size() - 1);
    }

    List<DynamoDbRequestRecorder.Request> requests(int number) {
      List<DynamoDbRequestRecorder.Request> result = new ArrayList<>();
      for (DynamoDbRequestRecorder.Request request : c.recorder.requests())
        if (request.operation == number) result.add(request);
      return result;
    }

    List<DynamoDbRequestRecorder.Request> lastRequests() {
      return requests(nextOperation - 1);
    }

    void assertBatch(AggregateId id, int count) {
      List<DynamoDbRequestRecorder.Request> requests = lastRequests();
      assertEquals(count, requests.size());
      assertEquals(
          Set.of(c.head, c.snapshot), fields(requests.get(0).original.path("RequestItems")));
      for (DynamoDbRequestRecorder.Request request : requests) {
        assertEquals("BatchGetItem", request.api);
        assertEquals("read-snapshot", request.phase);
        assertEquals(1, request.httpAttempts);
        DynamoDbConfigurationFixture.strong(request.original);
        DynamoDbConfigurationFixture.strong(request.marshalled);
        if (request.transmissions > 0) {
          assertEquals(1, request.transmissions);
          assertEquals(request.marshalled, request.transmitted);
          DynamoDbConfigurationFixture.strong(request.transmitted);
        }
        request
            .original
            .path("RequestItems")
            .fields()
            .forEachRemaining(
                entry ->
                    assertEquals(
                        DynamoDbJson.sdk(
                            List.of(dataKey(c.targets.role(entry.getKey()), id.asString()))),
                        entry.getValue().path("Keys")));
      }
    }

    Map<String, AttributeValue> stored(String role, AggregateId id) {
      return c.admin
          .getItem(
              GetItemRequest.builder()
                  .tableName(DynamoDbConfigurationFixture.table(c, role))
                  .key(dataKey(role, id.asString()))
                  .consistentRead(true)
                  .build())
          .item();
    }

    @Override
    public void close() throws Exception {
      try {
        DynamoDbConfigurationFixture.close(c);
      } finally {
        observation
            .put("resources_closed", c.closed())
            .put("sdk_clients_and_http_pools_closed", c.closed());
        Path directory = Path.of("build/reports/dynamodb-snapshot-read");
        Files.createDirectories(directory);
        Files.writeString(
            directory.resolve(name + (asynchronous ? "-async" : "-sync") + ".json"),
            observation.toPrettyString());
      }
    }
  }
}
