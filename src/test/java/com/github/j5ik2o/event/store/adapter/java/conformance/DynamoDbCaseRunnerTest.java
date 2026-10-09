package com.github.j5ik2o.event.store.adapter.java.conformance;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.j5ik2o.event.store.adapter.java.dynamodbtest.DynamoDbConfigurationFixture;
import com.github.j5ik2o.event.store.adapter.java.dynamodbtest.DynamoDbEventReadFixture;
import com.github.j5ik2o.event.store.adapter.java.dynamodbtest.DynamoDbSnapshotReadFixture;
import com.github.j5ik2o.event.store.adapter.java.dynamodbtest.DynamoDbSnapshotRetentionFixture;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.extension.RegisterExtension;

class DynamoDbCaseRunnerTest {
  @RegisterExtension
  static final DynamoDbConfigurationFixture fixture = new DynamoDbConfigurationFixture();

  private static ConformanceCase find(String id) throws IOException {
    return ConformanceDataLoader.load(ConformanceTestFiles.REAL_ROOT).cases().stream()
        .filter(c -> c.id().equals(id))
        .findFirst()
        .orElseThrow();
  }

  @TestFactory
  Stream<DynamicTest> retentionCandidatesExecuteEveryExpectationAndObservation()
      throws IOException {
    List<ConformanceCase> cases =
        ConformanceDataLoader.load(ConformanceTestFiles.REAL_ROOT).cases();
    return DynamoDbCaseRunner.RETENTION_CASE_IDS.stream()
        .sorted()
        .map(
            id ->
                DynamicTest.dynamicTest(
                    id,
                    () -> {
                      ConformanceCase c =
                          cases.stream()
                              .filter(value -> value.id().equals(id))
                              .findFirst()
                              .orElseThrow();
                      ObjectNode actual = ConformanceJson.mapper().createObjectNode();
                      ObjectNode receipt =
                          ConformanceJson.mapper().createObjectNode().put("case_id", id);
                      try {
                        CaseResult result = DynamoDbCaseRunner.run(c, fixture);
                        actual.setAll((ObjectNode) result.actual());
                        assertEquals(
                            ConformanceStatus.PASSED,
                            result.status(),
                            () -> String.valueOf(result.reason()));
                        assertFalse(actual.has("unsupported"), () -> actual.toString());
                        receipt.put("status", "passed");
                        for (String path : List.of("sync", "async")) {
                          assertTrue(actual.path(path).path("resources_closed").booleanValue());
                          if (!c.materialized().has("initialization")) {
                            assertEquals(
                                c.materialized().path("steps").size() + 1,
                                actual.path(path).path("operations").size());
                            for (JsonNode operation : actual.path(path).path("operations")) {
                              assertTrue(operation.path("request_terminal").booleanValue());
                              assertEquals(0, operation.path("pending").intValue());
                              assertEquals("passed", operation.path("fault_result").asText());
                            }
                          }
                        }
                      } catch (RuntimeException | AssertionError failure) {
                        receipt.put("status", "failed").put("reason", failure.toString());
                        throw failure;
                      } finally {
                        receipt.set("actual", actual);
                        Path directory = Path.of("build/reports/dynamodb-retention-candidates");
                        Files.createDirectories(directory);
                        Files.writeString(
                            directory.resolve(id + ".json"), receipt.toPrettyString());
                      }
                    }));
  }

