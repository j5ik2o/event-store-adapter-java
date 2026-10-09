package com.github.j5ik2o.event.store.adapter.java.conformance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 適合テストデータの実行結果の報告（設計文書 5.7）。report.json と summary.md を作る。 */
final class ConformanceReport {

  private final String dataVersion;
  private final ManifestVerifier.Result manifest;
  private final String implementationVersion;
  private final String implementationCommit;
  private final List<CaseResult> results;
  private final JsonNode ruleExclusions;

  ConformanceReport(
      String dataVersion,
      ManifestVerifier.Result manifest,
      String implementationVersion,
      String implementationCommit,
      List<CaseResult> results,
      JsonNode ruleExclusions) {
    this.dataVersion = dataVersion;
    this.manifest = manifest;
    this.implementationVersion = implementationVersion;
    this.implementationCommit = implementationCommit;
    this.results = List.copyOf(results);
    this.ruleExclusions = ruleExclusions;
  }

  /** CI が渡す GITHUB_SHA からコミットを決める。未設定・空白だけなら null。 */
  static String commitFrom(Map<String, String> env) {
    String sha = env.get("GITHUB_SHA");
    return sha == null || sha.isBlank() ? null : sha.trim();
  }

  ObjectNode toJson() {
    JsonNodeFactory f = JsonNodeFactory.instance;
    ObjectNode root = f.objectNode();
    root.put("data_version", dataVersion);
    ObjectNode manifestNode = root.putObject("manifest");
    manifestNode.put("result", manifest.ok() ? "ok" : "mismatch");
    manifestNode.put("file_count", manifest.fileCount());
    if (manifest.manifestVersion() == null) {
      manifestNode.putNull("version");
    } else {
      manifestNode.put("version", manifest.manifestVersion());
    }
    manifestNode.put("expected_version", ManifestVerifier.DATA_VERSION);
    manifestNode.put("version_matches", manifest.versionMatches());
    ArrayNode mismatches = manifestNode.putArray("mismatches");
    manifest.mismatches().forEach(mismatches::add);
    root.put("language", "java");
    root.put("implementation_version", implementationVersion);
    if (implementationCommit == null) {
      root.putNull("implementation_commit");
    } else {
      root.put("implementation_commit", implementationCommit);
    }

    ObjectNode backends = root.putObject("backends");
    for (Map.Entry<Backend, Map<ConformanceStatus, Integer>> entry : counts().entrySet()) {
      ObjectNode counts = backends.putObject(entry.getKey().reportName());
      for (ConformanceStatus status : ConformanceStatus.values()) {
        counts.put(status.label(), entry.getValue().get(status));
      }
    }
    ObjectNode targets = root.putObject("target_cases");
    ObjectNode reasons = root.putObject("reasons");
    for (Backend backend : Backend.values()) {
      ArrayNode ids = targets.putArray(backend.reportName());
      ObjectNode backendReasons = reasons.putObject(backend.reportName());
      for (ConformanceStatus status : ConformanceStatus.values()) {
        Map<String, List<CaseResult>> groups = new LinkedHashMap<>();
        for (CaseResult result : results) {
          if (result.backend() == backend && result.status() == status) {
            if (status != ConformanceStatus.NOT_APPLICABLE
                && status != ConformanceStatus.UNREPRESENTABLE) ids.add(result.caseId());
            if (result.reason() != null)
              groups
                  .computeIfAbsent(result.reason(), ignored -> new java.util.ArrayList<>())
                  .add(result);
          }
        }
        ArrayNode entries = backendReasons.putArray(status.label());
        groups.forEach(
            (reason, casesWithReason) -> {
              ObjectNode entry =
                  entries.addObject().put("reason", reason).put("count", casesWithReason.size());
              ArrayNode caseIds = entry.putArray("case_ids");
              casesWithReason.forEach(result -> caseIds.add(result.caseId()));
            });
      }
    }

    ArrayNode cases = root.putArray("cases");
    ArrayNode rules = root.putArray("rules");
    for (CaseResult r : results) {
      ObjectNode c = cases.addObject();
      c.put("case_id", r.caseId());
      c.put("file", r.file());
      ArrayNode ruleList = c.putArray("rules");
      r.rules().forEach(ruleList::add);
      c.put("backend", r.backend().reportName());
      c.put("status", r.status().label());
      c.put("reason", r.reason());
      if (r.failedOperation() == null) {
        c.putNull("failed_operation");
      } else {
        c.put("failed_operation", r.failedOperation());
      }
      c.set("expected", r.expected() == null ? f.nullNode() : r.expected());
      c.set("actual", r.actual() == null ? f.nullNode() : r.actual());
      for (String rule : r.rules()) {
        ObjectNode entry = rules.addObject();
        entry.put("rule", rule);
        entry.put("case_id", r.caseId());
        entry.put("backend", r.backend().reportName());
        entry.put("status", r.status().label());
        entry.put("reason", r.reason());
      }
    }
    root.set("rule_exclusions", ruleExclusions);
    return root;
  }

