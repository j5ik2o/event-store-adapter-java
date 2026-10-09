package com.github.j5ik2o.event.store.adapter.java.conformance;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.j5ik2o.event.store.adapter.java.dynamodbtest.DynamoDbConfigurationFixture;
import com.github.j5ik2o.event.store.adapter.java.dynamodbtest.DynamoDbEventReadFixture;
import com.github.j5ik2o.event.store.adapter.java.dynamodbtest.DynamoDbSnapshotReadFixture;
import com.github.j5ik2o.event.store.adapter.java.dynamodbtest.DynamoDbSnapshotRetentionFixture;
import java.util.Set;

/** Connects configuration, partial layout, verified reads and DELETE retention to real Local. */
final class DynamoDbCaseRunner {
  private DynamoDbCaseRunner() {}

  private static final Set<String> READ_CASE_IDS =
      Set.of("core-time-roundtrip-min", "core-time-roundtrip-max", "core-json-root-values");

  static final Set<String> RETENTION_CASE_IDS =
      Set.of(
          "core-retention-zero",
          "core-retention-delete-1",
          "core-retention-delete-2",
          "core-retention-default-current-only",
          "core-retention-failure-after-commit",
          "core-retention-query-failure",
          "dynamodb-retention-delete-paginated-batch",
          "dynamodb-retention-gsi-missing-new",
          "dynamodb-retention-gsi-deduplicate",
          "dynamodb-retention-event-only",
          "dynamodb-retention-failure-delete");

  static final Set<String> SNAPSHOT_CASE_IDS =
      Set.of(
          "core-serialize-event",
          "core-serialize-snapshot",
          "core-deserialize-snapshot",
          "core-storage-commit-failure",
          "core-storage-read-snapshot",
          "core-replay-without-snapshot",
          "core-snapshot-behind-head",
          "core-default-manifests",
          "core-aggregate-isolation",
          "core-existing-create-event",
          "core-duplicate-update-event",
          "core-stale-update-event",
          "core-gap-event",
          "core-no-head-update-event",
          "core-zero-event",
          "core-existing-create-snapshot",
          "core-duplicate-update-snapshot",
          "core-stale-update-snapshot",
          "core-gap-snapshot",
          "core-no-head-update-snapshot",
          "core-zero-snapshot",
          "core-snapshot-mismatch-0",
          "core-snapshot-mismatch-2",
          "core-time-below-min",
          "core-time-above-max",
          "core-seq-negative",
          "core-seq-above-max",
          "dynamodb-latest-unprocessed",
          "dynamodb-snapshot-ahead-of-head",
          "dynamodb-no-head-with-snapshot");

  static boolean supports(ConformanceCase c) {
    return c.file().equals("dynamodb/configuration.json")
        || c.file().equals("dynamodb/layout.json")
        || READ_CASE_IDS.contains(c.id())
        || SNAPSHOT_CASE_IDS.contains(c.id())
        || RETENTION_CASE_IDS.contains(c.id());
  }

  static CaseResult run(ConformanceCase c, DynamoDbConfigurationFixture fixture) {
    if (!supports(c)) throw new IllegalArgumentException("Unsupported DynamoDB case: " + c.id());
    ObjectNode actual = ConformanceJson.mapper().createObjectNode();
    try {
      boolean layout = c.format().equals("layout");
      if (READ_CASE_IDS.contains(c.id())
          || SNAPSHOT_CASE_IDS.contains(c.id())
          || RETENTION_CASE_IDS.contains(c.id())) {
        if (RETENTION_CASE_IDS.contains(c.id()))
          new DynamoDbSnapshotRetentionFixture(fixture).execute(c.materialized(), actual);
        else if (SNAPSHOT_CASE_IDS.contains(c.id()))
          new DynamoDbSnapshotReadFixture(fixture).execute(c.materialized(), actual);
        else new DynamoDbEventReadFixture(fixture).execute(c.materialized(), actual);
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
