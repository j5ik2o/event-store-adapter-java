package com.github.j5ik2o.event.store.adapter.java.conformance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.j5ik2o.event.store.adapter.java.spanner.ConformanceSpannerOperations;
import com.github.j5ik2o.event.store.adapter.java.spanner.SpannerEmulator;

/** 実際の Spanner エミュレーターで操作を実行し、結果を共通データの期待値と比較する。 */
final class SpannerCaseRunner {
  private SpannerCaseRunner() {}

  static boolean supports(ConformanceCase c) {
    return c.file().startsWith("scenarios/core/")
        || c.file().startsWith("spanner/")
        || c.file().equals("scenarios/stores/shared.json")
        || c.operation().map("validateOccurredAt"::equals).orElse(false);
  }

  static CaseResult run(ConformanceCase c) {
    try {
      SpannerEmulator emulator = SpannerEmulator.shared();
      return ScenarioComparison.run(
          c,
          Backend.SPANNER,
          null,
          new ScenarioComparison.Execution() {
            public ObjectNode scenario(JsonNode scenario) {
              return ConformanceSpannerOperations.executeScenario(scenario, emulator);
            }

            public ObjectNode occurredAt(JsonNode input, String caseId) {
              return ConformanceSpannerOperations.validateOccurredAt(input, caseId, emulator);
            }
          });
    } catch (RuntimeException failure) {
      return new CaseResult(
          c.id(),
          c.file(),
          c.rules(),
          Backend.SPANNER,
          ConformanceStatus.FAILED,
          "実行に失敗した: " + failure,
          null,
          c.materialized(),
          null);
    }
  }
}