  @TestFactory
  Stream<DynamicTest> snapshotCandidatesExecuteEveryOperationExpectationAndObservation()
      throws IOException {
    List<ConformanceCase> cases =
        ConformanceDataLoader.load(ConformanceTestFiles.REAL_ROOT).cases();
    return DynamoDbCaseRunner.SNAPSHOT_CASE_IDS.stream()
        .sorted()
        .map(
            id ->
                DynamicTest.dynamicTest(
                    id,
                    () -> {
                      ConformanceCase c =
                          cases.stream()
                              .filter(value -> value.id().equals(id))
                              .findFirst()
                              .orElseThrow();
                      CaseResult result = DynamoDbCaseRunner.run(c, fixture);
                      Path directory = Path.of("build/reports/dynamodb-snapshot-read-candidates");
                      Files.createDirectories(directory);
                      ObjectNode receipt =
                          ConformanceJson.mapper()
                              .createObjectNode()
                              .put("case_id", id)
                              .put("status", result.status().label());
                      receipt.set("actual", result.actual());
                      if (result.reason() != null) receipt.put("reason", result.reason());
                      Files.writeString(directory.resolve(id + ".json"), receipt.toPrettyString());
                      assertEquals(
                          ConformanceStatus.PASSED,
                          result.status(),
                          () -> String.valueOf(result.reason()));
                      for (String sdk : List.of("sync", "async")) {
                        JsonNode actual = result.actual().path(sdk);
                        assertEquals(
                            c.materialized().path("steps").size() + 1,
                            actual.path("operations").size());
                        for (JsonNode operation : actual.path("operations")) {
                          assertTrue(operation.path("request_terminal").booleanValue());
                          assertEquals(0, operation.path("pending").intValue());
                          assertEquals("passed", operation.path("fault_result").asText());
                        }
                        assertTrue(actual.path("resources_closed").booleanValue());
                        for (int index = 0;
                            index < c.materialized().path("steps").size();
                            index++) {
                          JsonNode error =
                              c.materialized().path("steps").get(index).at("/expect/error");
                          if (error.has("rule"))
                            assertEquals(
                                error.path("rule"),
                                actual.path("operations").get(index + 1).at("/outcome/error/rule"));
                        }
                      }
                    }));
  }

  @TestFactory
  Stream<DynamicTest> snapshotErrorRuleMismatchFailsOnBothSdkPaths() throws IOException {
    List<ConformanceCase> cases =
        ConformanceDataLoader.load(ConformanceTestFiles.REAL_ROOT).cases();
    return cases.stream()
        .filter(c -> DynamoDbCaseRunner.SNAPSHOT_CASE_IDS.contains(c.id()))
        .flatMap(
            c ->
                IntStream.range(0, c.materialized().path("steps").size())
                    .filter(
                        index ->
                            c.materialized()
                                .path("steps")
                                .get(index)
                                .at("/expect/error")
                                .has("rule"))
                    .mapToObj(
                        index ->
                            DynamicTest.dynamicTest(
                                c.id(),
                                () -> {
                                  JsonNode expectedError =
                                      c.materialized().path("steps").get(index).at("/expect/error");
                                  String rule = expectedError.path("rule").asText();
                                  ObjectNode altered = c.materialized().deepCopy();
                                  ((ObjectNode)
                                          altered.path("steps").get(index).at("/expect/error"))
                                      .put("rule", rule.equals("W-9") ? "W-8" : "W-9");
                                  CaseResult result =
                                      DynamoDbCaseRunner.run(
                                          new ConformanceCase(
                                              c.id(),
                                              c.file(),
                                              c.format(),
                                              c.rules(),
                                              c.raw(),
                                              altered),
                                          fixture);
                                  ConformanceData data =
                                      ConformanceDataLoader.load(ConformanceTestFiles.REAL_ROOT);
                                  Path directory =
                                      Path.of(
                                          "build/reports/dynamodb-snapshot-rule-failures", c.id());
                                  new ConformanceReport(
                                          data.dataVersion(),
                                          ManifestVerifier.verify(ConformanceTestFiles.REAL_ROOT),
                                          "2.0.0-SNAPSHOT",
                                          null,
                                          List.of(result),
                                          data.coverageExclusions())
                                      .write(directory);
                                  assertEquals(ConformanceStatus.FAILED, result.status());
                                  assertEquals(index + 1, result.failedOperation());
                                  for (String sdk : List.of("sync", "async")) {
                                    JsonNode actual = result.actual().path(sdk);
                                    assertEquals(
                                        index + 1, actual.path("failed_operation").intValue());
                                    JsonNode operation = actual.path("operations").get(index + 1);
                                    assertEquals(
                                        expectedError.path("category"),
                                        operation.at("/outcome/error/category"));
                                    assertEquals(
                                        rule, operation.at("/outcome/error/rule").asText());
                                    assertTrue(operation.path("request_terminal").booleanValue());
                                    assertEquals(0, operation.path("pending").intValue());
                                    assertTrue(actual.path("resources_closed").booleanValue());
                                  }
                                  JsonNode reported =
                                      ConformanceJson.mapper()
                                          .readTree(
                                              Files.readAllBytes(directory.resolve("report.json")))
                                          .at("/cases/0");
                                  assertEquals("failed", reported.path("status").asText());
                                  assertEquals(
                                      index + 1, reported.path("failed_operation").intValue());
                                  assertEquals(altered, reported.path("expected"));
                                  assertEquals(
                                      ConformanceJson.mapper().readTree(result.actual().toString()),
                                      reported.path("actual"));
                                })));
  }

