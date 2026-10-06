package com.github.j5ik2o.event.store.adapter.java.conformance;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

/** 読み込んだ適合テストデータ全体。 */
final class ConformanceData {

  private final String dataVersion;
  private final List<ConformanceCase> cases;
  private final JsonNode coverageExclusions;

  ConformanceData(String dataVersion, List<ConformanceCase> cases, JsonNode coverageExclusions) {
    this.dataVersion = dataVersion;
    this.cases = List.copyOf(cases);
    this.coverageExclusions = coverageExclusions;
  }

  String dataVersion() {
    return dataVersion;
  }

  List<ConformanceCase> cases() {
    return cases;
  }

  JsonNode coverageExclusions() {
    return coverageExclusions;
  }
}
