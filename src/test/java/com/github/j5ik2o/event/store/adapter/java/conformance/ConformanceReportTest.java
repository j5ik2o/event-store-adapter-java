package com.github.j5ik2o.event.store.adapter.java.conformance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
            "abc123",
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
    assertEquals(15, findCount(json, "memory", "passed"));
    assertEquals(15, findCount(json, "dynamodb-local", "passed"));
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

    JsonNode unverified = caseEntry(cases, "occurred-at-min", "memory");
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

  @Test
  void ruleExclusionsAreReportedWithReasonsInJsonAndSummary() {
    JsonNode exclusions = report.toJson().get("rule_exclusions");
    Set<String> rules = new HashSet<>();
    for (JsonNode e : exclusions) {
      rules.add(e.get("rule").asText());
      assertFalse(e.get("reason").asText().isEmpty());
    }
    assertEquals(Set.of("W-5", "R-7"), rules);

    String summary = report.summary();
    assertTrue(summary.contains("| W-5 | deleted |"), summary);
    assertTrue(summary.contains("| R-7 | caller-obligation |"), summary);
    assertTrue(summary.contains("番号を再利用しない"), summary);
  }

  @Test
  void implementationCommitAppearsInJsonAndSummary() {
    assertEquals("abc123", report.toJson().get("implementation_commit").asText());
    assertTrue(report.summary().contains("(commit abc123)"), report.summary());
  }

  @Test
  void missingCommitIsJsonNull() throws IOException {
    ConformanceReport noCommit =
        new ConformanceReport(
            "1.0.0",
            ManifestVerifier.verify(ConformanceTestFiles.REAL_ROOT),
            "2.0.0-SNAPSHOT",
            null,
            List.of(),
            ConformanceDataLoader.load(ConformanceTestFiles.REAL_ROOT).coverageExclusions());

    assertTrue(noCommit.toJson().get("implementation_commit").isNull());
    assertFalse(noCommit.summary().contains("commit"));
  }

  @Test
  void commitIsTakenFromGithubShaOnly() {
    assertEquals("abc", ConformanceReport.commitFrom(Map.of("GITHUB_SHA", " abc ")));
    assertNull(ConformanceReport.commitFrom(Map.of()));
    assertNull(ConformanceReport.commitFrom(Map.of("GITHUB_SHA", "  ")));
  }

  @Test
  void manifestVersionIsReportedAsReadAndComparedSeparately(@TempDir Path tempDir)
      throws IOException {
    Path copy = ConformanceTestFiles.copyOfRealData(tempDir);
    ConformanceTestFiles.replaceInFile(
        copy.resolve("manifest.json"), "\"version\": \"1.0.0\"", "\"version\": \"9.9.9\"");
    ConformanceReport tampered =
        new ConformanceReport(
            "1.0.0",
            ManifestVerifier.verify(copy),
            "2.0.0-SNAPSHOT",
            null,
            List.of(),
            report.toJson().get("rule_exclusions"));

    JsonNode manifest = tampered.toJson().get("manifest");
    assertEquals("9.9.9", manifest.get("version").asText());
    assertEquals("1.0.0", manifest.get("expected_version").asText());
    assertFalse(manifest.get("version_matches").asBoolean());
    assertEquals("1.0.0", report.toJson().at("/manifest/version").asText());
    assertTrue(report.toJson().at("/manifest/version_matches").asBoolean());
  }
}