  @Test
  void snapshotMismatchReportsSavedPayloadAndBothSdkPathsAtActualFailedOperation()
      throws IOException {
    ConformanceCase c = find("core-snapshot-behind-head");
    ObjectNode altered = c.materialized().deepCopy();
    ((ObjectNode) altered.at("/steps/3/expect")).put("head_seq_nr", 99);
    CaseResult result =
        DynamoDbCaseRunner.run(
            new ConformanceCase(c.id(), c.file(), c.format(), c.rules(), c.raw(), altered),
            fixture);
    assertEquals(ConformanceStatus.FAILED, result.status());
    assertEquals(4, result.failedOperation());
    for (String sdk : List.of("sync", "async")) {
      JsonNode path = result.actual().path(sdk);
      assertEquals(3, path.at("/operations/4/outcome/head_seq_nr").intValue());
      assertEquals(1, path.at("/operations/4/outcome/snapshot/seq_nr").intValue());
      assertEquals("snapshot-v1", path.at("/operations/4/outcome/snapshot/manifest").asText());
      assertEquals(1, path.at("/operations/4/outcome/snapshot/aggregate/items/0").intValue());
      assertEquals(0, path.at("/operations/4/pending").intValue());
      assertTrue(path.path("resources_closed").booleanValue());
    }
    ConformanceData data = ConformanceDataLoader.load(ConformanceTestFiles.REAL_ROOT);
    Path directory = Path.of("build/reports/dynamodb-snapshot-read-failures", c.id());
    new ConformanceReport(
            data.dataVersion(),
            ManifestVerifier.verify(ConformanceTestFiles.REAL_ROOT),
            "2.0.0-SNAPSHOT",
            null,
            List.of(result),
            data.coverageExclusions())
        .write(directory);
    JsonNode reported =
        ConformanceJson.mapper()
            .readTree(Files.readAllBytes(directory.resolve("report.json")))
            .at("/cases/0");
    assertEquals(4, reported.path("failed_operation").intValue());
    assertEquals("failed", reported.path("status").asText());
    assertEquals(
        ConformanceJson.mapper().readTree(result.actual().toString()), reported.path("actual"));
  }

  @Test
  void unconnectedSnapshotObservationsAndTtlStayUnverified() throws IOException {
    ConformanceCase c = find("core-snapshot-behind-head");
    for (String field : List.of("history", "items", "notifications")) {
      ObjectNode altered = c.materialized().deepCopy();
      ((ObjectNode) altered.at("/steps/3")).putObject("observe").putArray(field);
      CaseResult result =
          DynamoDbCaseRunner.run(
              new ConformanceCase(c.id(), c.file(), c.format(), c.rules(), c.raw(), altered),
              fixture);
      assertEquals(ConformanceStatus.UNVERIFIED, result.status());
      assertTrue(result.reason().contains(field));
      assertFalse(result.actual().has("sync"));
    }
    ConformanceCase retention = find("dynamodb-retention-ttl-once");
    assertNotNull(DynamoDbSnapshotReadFixture.unsupported(retention.materialized()));
    assertFalse(DynamoDbCaseRunner.supports(retention));
    assertEquals(
        ConformanceStatus.UNVERIFIED,
        CaseClassifier.classify(retention, Backend.DYNAMODB).status());
    assertFalse(RequiredCases.load().get(Backend.DYNAMODB).contains(retention.id()));
  }