  String summary() {
    StringBuilder sb = new StringBuilder();
    sb.append("## Conformance report\n\n");
    sb.append("- data version: ").append(dataVersion).append('\n');
    sb.append("- manifest: ").append(manifest.ok() ? "ok" : "mismatch " + manifest.mismatches());
    sb.append(" (").append(manifest.fileCount()).append(" files)\n");
    sb.append("- manifest version: ")
        .append(manifest.manifestVersion())
        .append(" (expected ")
        .append(ManifestVerifier.DATA_VERSION)
        .append(")\n");
    sb.append("- implementation: java ").append(implementationVersion);
    if (implementationCommit != null) {
      sb.append(" (commit ").append(implementationCommit).append(')');
    }
    sb.append("\n\n");
    sb.append("| backend |");
    for (ConformanceStatus status : ConformanceStatus.values()) {
      sb.append(' ').append(status.label()).append(" |");
    }
    sb.append("\n|---|");
    for (int i = 0; i < ConformanceStatus.values().length; i++) {
      sb.append("---|");
    }
    sb.append('\n');
    for (Map.Entry<Backend, Map<ConformanceStatus, Integer>> entry : counts().entrySet()) {
      sb.append("| ").append(entry.getKey().reportName()).append(" |");
      for (ConformanceStatus status : ConformanceStatus.values()) {
        sb.append(' ').append(entry.getValue().get(status)).append(" |");
      }
      sb.append('\n');
    }
    sb.append("\n### Excluded rules\n\n| rule | status | reason |\n|---|---|---|\n");
    for (JsonNode exclusion : ruleExclusions) {
      sb.append("| ")
          .append(exclusion.path("rule").asText())
          .append(" | ")
          .append(exclusion.path("status").asText())
          .append(" | ")
          .append(exclusion.path("reason").asText().replace("|", "\\|").replace("\n", " "))
          .append(" |\n");
    }
    return sb.toString();
  }

  void write(Path dir) throws IOException {
    Files.createDirectories(dir);
    try (OutputStream output = Files.newOutputStream(dir.resolve("report.json"))) {
      ConformanceJson.mapper().writerWithDefaultPrettyPrinter().writeValue(output, toJson());
    }
    Files.write(dir.resolve("summary.md"), summary().getBytes(StandardCharsets.UTF_8));
  }

  private Map<Backend, Map<ConformanceStatus, Integer>> counts() {
    Map<Backend, Map<ConformanceStatus, Integer>> counts = new EnumMap<>(Backend.class);
    for (Backend backend : Backend.values()) {
      Map<ConformanceStatus, Integer> perStatus = new EnumMap<>(ConformanceStatus.class);
      for (ConformanceStatus status : ConformanceStatus.values()) {
        perStatus.put(status, 0);
      }
      counts.put(backend, perStatus);
    }
    for (CaseResult r : results) {
      counts.get(r.backend()).merge(r.status(), 1, Integer::sum);
    }
    return counts;
  }
}
