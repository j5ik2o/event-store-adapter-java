package com.github.j5ik2o.event.store.adapter.java.conformance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class CaseClassifierTest {

  private static ConformanceData data;

  @BeforeAll
  static void load() throws IOException {
    data = ConformanceDataLoader.load(ConformanceTestFiles.REAL_ROOT);
  }

  private static final Set<String> EXECUTABLE_CASE_IDS =
      Set.of(
          "aid-library-format",
          "aid-hyphen-value",
          "aid-hyphen-type",
          "aid-ascii-1023",
          "aid-ascii-1024",
          "aid-ascii-1025",
          "aid-multibyte-1023",
          "aid-multibyte-1024",
          "aid-multibyte-1025",
          "seq-zero-value",
          "seq-max-value",
          "seq-one-event",
          "seq-zero-event",
          "seq-negative-value",
          "seq-above-max-value");

  private static ConformanceCase find(String id) {
    return data.cases().stream()
        .filter(c -> c.id().equals(id))
        .findFirst()
        .orElseThrow(() -> new AssertionError("case not found: " + id));
  }

  @Test
  void implementedMemoryCasesAndExistingCoreCasesPassWithoutPromotingDynamoDbCases() {
    for (ConformanceCase c : data.cases()) {
      for (Backend b : Backend.values()) {
        CaseResult r = CaseClassifier.classify(c, b);
        if (EXECUTABLE_CASE_IDS.contains(c.id())
            || (b == Backend.MEMORY && memoryExecutable(c))
            || (b == Backend.SQLITE && sqliteExecutable(c))
            || (b == Backend.SPANNER && spannerExecutable(c))
            || (b == Backend.BIGTABLE && bigtableExecutable(c))) {
          // Spanner and Bigtable take a change-feed request as a configuration error, so the
          // case runs.
          ConformanceStatus expected =
              c.changeFeedRequested() && b != Backend.SPANNER && b != Backend.BIGTABLE
                  ? ConformanceStatus.UNREPRESENTABLE
                  : ConformanceStatus.PASSED;
          assertEquals(expected, r.status(), c.id() + "/" + b);
        } else {
          assertTrue(
              r.status() == ConformanceStatus.UNVERIFIED
                  || r.status() == ConformanceStatus.NOT_APPLICABLE,
              c.id() + "/" + b + ": " + r.status());
          assertFalse(r.reason() == null || r.reason().isBlank(), c.id() + "/" + b);
        }
      }
    }
  }

  @Test
  void fnv1a64CasesAreNotApplicableWithAHashReasonExceptOnSpannerAndBigtable() {
    for (int i = 1; i <= 4; i++) {
      for (Backend b : Backend.values()) {
        CaseResult r = CaseClassifier.classify(find("hash-fnv1a64-" + i), b);
        if (b == Backend.SPANNER || b == Backend.BIGTABLE) {
          assertEquals(ConformanceStatus.PASSED, r.status(), r.reason());
        } else {
          assertEquals(ConformanceStatus.NOT_APPLICABLE, r.status());
          assertTrue(r.reason().contains("ハッシュ"), r.reason());
        }
      }
    }
  }

  @Test
  void dynamodbOnlyCaseIsNotApplicableOnMemory() {
    ConformanceCase c =
        data.cases().stream()
            .filter(x -> x.backends().map(l -> l.equals(List.of("dynamodb"))).orElse(false))
            .filter(x -> !x.file().equals("dynamodb/layout.json"))
            .findFirst()
            .orElseThrow(() -> new AssertionError("dynamodb-only case not found"));

    assertEquals(
        ConformanceStatus.NOT_APPLICABLE, CaseClassifier.classify(c, Backend.MEMORY).status());
    assertEquals(
        ConformanceStatus.UNVERIFIED, CaseClassifier.classify(c, Backend.DYNAMODB).status());
  }

  @Test
  void requiredListKeepsExistingCasesAndIncludesSuccessfulConnections() throws IOException {
    Map<Backend, Set<String>> required = RequiredCases.load();

    Set<String> memory =
        data.cases().stream()
            .filter(c -> EXECUTABLE_CASE_IDS.contains(c.id()) || memoryExecutable(c))
            // 表現不能のケースは必須にしない（必須のケースは成功だけを認める）。
            .filter(c -> !c.changeFeedRequested())
            .map(ConformanceCase::id)
            .collect(Collectors.toSet());
    assertEquals(66, memory.size());
    assertEquals(memory, required.get(Backend.MEMORY));
    Set<String> dynamodb = new java.util.HashSet<>(EXECUTABLE_CASE_IDS);
    Set<String> configuration =
        data.cases().stream()
            .filter(c -> c.file().equals("dynamodb/configuration.json"))
            .map(ConformanceCase::id)
            .collect(Collectors.toSet());
    assertEquals(14, configuration.size());
    dynamodb.addAll(configuration);
    dynamodb.addAll(
        Set.of("core-time-roundtrip-min", "core-time-roundtrip-max", "core-json-root-values"));
    dynamodb.addAll(DynamoDbCaseRunner.SNAPSHOT_CASE_IDS);
    dynamodb.addAll(DynamoDbCaseRunner.RETENTION_CASE_IDS);
    assertTrue(required.get(Backend.DYNAMODB).containsAll(dynamodb));
    Set<String> targets =
        data.cases().stream()
            .filter(c -> CaseClassifier.exclusion(c, Backend.DYNAMODB).isEmpty())
            .map(ConformanceCase::id)
            .collect(Collectors.toSet());
    assertEquals(106, targets.size());
    assertEquals(targets, required.get(Backend.DYNAMODB));
    Set<String> connections =
        data.cases().stream()
            .filter(c -> CaseClassifier.exclusion(c, Backend.DYNAMODB).isEmpty())
            .filter(c -> ValueCaseRunner.supports(c) || DynamoDbCaseRunner.supports(c))
            .map(ConformanceCase::id)
            .collect(Collectors.toSet());
    assertEquals(targets, connections);
    assertTrue(required.get(Backend.DYNAMODB).contains("dynamodb-layout-v1"));
  }

  @Test
  void unverifiedCaseViolatesRequirementOnlyWhenListed() {
    CaseResult r = CaseClassifier.classify(find("occurred-at-min"), Backend.DYNAMODB);
    assertEquals(ConformanceStatus.UNVERIFIED, r.status());

    assertFalse(CaseClassifier.violatesRequirement(r, Map.of(Backend.DYNAMODB, Set.of())));
    assertTrue(
        CaseClassifier.violatesRequirement(r, Map.of(Backend.DYNAMODB, Set.of("occurred-at-min"))));
    assertFalse(
        CaseClassifier.violatesRequirement(r, Map.of(Backend.DYNAMODB, Set.of("other-case"))));
  }

  @Test
  void unknownRequiredIdsAreReturned() {
    List<String> unknown =
        CaseClassifier.unknownRequiredIds(Set.of("occurred-at-min", "no-such-case"), data.cases());

    assertEquals(List.of("no-such-case"), unknown);
  }

  @Test
  void requirementIsDecidedPerCaseIdAndBackendPair() {
    Map<Backend, Set<String>> required =
        Map.of(Backend.MEMORY, Set.of(), Backend.DYNAMODB, Set.of("occurred-at-min"));
    CaseResult memory = CaseClassifier.classify(find("occurred-at-min"), Backend.MEMORY);
    CaseResult dynamo = CaseClassifier.classify(find("occurred-at-min"), Backend.DYNAMODB);

    assertFalse(CaseClassifier.violatesRequirement(memory, required));
    assertTrue(CaseClassifier.violatesRequirement(dynamo, required));
  }

  @Test
  void noCaseIsExcludedAsUnprovidedBecauseBigtableIsNowProvided() {
    for (ConformanceCase c : data.cases()) {
      for (Backend b : Backend.values()) {
        CaseResult r = CaseClassifier.classify(c, b);
        String reason = r.reason() == null ? "" : r.reason();
        assertFalse(reason.contains("提供しない"), c.id() + "/" + b + ": " + reason);
      }
    }
  }

  @Test
  void bigtableEnvelopeOperationsAreNotApplicableOutsideBigtableAndExecutableOnBigtable() {
    Set<String> operations =
        Set.of(
            "encodeBigtableEventEnvelope",
            "encodeBigtableSnapshotEnvelope",
            "decodeBigtableEnvelope");
    List<ConformanceCase> envelope =
        data.cases().stream()
            .filter(c -> c.operation().map(operations::contains).orElse(false))
            .collect(Collectors.toList());

    assertEquals(10, envelope.size());
    for (ConformanceCase c : envelope) {
      for (Backend b : Backend.values()) {
        CaseResult r = CaseClassifier.classify(c, b);
        if (b == Backend.BIGTABLE) {
          assertEquals(ConformanceStatus.PASSED, r.status(), c.id() + ": " + r.reason());
        } else {
          assertEquals(ConformanceStatus.NOT_APPLICABLE, r.status(), c.id() + "/" + b);
          assertTrue(r.reason().contains("Bigtable の封筒"), c.id() + "/" + b + ": " + r.reason());
        }
      }
    }
  }

  @Test
  void unknownOperationInAProvidedBackendCaseIsNeverPassedAndViolatesTheRequirement()
      throws IOException {
    ConformanceCase original = find("memory-store-shared-configuration");
    com.fasterxml.jackson.databind.node.ObjectNode changed = original.materialized().deepCopy();
    ((com.fasterxml.jackson.databind.node.ObjectNode) changed.path("steps").get(0))
        .put("op", "holdWriteLock");
    ConformanceCase copy =
        new ConformanceCase(
            original.id(),
            original.file(),
            original.format(),
            original.rules(),
            changed.deepCopy(),
            changed);

    CaseResult r = CaseClassifier.classify(copy, Backend.MEMORY);

    assertEquals(ConformanceStatus.UNVERIFIED, r.status(), r.reason());
    assertTrue(CaseClassifier.violatesRequirement(r, RequiredCases.load()));
  }

  private static boolean sqliteExecutable(ConformanceCase c) {
    return !c.timePrecision().map("milliseconds"::equals).orElse(false)
        && (c.file().startsWith("scenarios/core/")
            || c.file().startsWith("sqlite/")
            || c.file().equals("scenarios/stores/shared.json")
            || c.operation().map("validateOccurredAt"::equals).orElse(false));
  }

  @Test
  void sqliteTargetsAreSixtySevenCasesEqualToTheRequiredListAndAllConnected() throws IOException {
    Set<String> targets =
        data.cases().stream()
            .filter(c -> CaseClassifier.exclusion(c, Backend.SQLITE).isEmpty())
            .map(ConformanceCase::id)
            .collect(Collectors.toSet());

    assertEquals(67, targets.size());
    assertEquals(targets, RequiredCases.load().get(Backend.SQLITE));
    for (ConformanceCase c : data.cases()) {
      if (targets.contains(c.id())) {
        assertTrue(
            ValueCaseRunner.supports(c) || SqliteCaseRunner.supports(c),
            c.id() + " is a SQLite target without a runner");
      }
    }
  }

  private static boolean spannerExecutable(ConformanceCase c) {
    return !c.timePrecision().map("milliseconds"::equals).orElse(false)
        && (c.file().startsWith("scenarios/core/")
            || c.file().startsWith("spanner/")
            || c.file().equals("scenarios/stores/shared.json")
            || c.operation().map("validateOccurredAt"::equals).orElse(false)
            || c.operation().map("fnv1a64"::equals).orElse(false));
  }

  @Test
  void spannerTargetsEqualTheRequiredListAndAreAllConnectedAndNeverExcludedAsUnprovided()
      throws IOException {
    Set<String> targets =
        data.cases().stream()
            .filter(c -> CaseClassifier.exclusion(c, Backend.SPANNER).isEmpty())
            .map(ConformanceCase::id)
            .collect(Collectors.toSet());

    assertEquals(targets, RequiredCases.load().get(Backend.SPANNER));
    assertTrue(targets.containsAll(Set.of("hash-fnv1a64-1", "spanner-change-feed-unsupported")));
    assertFalse(targets.contains("dynamodb-layout-v1"));
    for (ConformanceCase c : data.cases()) {
      if (targets.contains(c.id())) {
        assertTrue(
            ValueCaseRunner.supports(c) || SpannerCaseRunner.supports(c),
            c.id() + " is a Spanner target without a runner");
      }
    }
  }

  @Test
  void bigtableScenariosAreNotExcludedOnBigtableAndAreNotApplicableOnTheOtherBackends() {
    int bigtableFiles = 0;
    for (ConformanceCase c : data.cases()) {
      if (!c.file().startsWith("bigtable/")) continue;
      bigtableFiles++;
      assertTrue(CaseClassifier.exclusion(c, Backend.BIGTABLE).isEmpty(), c.id());
      for (Backend b : Backend.values()) {
        if (b == Backend.BIGTABLE) continue;
        assertEquals(
            ConformanceStatus.NOT_APPLICABLE, CaseClassifier.classify(c, b).status(), c.id() + b);
      }
    }
    assertEquals(12, bigtableFiles);
  }

  private static boolean bigtableExecutable(ConformanceCase c) {
    return !c.timePrecision().map("milliseconds"::equals).orElse(false)
        && (c.file().startsWith("scenarios/core/")
            || c.file().startsWith("bigtable/")
            || c.file().equals("scenarios/stores/shared.json")
            || c.file().equals("values/bigtable-envelope.json")
            || c.operation().map("validateOccurredAt"::equals).orElse(false)
            || c.operation().map("fnv1a64"::equals).orElse(false));
  }

  @Test
  void bigtableTargetsAreEightySevenCasesEqualToTheRequiredListAndAllConnected()
      throws IOException {
    Set<String> targets =
        data.cases().stream()
            .filter(c -> CaseClassifier.exclusion(c, Backend.BIGTABLE).isEmpty())
            .map(ConformanceCase::id)
            .collect(Collectors.toSet());

    assertEquals(87, targets.size());
    assertEquals(targets, RequiredCases.load().get(Backend.BIGTABLE));
    assertTrue(
        targets.containsAll(
            Set.of(
                "hash-fnv1a64-1",
                "bigtable-change-feed-unsupported",
                "bigtable-envelope-event-empty",
                "bigtable-cas-response-lost",
                "store-shared-persistent")));
    assertFalse(targets.contains("dynamodb-layout-v1"));
    assertFalse(targets.contains("spanner-commit-response-lost"));
    for (ConformanceCase c : data.cases()) {
      if (targets.contains(c.id())) {
        assertTrue(
            ValueCaseRunner.supports(c) || BigtableCaseRunner.supports(c),
            c.id() + " is a Bigtable target without a runner");
      }
    }
  }

  private static boolean memoryExecutable(ConformanceCase c) {
    return !c.timePrecision().map("milliseconds"::equals).orElse(false)
        && (c.file().startsWith("scenarios/core/")
            || c.file().startsWith("memory/")
            || c.operation().map("validateOccurredAt"::equals).orElse(false));
  }
}