  @Test
  void unconnectedRetentionObservationsAndConstraintsStayUnverifiedBeforeCreatingResources()
      throws IOException {
    ConformanceCase c = find("core-retention-delete-1");
    for (String field : List.of("items", "unknown-observation")) {
      ObjectNode altered = c.materialized().deepCopy();
      ((ObjectNode) altered.at("/steps/0/observe")).putArray(field);
      CaseResult result =
          DynamoDbCaseRunner.run(
              new ConformanceCase(c.id(), c.file(), c.format(), c.rules(), c.raw(), altered),
              fixture);
      assertEquals(ConformanceStatus.UNVERIFIED, result.status());
      assertTrue(result.reason().contains(field));
      assertFalse(result.actual().has("sync"));
    }
    ObjectNode altered =
        find("dynamodb-retention-delete-paginated-batch").materialized().deepCopy();
    ((ObjectNode) altered.at("/steps/0/observe/requests/0/constraints"))
        .put("unknown-constraint", true);
    assertTrue(
        DynamoDbSnapshotRetentionFixture.unsupported(altered).contains("unknown-constraint"));
  }

  @TestFactory
  Stream<DynamicTest> wrongRetentionExpectationsFailOnBothPathsWithActualObservations() {
    return Stream.of(
            "history",
            "notifications",
            "initial_batch_sizes",
            "scan_index_forward",
            "request_count",
            "error.rule")
        .map(
            field ->
                DynamicTest.dynamicTest(
                    field,
                    () -> {
                      ConformanceCase c =
                          find(
                              field.equals("notifications")
                                  ? "core-retention-query-failure"
                                  : field.equals("history")
                                      ? "core-retention-delete-1"
                                      : field.equals("error.rule")
                                          ? "core-retention-zero"
                                          : "dynamodb-retention-delete-paginated-batch");
                      ObjectNode altered = c.materialized().deepCopy();
                      int operation =
                          field.equals("error.rule") ? 0 : field.equals("notifications") ? 2 : 1;
                      switch (field) {
                        case "history":
                          ((ObjectNode) altered.at("/steps/0/observe/history"))
                              .putArray("active")
                              .add(99);
                          break;
                        case "notifications":
                          ((ObjectNode) altered.at("/steps/1/observe")).putArray("notifications");
                          break;
                        case "initial_batch_sizes":
                          ((ObjectNode) altered.at("/steps/0/observe/requests/1/constraints"))
                              .putArray("initial_batch_sizes")
                              .add(26)
                              .add(4);
                          break;
                        case "scan_index_forward":
                          ((ObjectNode) altered.at("/steps/0/observe/requests/0/constraints"))
                              .put("scan_index_forward", true);
                          break;
                        case "request_count":
                          ((ObjectNode) altered.at("/steps/0/observe"))
                              .putObject("request_count")
                              .put("retention-query", 1);
                          break;
                        case "error.rule":
                          ((ObjectNode) altered.at("/initialization/expect/error"))
                              .put("rule", "W-9");
                          break;
                        default:
                          fail("Unknown expectation");
                      }
                      CaseResult result =
                          DynamoDbCaseRunner.run(
                              new ConformanceCase(
                                  c.id(), c.file(), c.format(), c.rules(), c.raw(), altered),
                              fixture);
                      assertEquals(ConformanceStatus.FAILED, result.status());
                      assertEquals(operation, result.failedOperation());
                      for (String sdk : List.of("sync", "async")) {
                        assertEquals(
                            operation,
                            result.actual().path(sdk).path("failed_operation").intValue());
                        assertTrue(
                            result.actual().path(sdk).path("resources_closed").booleanValue());
                      }
                      Path directory = Path.of("build/reports/dynamodb-retention-failures", field);
                      ConformanceData data =
                          ConformanceDataLoader.load(ConformanceTestFiles.REAL_ROOT);
                      new ConformanceReport(
                              data.dataVersion(),
                              ManifestVerifier.verify(ConformanceTestFiles.REAL_ROOT),
                              "2.0.0-SNAPSHOT",
                              null,
                              List.of(result),
                              data.coverageExclusions())
                          .write(directory);
                    }));
  }

