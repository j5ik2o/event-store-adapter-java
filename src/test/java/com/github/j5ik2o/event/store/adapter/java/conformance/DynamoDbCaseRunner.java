package com.github.j5ik2o.event.store.adapter.java.conformance;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.j5ik2o.event.store.adapter.java.dynamodbtest.DynamoDbConfigurationFixture;
import com.github.j5ik2o.event.store.adapter.java.dynamodbtest.DynamoDbEventReadFixture;
import java.util.Set;

/** Connects configuration, partial layout and the verified event-read cases to real Local. */
final class DynamoDbCaseRunner {
  private DynamoDbCaseRunner() {}

  private static final Set<String> READ_CASE_IDS =
      Set.of("core-time-roundtrip-min", "core-time-roundtrip-max", "core-json-root-values");

  static boolean supports(ConformanceCase c) {
    return c.file().equals("dynamodb/configuration.json")
        || c.file().equals("dynamodb/layout.json")
        || READ_CASE_IDS.contains(c.id());
  }

  static CaseResult run(ConformanceCase c, DynamoDbConfigurationFixture fixture) {
    if (!supports(c)) throw new IllegalArgumentException("Unsupported DynamoDB case: " + c.id());
    ObjectNode actual = ConformanceJson.mapper().createObjectNode();
    try {
      boolean layout = c.format().equals("layout");
      if (READ_CASE_IDS.contains(c.id())) {
        new DynamoDbEventReadFixture(fixture).execute(c.materialized(), actual);
        if (actual.has("unsupported"))
          return new CaseResult(
              c.id(),
              c.file(),
              c.rules(),
              Backend.DYNAMODB,
              ConformanceStatus.UNVERIFIED,
              actual.path("unsupported").asText(),
              null,
              null,
              actual);
      } else if (layout) fixture.layout(c.materialized(), actual);
      else fixture.configuration(c.materialized(), actual);
      return new CaseResult(
          c.id(),
          c.file(),
          c.rules(),
          Backend.DYNAMODB,
          layout ? ConformanceStatus.UNVERIFIED : ConformanceStatus.PASSED,
          layout ? "3テーブル・索引・Streams・TTL・設定項目を実確認。全データ項目形状と操作本体は未検証" : null,
          null,
          null,
          actual);
    } catch (RuntimeException | AssertionError failure) {
      return new CaseResult(
          c.id(),
          c.file(),
          c.rules(),
          Backend.DYNAMODB,
          ConformanceStatus.FAILED,
          failure.toString(),
          actual.has("failed_operation") ? actual.path("failed_operation").intValue() : 0,
          c.materialized(),
          actual);
    }
  }
}
