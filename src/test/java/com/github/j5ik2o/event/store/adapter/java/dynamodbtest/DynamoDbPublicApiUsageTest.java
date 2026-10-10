package com.github.j5ik2o.event.store.adapter.java.dynamodbtest;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.j5ik2o.event.store.adapter.java.core.AggregateId;
import com.github.j5ik2o.event.store.adapter.java.core.AsyncEventStore;
import com.github.j5ik2o.event.store.adapter.java.core.EventStore;
import com.github.j5ik2o.event.store.adapter.java.core.RetentionPolicy;
import com.github.j5ik2o.event.store.adapter.java.dynamodb.DynamoDbEventStore;
import com.github.j5ik2o.event.store.adapter.java.examples.UserAccountExample;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;

class DynamoDbPublicApiUsageTest {
  @RegisterExtension
  static final DynamoDbConfigurationFixture fixture = new DynamoDbConfigurationFixture();

  private static final AggregateId ID = AggregateId.of("UserAccount", "example-1");

  @Test
  void synchronousPublicFactorySavesAndRestoresThroughTheExample() throws Exception {
    DynamoDbTestContext c = fixture.createContext();
    ObjectNode evidence = DynamoDbJson.object().put("path", "sync");
    try {
      DynamoDbConfigurationTables.create(c, RetentionPolicy.none());
      EventStore<String, String> store =
          observe(
              c,
              evidence,
              0,
              false,
              () ->
                  DynamoDbEventStore.create(
                      c.client,
                      DynamoDbConfigurationTables.config(c).build(),
                      UserAccountExample.config()));
      assertTrue(
          observe(c, evidence, 1, false, () -> UserAccountExample.restore(store, ID)).isEmpty());

      observe(
          c,
          evidence,
          2,
          true,
          () -> {
            store.persistEvent(UserAccountExample.event(ID, 1, "Alice"));
            return null;
          });
      assertEquals(
          Optional.of("Alice"),
          observe(c, evidence, 3, false, () -> UserAccountExample.restore(store, ID)));
      assertReplayStart(c, 3, 1);

      observe(
          c,
          evidence,
          4,
          true,
          () -> {
            store.persistEventAndSnapshot(
                UserAccountExample.event(ID, 2, "Bob"), UserAccountExample.snapshot(2, "Bob"));
            return null;
          });
      assertEquals(
          Optional.of("Bob"),
          observe(c, evidence, 5, false, () -> UserAccountExample.restore(store, ID)));
      observe(
          c,
          evidence,
          6,
          true,
          () -> {
            store.persistEvent(UserAccountExample.event(ID, 3, "Carol"));
            return null;
          });
      assertEquals(
          Optional.of("Carol"),
          observe(c, evidence, 7, false, () -> UserAccountExample.restore(store, ID)));
      assertReplayStart(c, 7, 3);
      assertSaved(c, evidence);
      assertMixedSnapshot(c, evidence, () -> UserAccountExample.restore(store, ID));
    } finally {
      saveAndClose(c, evidence);
    }
  }

  @Test
  void asynchronousPublicFactorySavesAndRestoresThroughTheExample() throws Exception {
    DynamoDbTestContext c = fixture.createContext();
    ObjectNode evidence = DynamoDbJson.object().put("path", "async");
    try {
      DynamoDbConfigurationTables.create(c, RetentionPolicy.none());
      AsyncEventStore<String, String> store =
          observe(
              c,
              evidence,
              0,
              false,
              () ->
                  DynamoDbEventStore.createAsync(
                          c.async,
                          DynamoDbConfigurationTables.config(c).build(),
                          UserAccountExample.config())
                      .join());
      assertTrue(
          observe(c, evidence, 1, false, () -> UserAccountExample.restoreAsync(store, ID).join())
              .isEmpty());

      observe(
          c,
          evidence,
          2,
          true,
          () -> store.persistEvent(UserAccountExample.event(ID, 1, "Alice")).join());
      assertEquals(
          Optional.of("Alice"),
          observe(c, evidence, 3, false, () -> UserAccountExample.restoreAsync(store, ID).join()));
      assertReplayStart(c, 3, 1);

      observe(
          c,
          evidence,
          4,
          true,
          () ->
              store
                  .persistEventAndSnapshot(
                      UserAccountExample.event(ID, 2, "Bob"), UserAccountExample.snapshot(2, "Bob"))
                  .join());
      assertEquals(
          Optional.of("Bob"),
          observe(c, evidence, 5, false, () -> UserAccountExample.restoreAsync(store, ID).join()));
      observe(
          c,
          evidence,
          6,
          true,
          () -> store.persistEvent(UserAccountExample.event(ID, 3, "Carol")).join());
      assertEquals(
          Optional.of("Carol"),
          observe(c, evidence, 7, false, () -> UserAccountExample.restoreAsync(store, ID).join()));
      assertReplayStart(c, 7, 3);
      assertSaved(c, evidence);
      assertMixedSnapshot(c, evidence, () -> UserAccountExample.restoreAsync(store, ID).join());
    } finally {
      saveAndClose(c, evidence);
    }
  }