  @TestFactory
  Stream<DynamicTest> readCandidatesExecuteAllOperationsAndObservations() throws IOException {
    List<ConformanceCase> cases =
        ConformanceDataLoader.load(ConformanceTestFiles.REAL_ROOT).cases();
    return Stream.of("core-time-roundtrip-min", "core-time-roundtrip-max", "core-json-root-values")
        .map(
            id ->
                DynamicTest.dynamicTest(
                    id,
                    () -> {
                      ConformanceCase c =
                          cases.stream()
                              .filter(value -> value.id().equals(id))
                              .findFirst()
                              .orElseThrow();
                      ObjectNode actual = ConformanceJson.mapper().createObjectNode();
                      new DynamoDbEventReadFixture(fixture).execute(c.materialized(), actual);
                      assertFalse(actual.has("unsupported"));
                      for (String sdk : List.of("sync", "async")) {
                        assertEquals(
                            c.materialized().path("steps").size() + 1,
                            actual.at("/" + sdk + "/operations").size());
                        assertTrue(actual.at("/" + sdk + "/resources_closed").booleanValue());
                      }
                      Path directory = Path.of("build/reports/dynamodb-event-read-candidates");
                      Files.createDirectories(directory);
                      Files.writeString(directory.resolve(id + ".json"), actual.toPrettyString());
                    }));
  }

  @Test
  void paginationCandidateRecordsUnmetQueryCountOnBothActualSdkPaths() throws IOException {
    ConformanceCase c = find("dynamodb-events-over-one-megabyte");
    ObjectNode actual = ConformanceJson.mapper().createObjectNode();
    AssertionError failure =
        assertThrows(
            AssertionError.class,
            () -> new DynamoDbEventReadFixture(fixture).execute(c.materialized(), actual));
    assertEquals(5, actual.path("failed_operation").intValue());
    for (String sdk : List.of("sync", "async")) {
      JsonNode path = actual.path(sdk);
      assertTrue(path.path("resources_closed").booleanValue());
      assertEquals(6, path.path("operations").size());
      JsonNode read = path.at("/operations/5");
      assertEquals(4, read.path("events").size());
      assertEquals(1, read.path("requests").size());
      assertEquals(1, read.path("responses").size());
      assertFalse(read.at("/responses/0").has("LastEvaluatedKey"));
      assertEquals(0, read.path("pending").intValue());
      assertTrue(read.path("request_terminal").booleanValue());
    }
    assertFalse(DynamoDbCaseRunner.supports(c));
    assertEquals(
        ConformanceStatus.UNVERIFIED, CaseClassifier.classify(c, Backend.DYNAMODB).status());
    assertFalse(RequiredCases.load().get(Backend.DYNAMODB).contains(c.id()));
    ObjectNode result = ConformanceJson.mapper().createObjectNode();
    result
        .put("case_id", c.id())
        .put("status", "failed")
        .put("reason", failure.toString())
        .put("failed_operation", 5);
    result.set("expected", c.materialized());
    result.set("actual", actual);
    Path directory = Path.of("build/reports/dynamodb-event-read-candidates");
    Files.createDirectories(directory);
    Files.writeString(directory.resolve(c.id() + ".json"), result.toPrettyString());
  }

