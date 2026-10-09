package com.github.j5ik2o.event.store.adapter.java.conformance;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.j5ik2o.event.store.adapter.java.dynamodbtest.DynamoDbConfigurationFixture;
import com.github.j5ik2o.event.store.adapter.java.dynamodbtest.DynamoDbEventReadFixture;
import com.github.j5ik2o.event.store.adapter.java.dynamodbtest.DynamoDbSnapshotReadFixture;
import com.github.j5ik2o.event.store.adapter.java.dynamodbtest.DynamoDbSnapshotRetentionFixture;
import java.util.Set;

/** Connects applicable cases to the public stores through both real SDK clients. */
final class DynamoDbCaseRunner {
  private DynamoDbCaseRunner() {}

  private static final Set<String> READ_CASE_IDS =
      Set.of(
          "core-time-roundtrip-min",
          "core-time-roundtrip-max",
          "core-json-root-values",
          "dynamodb-events-over-one-megabyte");

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
          "dynamodb-retention-failure-delete",
          "dynamodb-retention-ttl-once",
          "dynamodb-retention-ttl-stale-marked",
          "dynamodb-retention-failure-ttl",
          "dynamodb-written-item-shapes");

  static final Set<String> SNAPSHOT_CASE_IDS =
      Set.of(
          "core-serialize-event",
          "core-deserialize-event",
          "core-serialize-snapshot",
          "core-deserialize-snapshot",
          "core-storage-commit-failure",
          "core-storage-read-snapshot",
          "core-storage-read-events",
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
        || c.file().equals("dynamodb/write-errors.json")
        || c.operation().map("validateOccurredAt"::equals).orElse(false)
        || READ_CASE_IDS.contains(c.id())
        || SNAPSHOT_CASE_IDS.contains(c.id())
        || RETENTION_CASE_IDS.contains(c.id());
  }

  static CaseResult run(ConformanceCase c, DynamoDbConfigurationFixture fixture) {
    if (!supports(c)) throw new IllegalArgumentException("Unsupported DynamoDB case: " + c.id());
    ObjectNode actual = ConformanceJson.mapper().createObjectNode();
    try {
      boolean layout = c.format().equals("layout");
      if (c.operation().map("validateOccurredAt"::equals).orElse(false)) {
        new DynamoDbEventReadFixture(fixture).validateOccurredAt(c.materialized(), actual);
      } else if (READ_CASE_IDS.contains(c.id())
          || SNAPSHOT_CASE_IDS.contains(c.id())
          || c.file().equals("dynamodb/write-errors.json")
          || RETENTION_CASE_IDS.contains(c.id())) {
        if (RETENTION_CASE_IDS.contains(c.id()))
          new DynamoDbSnapshotRetentionFixture(fixture).execute(c.materialized(), actual);
        else if (SNAPSHOT_CASE_IDS.contains(c.id())
            || c.file().equals("dynamodb/write-errors.json"))
          new DynamoDbSnapshotReadFixture(fixture).execute(c.materialized(), actual);
        else new DynamoDbEventReadFixture(fixture).execute(c.materialized(), actual);
      } else if (layout) fixture.layout(c.materialized(), actual);
      else fixture.configuration(c.materialized(), actual);
      if (actual.has("unsupported"))
        return new CaseResult(
            c.id(),
            c.file(),
            c.rules(),
            Backend.DYNAMODB,
            ConformanceStatus.UNVERIFIED,
            actual.path("unsupported").asText(),
            null,
            c.materialized(),
            actual);
      return new CaseResult(
          c.id(),
          c.file(),
          c.rules(),
          Backend.DYNAMODB,
          ConformanceStatus.PASSED,
          null,
          null,
          c.materialized(),
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
