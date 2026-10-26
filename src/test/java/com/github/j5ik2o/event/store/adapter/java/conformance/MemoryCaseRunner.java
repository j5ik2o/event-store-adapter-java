package com.github.j5ik2o.event.store.adapter.java.conformance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.j5ik2o.event.store.adapter.java.memory.ConformanceMemoryOperations;

/** 実際のメモリ操作の結果を、共通データの期待値と比較する。 */
final class MemoryCaseRunner {
  private MemoryCaseRunner() {}

  static boolean supports(ConformanceCase c) {
    return c.file().startsWith("scenarios/core/")
        || c.file().startsWith("memory/")
        || c.operation().map("validateOccurredAt"::equals).orElse(false);
  }

  static final String REASON_CHANGE_FEED =
      "公開設定（MemoryStorage・EventStoreConfig）に変更フィードを求める項目がなく、change_feed=true を表せない";

  static CaseResult run(ConformanceCase c) {
    return run(c, ConformanceMemoryOperations.StoreOpener.PUBLIC_API);
  }

  static CaseResult run(ConformanceCase c, ConformanceMemoryOperations.StoreOpener opener) {
    return ScenarioComparison.run(
        c,
        Backend.MEMORY,
        REASON_CHANGE_FEED,
        new ScenarioComparison.Execution() {
          public ObjectNode scenario(JsonNode scenario) {
            return ConformanceMemoryOperations.executeScenario(scenario, opener);
          }

          public ObjectNode occurredAt(JsonNode input, String caseId) {
            return ConformanceMemoryOperations.validateOccurredAt(input, caseId);
          }
        });
  }
}