  @Test
  void supportsOnlyConfigurationPartialLayoutAndSelectedReadCases() throws IOException {
    assertTrue(DynamoDbCaseRunner.supports(find("dynamodb-config-new")));
    assertTrue(DynamoDbCaseRunner.supports(find("dynamodb-layout-v1")));
    assertTrue(DynamoDbCaseRunner.supports(find("core-time-roundtrip-min")));
    assertTrue(DynamoDbCaseRunner.supports(find("core-time-roundtrip-max")));
    assertTrue(DynamoDbCaseRunner.supports(find("core-json-root-values")));
    assertTrue(DynamoDbCaseRunner.supports(find("dynamodb-latest-unprocessed")));
    assertTrue(DynamoDbCaseRunner.supports(find("dynamodb-snapshot-ahead-of-head")));
    assertTrue(DynamoDbCaseRunner.supports(find("dynamodb-no-head-with-snapshot")));
    assertFalse(DynamoDbCaseRunner.supports(find("occurred-at-min")));
    assertThrows(
        IllegalArgumentException.class,
        () -> DynamoDbCaseRunner.run(find("occurred-at-min"), fixture));
  }

  @Test
  void readMismatchReportsActualFailedOperationAndRealReturnedEnvelope() throws IOException {
    ConformanceCase c = find("core-time-roundtrip-min");
    ObjectNode altered = c.materialized().deepCopy();
    ((ObjectNode) altered.at("/steps/1/expect")).put("result", "none");
    CaseResult result =
        DynamoDbCaseRunner.run(
            new ConformanceCase(c.id(), c.file(), c.format(), c.rules(), c.raw(), altered),
            fixture);
    assertEquals(ConformanceStatus.FAILED, result.status());
    assertEquals(2, result.failedOperation());
    JsonNode operation = result.actual().at("/sync/operations/2");
    assertEquals("events", operation.at("/outcome/result").asText());
    assertEquals("1677-09-21T00:12:43.145224192Z", operation.at("/events/0/occurred_at").asText());
    assertEquals(1, operation.path("requests").size());
    assertEquals(0, operation.path("pending").intValue());
    assertTrue(result.actual().at("/sync/resources_closed").booleanValue());
    ConformanceData data = ConformanceDataLoader.load(ConformanceTestFiles.REAL_ROOT);
    Path directory = Path.of("build/reports/dynamodb-event-read-failures", c.id());
    new ConformanceReport(
            data.dataVersion(),
            ManifestVerifier.verify(ConformanceTestFiles.REAL_ROOT),
            "2.0.0-SNAPSHOT",
            null,
            List.of(result),
            data.coverageExclusions())
        .write(directory);
    JsonNode reported =
        ConformanceJson.mapper()
            .readTree(Files.readAllBytes(directory.resolve("report.json")))
            .at("/cases/0");
    assertEquals(2, reported.path("failed_operation").intValue());
    assertEquals("failed", reported.path("status").asText());
    assertEquals(
        ConformanceJson.mapper().readTree(result.actual().toString()), reported.path("actual"));
  }

  @Test
  void unconnectedReadObservationRemainsUnverified() throws IOException {
    ConformanceCase c = find("core-time-roundtrip-max");
    ObjectNode altered = c.materialized().deepCopy();
    ((ObjectNode) altered.at("/steps/1")).putObject("observe").putArray("notifications");
    CaseResult result =
        DynamoDbCaseRunner.run(
            new ConformanceCase(c.id(), c.file(), c.format(), c.rules(), c.raw(), altered),
            fixture);
    assertEquals(ConformanceStatus.UNVERIFIED, result.status());
    assertTrue(result.reason().contains("notifications"));
    assertNull(result.failedOperation());
    assertFalse(result.actual().has("sync"));
  }

