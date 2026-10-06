package com.github.j5ik2o.event.store.adapter.java.conformance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConformanceReportTest {

  private static final String[] STATES = {
    "passed", "failed", "not-applicable", "unverified", "unrepresentable"
  };

  private static ConformanceData data;
  private static ConformanceReport report;

  @BeforeAll
  static void build() throws IOException {
    data = ConformanceDataLoader.load(ConformanceTestFiles.REAL_ROOT);
    List<CaseResult> results = new ArrayList<>();
    for (ConformanceCase c : data.cases()) {
      for (Backend b : Backend.values()) {
        results.add(CaseClassifier.classify(c, b));
      }
    }
    report =
        new ConformanceReport(
            data.dataVersion(),
            ManifestVerifier.verify(ConformanceTestFiles.REAL_ROOT),
            "2.0.0-SNAPSHOT",
            results,
            data.coverageExclusions());
  }

  @Test
  void jsonHasTheRequiredTopLevelFields() {
    JsonNode json = report.toJson();

    assertEquals("1.0.0", json.get("data_version").asText());
    assertTrue(json.at("/manifest/result").isTextual(), json.at("/manifest").toString());
    assertEquals("java", json.get("language").asText());
    assertEquals("2.0.0-SNAPSHOT", json.get("implementation_version").asText());
    assertTrue(json.has("rule_exclusions"));
  }

  @Test
  void everyBackendHasAllFiveStateCountsEvenWhenZero() {
    String text = report.toJson().toString();
    JsonNode json = report.toJson();

    assertTrue(text.contains("dynamodb-local"));
    assertTrue(text.contains("memory"));
    for (String state : STATES) {
      assertTrue(text.contains("\"" + state + "\""), "missing state key: " + state);
    }
    // passed が 0 件でも、キーが出ていること
    assertTrue(findCount(json, "memory", "passed") == 0);
    assertTrue(findCount(json, "dynamodb-local", "passed") == 0);
    assertTrue(findCount(json, "memory", "failed") == 0);
  }

  @Test
  void ruleEntriesAreEmittedPerRuleForACaseWithTwoRules() {
    ConformanceCase multi =
        data.cases().stream()
            .filter(c -> c.id().equals("seq-zero-event"))
            .findFirst()
            .orElseThrow(() -> new AssertionError("seq-zero-event not found"));
    assertTrue(multi.rules().size() >= 2, multi.rules().toString());

    List<String> ruleNames = new ArrayList<>();
    for (JsonNode entry : report.toJson().get("rules")) {
      if (entry.get("case_id").asText().equals(multi.id())
          && entry.get("backend").asText().equals("memory")) {
        ruleNames.add(entry.get("rule").asText());
      }
    }

    assertEquals(multi.rules().size(), ruleNames.size(), ruleNames.toString());
    assertEquals(new HashSet<>(multi.rules()), new HashSet<>(ruleNames));
  }

  @Test
  void caseEntriesCarryStatusAndReason() {
    JsonNode cases = report.toJson().get("cases");

    JsonNode notApplicable = caseEntry(cases, "hash-fnv1a64-1", "memory");
    assertEquals("not-applicable", notApplicable.get("status").asText());
    assertFalse(notApplicable.get("reason").asText().isBlank());

    JsonNode unverified = caseEntry(cases, "seq-zero-value", "memory");
    assertEquals("unverified", unverified.get("status").asText());
    assertFalse(unverified.get("reason").asText().isBlank());
  }

  private static JsonNode caseEntry(JsonNode cases, String caseId, String backend) {
    for (JsonNode entry : cases) {
      if (entry.get("case_id").asText().equals(caseId)
          && entry.get("backend").asText().equals(backend)) {
        return entry;
      }
    }
    throw new AssertionError("case entry not found: " + caseId + "/" + backend);
  }

  @Test
  void summaryIsAMarkdownTable() {
    String summary = report.summary();

    assertFalse(summary.isBlank());
    assertTrue(summary.contains("|"), summary);
    assertTrue(summary.contains("unverified"), summary);
  }

  @Test
  void writeCreatesReportJsonAndSummaryMarkdown(@TempDir Path tempDir) throws IOException {
    Path dir = tempDir.resolve("reports/conformance");

    report.write(dir);

    assertTrue(Files.isRegularFile(dir.resolve("report.json")));
    assertTrue(Files.isRegularFile(dir.resolve("summary.md")));
    JsonNode parsed =
        ConformanceJson.mapper().readTree(Files.readAllBytes(dir.resolve("report.json")));
    assertEquals("java", parsed.get("language").asText());
  }

  /** 保存先名をキーに持つ状態件数を、構造を仮定せずに探す。 */
  private static int findCount(JsonNode root, String backendName, String state) {
    JsonNode node = locate(root, backendName);
    if (node == null) {
      throw new AssertionError("backend not found in report: " + backendName);
    }
    JsonNode counts = locate(node, state);
    if (counts == null || !counts.isNumber()) {
      throw new AssertionError("count not found: " + backendName + "/" + state);
    }
    return counts.asInt();
  }

  private static JsonNode locate(JsonNode node, String key) {
    if (node.has(key)) {
      return node.get(key);
    }
    for (JsonNode child : node) {
      JsonNode found = locate(child, key);
      if (found != null) {
        return found;
      }
    }
    return null;
  }
}
