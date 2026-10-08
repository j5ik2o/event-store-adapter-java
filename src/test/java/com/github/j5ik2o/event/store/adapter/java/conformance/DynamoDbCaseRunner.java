package com.github.j5ik2o.event.store.adapter.java.conformance;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.j5ik2o.event.store.adapter.java.dynamodbtest.DynamoDbConfigurationFixture;

/** Connects only configuration and partial layout verification to the real Local fixture. */
final class DynamoDbCaseRunner {
  private DynamoDbCaseRunner() {}

  static boolean supports(ConformanceCase c) {
    return c.file().equals("dynamodb/configuration.json")
        || c.file().equals("dynamodb/layout.json");
  }

  static CaseResult run(ConformanceCase c, DynamoDbConfigurationFixture fixture) {
    if (!supports(c)) throw new IllegalArgumentException("Unsupported DynamoDB case: " + c.id());
    ObjectNode actual = ConformanceJson.mapper().createObjectNode();
    try {
      boolean layout = c.format().equals("layout");
      if (layout) fixture.layout(c.materialized(), actual);
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
          0,
          c.materialized(),
          actual);
    }
  }
}