  @Test
  void mismatchedExpectedOutcomeIsFailed() throws IOException {
    ConformanceCase c = find("dynamodb-config-matching");
    ObjectNode altered = c.materialized().deepCopy();
    ((ObjectNode) altered.path("initialization"))
        .putObject("expect")
        .putObject("error")
        .put("category", "storage");
    ConformanceCase changed =
        new ConformanceCase(c.id(), c.file(), c.format(), c.rules(), c.raw(), altered);
    CaseResult result = DynamoDbCaseRunner.run(changed, fixture);
    assertEquals(ConformanceStatus.FAILED, result.status());
    assertEquals(0, result.failedOperation());
    assertTrue(result.reason().contains("AssertionFailedError"));
    JsonNode actual = result.actual().path("sync");
    assertEquals("success", actual.path("result").asText());
    assertEquals(1, actual.path("requests").size());
    assertEquals("BatchGetItem", actual.at("/requests/0/api").asText());
    assertEquals(3, actual.at("/requests/0/transmitted/RequestItems").size());
    for (String role : List.of("journal", "snapshot", "head")) {
      assertEquals("store-a", actual.at("/items/" + role + "/store_id/S").asText());
      assertEquals("1", actual.at("/items/" + role + "/layout_version/N").asText());
    }
    assertEquals(0, actual.path("pending").intValue());
    assertTrue(actual.path("resources_closed").booleanValue());
    assertFalse(result.actual().has("async"));
    assertFailureIsReportedWithObservations(result, altered);
  }

  @Test
  void failedPartialLayoutCannotBeReportedAsUnverifiedOrPassed() throws IOException {
    ConformanceCase c = find("dynamodb-layout-v1");
    ObjectNode altered = c.materialized().deepCopy();
    ((ObjectNode) altered.at("/tables/0/partition_key")).put("type", "N");
    ConformanceCase changed =
        new ConformanceCase(c.id(), c.file(), c.format(), c.rules(), c.raw(), altered);
    CaseResult result = DynamoDbCaseRunner.run(changed, fixture);
    assertEquals(ConformanceStatus.FAILED, result.status());
    assertTrue(result.reason().contains("AssertionFailedError"));
    JsonNode actual = result.actual().path("none");
    JsonNode attributes = actual.at("/journal/describe_table/AttributeDefinitions");
    assertTrue(attributes.isArray());
    JsonNode aid = null;
    for (JsonNode attribute : attributes)
      if (attribute.path("AttributeName").asText().equals("aid")) aid = attribute;
    assertNotNull(aid);
    assertEquals("S", aid.path("AttributeType").asText());
    assertEquals("DISABLED", actual.at("/journal/describe_ttl/TimeToLiveStatus").asText());
    assertEquals(2, actual.path("requests").size());
    assertEquals(3, actual.path("configuration_items").size());
    assertFalse(actual.at("/configuration_items/journal/store_id/S").asText().isBlank());
    assertTrue(actual.path("resources_closed").booleanValue());
    assertFalse(result.actual().has("delete"));
    assertFalse(result.actual().has("ttl"));
    assertFailureIsReportedWithObservations(result, altered);
  }

  private static void assertFailureIsReportedWithObservations(
      CaseResult result, ObjectNode expected) throws IOException {
    ConformanceData data = ConformanceDataLoader.load(ConformanceTestFiles.REAL_ROOT);
    ConformanceReport report =
        new ConformanceReport(
            data.dataVersion(),
            ManifestVerifier.verify(ConformanceTestFiles.REAL_ROOT),
            "2.0.0-SNAPSHOT",
            null,
            List.of(result),
            data.coverageExclusions());
    Path directory = Path.of("build/reports/dynamodb-configuration-failures", result.caseId());
    report.write(directory);
    JsonNode entry =
        ConformanceJson.mapper()
            .readTree(Files.readAllBytes(directory.resolve("report.json")))
            .at("/cases/0");
    assertEquals("failed", entry.path("status").asText());
    assertEquals(0, entry.path("failed_operation").intValue());
    assertEquals(result.reason(), entry.path("reason").asText());
    assertEquals(expected, entry.path("expected"));
    assertEquals(
        ConformanceJson.mapper().readTree(result.actual().toString()), entry.path("actual"));
    assertFalse(entry.path("actual").isEmpty());
  }
}
