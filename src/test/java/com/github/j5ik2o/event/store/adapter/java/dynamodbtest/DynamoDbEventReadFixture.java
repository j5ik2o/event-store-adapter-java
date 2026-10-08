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
import java.util.concurrent.*;
import java.util.function.Supplier;
import software.amazon.awssdk.services.dynamodb.model.*;

/** Event-read scenes reuse the accepted configuration fixture and its borrowed event loop. */
public final class DynamoDbEventReadFixture {
  private final DynamoDbConfigurationFixture configuration;

  public DynamoDbEventReadFixture(DynamoDbConfigurationFixture configuration) {
    this.configuration = configuration;
  }

  /** Executes actual operations before comparing every supplied expectation and observation. */
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
      try (Scene<JsonNode> scene =
          new Scene<>(
              scenario.path("id").asText(),
              async,
              JsonPayloadSerializer.of(DynamoDbJson.mapper(), JsonNode.class),
              path)) {
        for (JsonNode step : scenario.path("steps")) {
          int operation = scene.nextOperation;
          actual.put("current_operation", operation);
          JsonNode arguments = step.path("arguments");
          ObjectNode received;
          if (step.path("op").asText().equals("persistEvent")) {
            JsonNode definition =
                scenario.at("/fixtures/events").path(arguments.path("event").asText());
            scene.write(event(definition));
            received = DynamoDbJson.object().put("result", "success");
          } else {
            List<EventEnvelope<JsonNode>> events =
                scene.read(
                    aid(arguments.path("aggregate_id")),
                    arguments.path("seq_nr").bigIntegerValue().longValueExact());
            scene.assertQuery(
                aid(arguments.path("aggregate_id")),
                arguments.path("seq_nr").longValue(),
                scene.pages.size());
            received = DynamoDbJson.object().put("result", "events");
            received.set("events", envelopes(events));
          }
          scene.last().set("outcome", received);
          compareExpected(scenario, step.path("expect"), received);
          observe(scene, step.path("observe"), arguments);
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
          firstFailure = new IllegalStateException("Event-read scene failed", failure);
        } else firstFailure.addSuppressed(failure);
      }
    }
    actual.remove("current_operation");
    if (firstFailure instanceof AssertionError) throw (AssertionError) firstFailure;
    if (firstFailure != null) throw (RuntimeException) firstFailure;
  }

  private static String unsupported(JsonNode scenario) {
    if (scenario.has("faults") || scenario.has("seed") || scenario.has("initialization"))
      return "このイベント読取り実行器は生成障害・seed・初期化期待を接続していない";
    for (JsonNode step : scenario.path("steps")) {
      if (!List.of("persistEvent", "getEventsByIdSinceSeqNr").contains(step.path("op").asText()))
        return "未接続の操作: " + step.path("op").asText();
      Iterator<String> fields = step.path("observe").fieldNames();
      while (fields.hasNext()) {
        String field = fields.next();
        if (!Set.of("requests", "minimum_request_count", "request_count", "no_requests_in_phases")
            .contains(field)) return "未接続の観測: " + field;
      }
      for (JsonNode request : step.at("/observe/requests")) {
        if (!request.path("api").asText().equals("Query")
            || !request.path("phase").asText().equals("read-events")) return "未接続の要求観測";
        Iterator<String> constraints = request.path("constraints").fieldNames();
        while (constraints.hasNext()) {
          String constraint = constraints.next();
          if (!Set.of(
                  "table",
                  "key_condition",
                  "consistent_read",
                  "scan_index_forward",
                  "follow_last_evaluated_key")
              .contains(constraint)) return "未接続の要求制約: " + constraint;
        }
      }
    }
    return null;
  }

  private static AggregateId aid(JsonNode value) {
    return AggregateId.of(value.path("type_name").asText(), value.path("value").asText());
  }

  private static EventEnvelope<JsonNode> event(JsonNode fixture) {
    return EventEnvelope.<JsonNode>builder()
        .aggregateId(aid(fixture.path("aggregate_id")))
        .seqNr(fixture.path("seq_nr").bigIntegerValue().longValueExact())
        .occurredAt(Instant.parse(fixture.path("occurred_at").asText()))
        .manifest(fixture.path("manifest").asText(""))
        .payload(fixture.get("payload"))
        .build();
  }

  static ArrayNode envelopes(List<? extends EventEnvelope<?>> events) {
    ArrayNode values = DynamoDbJson.mapper().createArrayNode();
    for (EventEnvelope<?> event : events) {
      ObjectNode value = values.addObject();
      value
          .putObject("aggregate_id")
          .put("type_name", event.aggregateId().typeName())
          .put("value", event.aggregateId().value());
      value
          .put("seq_nr", event.seqNr())
          .put("occurred_at", event.occurredAt().toString())
          .put("manifest", event.manifest());
      value.set("payload", DynamoDbJson.mapper().valueToTree(event.payload()));
    }
    return values;
  }

  private static void compareExpected(JsonNode scenario, JsonNode expected, JsonNode actual) {
    ObjectNode normalized = expected.deepCopy();
    if (expected.has("events")) {
      ArrayNode events = normalized.putArray("events");
      for (JsonNode reference : expected.path("events")) {
        ObjectNode value = scenario.at("/fixtures/events").path(reference.asText()).deepCopy();
        value.put("manifest", value.path("manifest").asText(""));
        value.put("occurred_at", Instant.parse(value.path("occurred_at").asText()).toString());
        events.add(value);
      }
    }
    assertTrue(
        equal(normalized, actual),
        "Actual event operation differs from the distributed expectation");
  }

  private static boolean equal(JsonNode expected, JsonNode actual) {
    if (expected.isNumber() && actual.isNumber())
      return expected.decimalValue().compareTo(actual.decimalValue()) == 0;
    if (expected.isArray() && actual.isArray()) {
      if (expected.size() != actual.size()) return false;
      for (int i = 0; i < expected.size(); i++)
        if (!equal(expected.get(i), actual.get(i))) return false;
      return true;
    }
    if (expected.isObject() && actual.isObject()) {
      if (expected.size() != actual.size()) return false;
      Iterator<String> fields = expected.fieldNames();
      while (fields.hasNext()) {
        String field = fields.next();
        if (!equal(expected.get(field), actual.path(field))) return false;
      }
      return true;
    }
    return expected.equals(actual);
  }

  private static void observe(Scene<?> scene, JsonNode expected, JsonNode arguments) {
    List<DynamoDbRequestRecorder.Request> requests = scene.lastRequests();
    int index = 0;
    for (JsonNode constraint : expected.path("requests")) {
      assertTrue(index < requests.size());
      DynamoDbRequestRecorder.Request actual = requests.get(index++);
      assertEquals(constraint.path("api").asText(), actual.api);
      assertEquals(constraint.path("phase").asText(), actual.phase);
      constraint
          .path("constraints")
          .fields()
          .forEachRemaining(
              entry -> {
                JsonNode value = entry.getValue();
                switch (entry.getKey()) {
                  case "table":
                    assertEquals(
                        DynamoDbConfigurationFixture.table(scene.c, value.asText()),
                        actual.transmitted.path("TableName").asText());
                    break;
                  case "key_condition":
                    ArrayNode terms = DynamoDbJson.mapper().createArrayNode();
                    for (JsonNode term : value.path("all")) {
                      ObjectNode bound = term.deepCopy();
                      if (term.path("argument").asText().equals("aggregate_id"))
                        bound.set(
                            "argument",
                            DynamoDbJson.sdk(
                                AttributeValue.fromS(
                                    aid(arguments.path("aggregate_id")).asString())));
                      else if (term.path("argument").asText().equals("seq_nr"))
                        bound.set(
                            "argument",
                            DynamoDbJson.sdk(
                                AttributeValue.fromN(
                                    arguments.path("seq_nr").bigIntegerValue().toString())));
                      else fail("Unconnected key-condition argument");
                      terms.add(bound);
                    }
                    assertEquals(
                        new HashSet<>(jsonList(terms)),
                        new HashSet<>(jsonList(actual.structure.at("/key_condition/all"))));
                    break;
                  case "consistent_read":
                    assertEquals(
                        value.booleanValue(),
                        actual.transmitted.path("ConsistentRead").booleanValue());
                    break;
                  case "scan_index_forward":
                    assertEquals(
                        value.booleanValue(),
                        actual.transmitted.path("ScanIndexForward").booleanValue());
                    break;
                  case "follow_last_evaluated_key":
                    assertTrue(value.booleanValue());
                    scene.assertPages();
                    break;
                  default:
                    fail("Unconnected request constraint");
                }
              });
    }
    expected
        .path("minimum_request_count")
        .fields()
        .forEachRemaining(
            entry ->
                assertTrue(
                    requests.stream().filter(r -> r.phase.equals(entry.getKey())).count()
                        >= entry.getValue().longValue()));
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

  private static List<JsonNode> jsonList(JsonNode array) {
    List<JsonNode> values = new ArrayList<>();
    array.forEach(values::add);
    return values;
  }

  <P> Scene<P> scene(String name, boolean async, PayloadSerializer<P> serializer) {
    return new Scene<>(name, async, serializer, DynamoDbJson.object());
  }

  final class Scene<P> implements AutoCloseable {
    final DynamoDbTestContext c = configuration.createContext();
    final ObjectNode observation;
    final ArrayNode operations;
    final boolean asynchronous;
    final String name;
    final Map<Integer, List<FaultRegistry.Fault>> registered = new LinkedHashMap<>();
    final List<QueryResponse> pages = new ArrayList<>();
    final EventStore<P, String> syncStore;
    final AsyncEventStore<P, String> asyncStore;
    int nextOperation = 1;

    Scene(String name, boolean async, PayloadSerializer<P> serializer, ObjectNode observation) {
      this.name = name;
      this.asynchronous = async;
      this.observation = observation;
      operations = observation.putArray("operations");
      observation.put("case", name).put("sdk_path", async ? "async" : "sync");
      observation.set("tables", DynamoDbJson.sdk(DynamoDbConfigurationTables.names(c)));
      try {
        DynamoDbConfigurationTables.create(c, RetentionPolicy.none());
        EventStoreConfig<P, String> config =
            EventStoreConfig.<P, String>builder()
                .payloadSerializer(new FaultPayloadSerializer<>(serializer, c.faults, false))
                .snapshotSerializer(JsonPayloadSerializer.of(String.class))
                .build();
        DynamoDbTableConfig tables = DynamoDbConfigurationTables.config(c).build();
        FaultRegistry.Operation operation = c.faults.begin(0, false);
        if (async) {
          asyncStore = DynamoDbEventStore.createAsync(c.async, tables, config).join();
          syncStore = null;
        } else {
          syncStore = DynamoDbEventStore.create(c.client, tables, config);
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
        String phase, int count, FaultRegistry.Injection injection, FaultRegistry.Effect effect) {
      registered
          .computeIfAbsent(nextOperation, ignored -> new ArrayList<>())
          .add(c.faults.register(nextOperation, phase, count, injection, effect));
    }

    void capturePages() {
      pages.clear();
      register(
          "read-events",
          -1,
          FaultRegistry.Injection.REPLACE_RESPONSE,
          DynamoDbFaultEffects.response(
              response -> {
                pages.add((QueryResponse) response);
                return response;
              }));
    }

    void write(EventEnvelope<P> event) {
      pages.clear();
      operate(
          true,
          () -> {
            if (asynchronous) asyncStore.persistEvent(event).join();
            else syncStore.persistEvent(event);
            return null;
          });
    }

    List<EventEnvelope<P>> read(AggregateId id, long seq) {
      pages.clear();
      boolean fault =
          registered.getOrDefault(nextOperation, List.of()).stream()
              .anyMatch(f -> f.phase.equals("read-events"));
      if (!fault && id != null && seq >= 0 && seq <= (1L << 53) - 1) capturePages();
      return operate(
          false,
          () ->
              asynchronous
                  ? asyncStore.getEventsByIdSinceSeqNr(id, seq).join()
                  : syncStore.getEventsByIdSinceSeqNr(id, seq));
    }

    private <T> T operate(boolean writing, Supplier<T> action) {
      FaultRegistry.Operation operation = c.faults.begin(nextOperation++, writing);
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
                  ? DynamoDbConfigurationFixture.category(failure)
                  : failure.getClass().getName());
      if (failure != null) {
        entry.put("exception", failure.getClass().getName());
        if (failure.getCause() != null) entry.put("cause", failure.getCause().getClass().getName());
      }
      if (result instanceof List) {
        @SuppressWarnings("unchecked")
        List<EventEnvelope<P>> events = (List<EventEnvelope<P>>) result;
        entry.set("events", envelopes(events));
      }
      entry.set("requests", DynamoDbConfigurationFixture.requestsJson(requests(operation.number)));
      entry.set("responses", DynamoDbJson.sdk(pages));
      entry
          .put("pending", c.faults.pending(operation))
          .put("request_terminal", c.recorder.requestsFinished(operation).isDone());
      ArrayNode faults = entry.putArray("faults");
      for (FaultRegistry.Fault fault : registered.getOrDefault(operation.number, List.of())) {
        faults
            .addObject()
            .put("phase", fault.phase)
            .put("applications", c.faults.applications(fault))
            .put("reservations", c.faults.reservations(fault));
        assertEquals(0, c.faults.reservations(fault));
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

    List<DynamoDbRequestRecorder.Request> requests(int operation) {
      List<DynamoDbRequestRecorder.Request> result = new ArrayList<>();
      for (DynamoDbRequestRecorder.Request request : c.recorder.requests())
        if (request.operation == operation) result.add(request);
      return result;
    }

    List<DynamoDbRequestRecorder.Request> lastRequests() {
      return requests(nextOperation - 1);
    }

    void assertQuery(AggregateId id, long seq, int count) {
      List<DynamoDbRequestRecorder.Request> requests = lastRequests();
      assertEquals(count, requests.size());
      for (DynamoDbRequestRecorder.Request request : requests) {
        assertEquals("Query", request.api);
        assertEquals("read-events", request.phase);
        assertEquals(1, request.httpAttempts);
        assertEquals(1, request.transmissions);
        assertEquals(request.marshalled, request.transmitted);
        assertEquals(c.journal, request.transmitted.path("TableName").asText());
        assertFalse(request.transmitted.has("IndexName"));
        assertTrue(request.transmitted.path("ConsistentRead").booleanValue());
        assertTrue(request.transmitted.path("ScanIndexForward").booleanValue());
        ObjectNode condition = DynamoDbJson.object();
        ArrayNode terms = condition.putArray("all");
        terms
            .addObject()
            .put("attribute", "aid")
            .put("operator", "eq")
            .set("argument", DynamoDbJson.sdk(AttributeValue.fromS(id.asString())));
        terms
            .addObject()
            .put("attribute", "seq_nr")
            .put("operator", "gte")
            .set("argument", DynamoDbJson.sdk(AttributeValue.fromN(Long.toString(seq))));
        assertEquals(condition, request.structure.path("key_condition"));
      }
      assertPages();
    }

    void assertPages() {
      List<DynamoDbRequestRecorder.Request> requests = lastRequests();
      assertEquals(pages.size(), requests.size());
      for (int i = 0; i < pages.size(); i++) {
        JsonNode cursor = requests.get(i).transmitted.path("ExclusiveStartKey");
        if (i == 0) assertTrue(cursor.isMissingNode() || cursor.isEmpty());
        else {
          assertFalse(pages.get(i - 1).lastEvaluatedKey().isEmpty());
          assertEquals(DynamoDbJson.sdk(pages.get(i - 1).lastEvaluatedKey()), cursor);
        }
      }
      if (!pages.isEmpty()) assertTrue(pages.get(pages.size() - 1).lastEvaluatedKey().isEmpty());
    }

    @Override
    public void close() throws Exception {
      try {
        DynamoDbConfigurationFixture.close(c);
      } finally {
        observation.put("resources_closed", c.closed());
        Path directory = Path.of("build/reports/dynamodb-event-read");
        Files.createDirectories(directory);
        Files.writeString(
            directory.resolve(name + (asynchronous ? "-async" : "-sync") + ".json"),
            observation.toPrettyString());
      }
    }
  }
}