  private static <T> T observe(
      DynamoDbTestContext c, ObjectNode evidence, int number, boolean writing, Supplier<T> action) {
    FaultRegistry.Operation operation = c.faults.begin(number, writing);
    ObjectNode actual = evidence.withArray("operations").addObject().put("operation", number);
    try {
      T result = action.get();
      actual.put("result", "success");
      if (result instanceof Optional) {
        Optional<?> value = (Optional<?>) result;
        actual.put("present", value.isPresent());
        value.ifPresent(name -> actual.put("restored_name", (String) name));
      }
      return result;
    } catch (RuntimeException | Error failure) {
      actual.put("result", failure.getClass().getName());
      throw failure;
    } finally {
      c.recorder.requestsFinished(operation).join();
      actual.set(
          "requests", DynamoDbConfigurationFixture.requestsJson(requests(c, operation.number)));
      actual.put("request_terminal", c.recorder.requestsFinished(operation).isDone());
      actual.put("pending", c.faults.pending(operation));
      assertEquals(0, c.faults.pending(operation));
      FaultRegistry.Result result = c.faults.finish(operation);
      actual.put("fault_result", result.status);
      assertEquals("passed", result.status, () -> result.reasons.toString());
    }
  }

  private static List<DynamoDbRequestRecorder.Request> requests(
      DynamoDbTestContext c, int operation) {
    return c.recorder.requests().stream()
        .filter(request -> request.operation == operation)
        .collect(Collectors.toList());
  }

  private static void assertReplayStart(DynamoDbTestContext c, int operation, long start) {
    List<DynamoDbRequestRecorder.Request> queries =
        requests(c, operation).stream()
            .filter(request -> request.phase.equals("read-events"))
            .collect(Collectors.toList());
    assertEquals(1, queries.size());
    assertEquals(
        Long.toString(start),
        queries.get(0).transmitted.at("/ExpressionAttributeValues/:seq_nr/N").asText());
    assertEquals(
        ID.asString(), queries.get(0).transmitted.at("/ExpressionAttributeValues/:aid/S").asText());
  }

  private static void assertSaved(DynamoDbTestContext c, ObjectNode evidence) {
    Map<String, AttributeValue> head =
        stored(c, c.head, Map.of("aid", AttributeValue.fromS(ID.asString())));
    Map<String, AttributeValue> current =
        stored(
            c,
            c.snapshot,
            Map.of("aid", AttributeValue.fromS(ID.asString()), "skey", AttributeValue.fromN("0")));
    evidence.set("stored_head", DynamoDbJson.sdk(head));
    evidence.set("stored_current", DynamoDbJson.sdk(current));
    assertEquals("3", head.get("seq_nr").n());
    assertEquals("2", current.get("seq_nr").n());
    assertEquals("Bob", DynamoDbJson.read(current.get("payload").b().asByteArray()).textValue());
    for (long seq = 1; seq <= 3; seq++) {
      Map<String, AttributeValue> event =
          stored(
              c,
              c.journal,
              Map.of(
                  "aid", AttributeValue.fromS(ID.asString()),
                  "seq_nr", AttributeValue.fromN(Long.toString(seq))));
      evidence.withArray("stored_journal").add(DynamoDbJson.sdk(event));
      assertEquals("user-name", event.get("manifest").s());
      assertEquals(
          List.of("Alice", "Bob", "Carol").get((int) seq - 1),
          DynamoDbJson.read(event.get("payload").b().asByteArray()).textValue());
    }
  }

  private static Map<String, AttributeValue> stored(
      DynamoDbTestContext c, String table, Map<String, AttributeValue> key) {
    return c.admin
        .getItem(GetItemRequest.builder().tableName(table).key(key).consistentRead(true).build())
        .item();
  }

  private static void assertMixedSnapshot(
      DynamoDbTestContext c, ObjectNode evidence, Supplier<Optional<String>> restore) {
    FaultRegistry.Fault fault =
        c.faults.register(
            8,
            "read-snapshot",
            1,
            FaultRegistry.Injection.REPLACE_RESPONSE,
            DynamoDbFaultEffects.readInterleave(
                c.admin,
                c.head,
                () -> {
                  EventStore<String, String> writer =
                      DynamoDbEventStore.create(
                          c.admin,
                          DynamoDbConfigurationTables.config(c).build(),
                          UserAccountExample.config());
                  writer.persistEventAndSnapshot(
                      UserAccountExample.event(ID, 4, "Dora"),
                      UserAccountExample.snapshot(4, "Dora"));
                }));
    assertEquals(Optional.of("Dora"), observe(c, evidence, 8, false, restore));
    assertReplayStart(c, 8, 5);
    DynamoDbRequestRecorder.Request read = requests(c, 8).get(0);
    assertEquals("3", read.effectiveResponse.at("/Responses/" + c.head + "/0/seq_nr/N").asText());
    assertEquals(
        "4", read.effectiveResponse.at("/Responses/" + c.snapshot + "/0/seq_nr/N").asText());
    evidence
        .putObject("read_interleave_fault")
        .put("applications", c.faults.applications(fault))
        .put("reservations", c.faults.reservations(fault));
    assertEquals(1, c.faults.applications(fault));
    assertEquals(0, c.faults.reservations(fault));
  }

  private static void saveAndClose(DynamoDbTestContext c, ObjectNode evidence) throws Exception {
    DynamoDbConfigurationFixture.close(c);
    evidence.put("resources_closed", c.closed());
    evidence.set("tables", DynamoDbJson.sdk(DynamoDbConfigurationTables.names(c)));
    Path directory = Path.of("build/reports/public-api-usage");
    Files.createDirectories(directory);
    DynamoDbJson.write(directory.resolve(evidence.path("path").asText() + ".json"), evidence);
    fixture.releaseClosedContext(c);
  }
}
