package com.github.j5ik2o.event.store.adapter.java.conformance;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.j5ik2o.event.store.adapter.java.dynamodbtest.DynamoDbConfigurationFixture;
import com.github.j5ik2o.event.store.adapter.java.dynamodbtest.DynamoDbEventReadFixture;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
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
  void supportsOnlyConfigurationPartialLayoutAndVerifiedReadCases() throws IOException {
    assertTrue(DynamoDbCaseRunner.supports(find("dynamodb-config-new")));
    assertTrue(DynamoDbCaseRunner.supports(find("dynamodb-layout-v1")));
    assertTrue(DynamoDbCaseRunner.supports(find("core-time-roundtrip-min")));
    assertTrue(DynamoDbCaseRunner.supports(find("core-time-roundtrip-max")));
    assertTrue(DynamoDbCaseRunner.supports(find("core-json-root-values")));
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
