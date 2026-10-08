package com.github.j5ik2o.event.store.adapter.java.conformance;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.j5ik2o.event.store.adapter.java.dynamodbtest.DynamoDbConfigurationFixture;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
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

  @Test
  void supportsOnlyConfigurationAndPartialLayout() throws IOException {
    assertTrue(DynamoDbCaseRunner.supports(find("dynamodb-config-new")));
    assertTrue(DynamoDbCaseRunner.supports(find("dynamodb-layout-v1")));
    assertFalse(DynamoDbCaseRunner.supports(find("occurred-at-min")));
    assertThrows(
        IllegalArgumentException.class,
        () -> DynamoDbCaseRunner.run(find("occurred-at-min"), fixture));
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
