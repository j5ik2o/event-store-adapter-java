package com.github.j5ik2o.event.store.adapter.java.conformance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

/** Every Bigtable case runs against the real emulator. Nothing is skipped when Docker is absent. */
class BigtableCaseRunnerTest {
  @TestFactory
  Stream<DynamicTest> everyRequiredBigtableCaseRunsAgainstTheEmulatorAndPasses()
      throws IOException {
    Set<String> required = RequiredCases.load().get(Backend.BIGTABLE);
    return ConformanceDataLoader.load(ConformanceTestFiles.REAL_ROOT).cases().stream()
        .filter(c -> required.contains(c.id()))
        .map(
            c ->
                dynamicTest(
                    c.id(),
                    () -> {
                      CaseResult result = CaseClassifier.classify(c, Backend.BIGTABLE);
                      assertEquals(ConformanceStatus.PASSED, result.status(), result.reason());
                    }));
  }

  @Test
  void requiredBigtableSetEqualsTheApplicableCasesAndIncludesTheBigtableSpecificOnes()
      throws IOException {
    Set<String> required = RequiredCases.load().get(Backend.BIGTABLE);
    Set<String> applicable =
        ConformanceDataLoader.load(ConformanceTestFiles.REAL_ROOT).cases().stream()
            .filter(c -> CaseClassifier.exclusion(c, Backend.BIGTABLE).isEmpty())
            .map(ConformanceCase::id)
            .collect(Collectors.toSet());

    assertEquals(applicable, required);
    assertEquals(87, required.size());
    assertTrue(
        required.containsAll(
            Set.of(
                "bigtable-previous-event-write-failure",
                "bigtable-new-tail-write-failure",
                "bigtable-cas-response-lost",
                "bigtable-change-feed-unsupported",
                "bigtable-ttl-unsupported",
                "bigtable-envelope-over-limit",
                "bigtable-seeded-configuration-match",
                "bigtable-seeded-table-envelope-limit-mismatch",
                "bigtable-envelope-limit-below-minimum",
                "bigtable-envelope-limit-above-maximum",
                "store-shared-persistent")));
  }

  @Test
  void changeFeedAndTtlRequestsRunOnBigtableAndPassAsConfigurationErrors() throws IOException {
    for (String id :
        new String[] {"bigtable-change-feed-unsupported", "bigtable-ttl-unsupported"}) {
      CaseResult result = CaseClassifier.classify(find(id), Backend.BIGTABLE);
      assertEquals(ConformanceStatus.PASSED, result.status(), id + ": " + result.reason());
    }
  }

  @Test
  void dynamoDbLayoutCaseIsNotApplicableToBigtable() throws IOException {
    CaseResult result = CaseClassifier.classify(find("dynamodb-layout-v1"), Backend.BIGTABLE);

    assertEquals(ConformanceStatus.NOT_APPLICABLE, result.status(), result.reason());
    assertFalse(result.reason().contains("未提供"));
  }

  @Test
  void aFaultInjectedWritePreviousOfAnotherKindIsUnverifiedNotPassed() throws IOException {
    ConformanceCase original = find("bigtable-previous-event-write-failure");
    ObjectNode changed = original.materialized().deepCopy();
    ((ObjectNode) changed.at("/faults/0")).put("kind", "sdk-response");

    CaseResult result = CaseClassifier.classify(copy(original, changed), Backend.BIGTABLE);

    assertEquals(ConformanceStatus.UNVERIFIED, result.status(), result.reason());
  }

  @Test
  void aLostCasResponseCaseExpectingSuccessOnResendFails() throws IOException {
    ConformanceCase original = find("bigtable-cas-response-lost");
    ObjectNode changed = original.materialized().deepCopy();
    ObjectNode resend = (ObjectNode) changed.withArray("steps").get(1);
    resend.putObject("expect").put("result", "success");

    CaseResult result = CaseClassifier.classify(copy(original, changed), Backend.BIGTABLE);

    assertEquals(ConformanceStatus.FAILED, result.status(), result.reason());
  }

  @Test
  void aSharedCaseWithAnIndependentNewStorageFails() throws IOException {
    ConformanceCase original = find("store-shared-persistent");
    ObjectNode changed = original.materialized().deepCopy();
    for (JsonNode step : changed.withArray("steps")) {
      if ("createStore".equals(step.path("op").asText())) {
        ((ObjectNode) step.get("arguments")).put("storage", "new");
      }
    }

    CaseResult result = CaseClassifier.classify(copy(original, changed), Backend.BIGTABLE);

    assertEquals(ConformanceStatus.FAILED, result.status(), result.reason());
  }

  @Test
  void anUnsupportedOperationIsUnverifiedAndAViolationOfTheRequiredSet() throws IOException {
    ConformanceCase original = find("bigtable-cas-response-lost");
    ObjectNode changed = original.materialized().deepCopy();
    ((ObjectNode) changed.at("/steps/0")).put("op", "mutateWrittenPayload");

    CaseResult result = CaseClassifier.classify(copy(original, changed), Backend.BIGTABLE);

    assertEquals(ConformanceStatus.UNVERIFIED, result.status(), result.reason());
    assertTrue(
        CaseClassifier.violatesRequirement(result, RequiredCases.load()),
        "an unverified required Bigtable case must violate the requirement");
  }

  @Test
  void dropOneRequiredCaseAndTheRequiredMismatchReportsItAsMissing() throws IOException {
    ConformanceData data = ConformanceDataLoader.load(ConformanceTestFiles.REAL_ROOT);
    Set<String> required = new java.util.HashSet<>(RequiredCases.load().get(Backend.BIGTABLE));
    required.remove("bigtable-cas-response-lost");

    CaseClassifier.RequiredMismatch mismatch =
        CaseClassifier.requiredMismatch(Backend.BIGTABLE, required, data.cases());

    assertEquals(Set.of("bigtable-cas-response-lost"), mismatch.missing());
    assertTrue(mismatch.extra().isEmpty());
  }

  @Test
  void resultsCarryTheEmulatorEvidence() throws IOException {
    CaseResult result =
        CaseClassifier.classify(find("bigtable-cas-response-lost"), Backend.BIGTABLE);

    assertEquals(ConformanceStatus.PASSED, result.status(), result.reason());
    assertFalse(result.actual().path("emulator_image").asText().isEmpty(), "emulator_image");
    assertFalse(result.actual().path("journal_table_id").asText().isEmpty(), "journal_table_id");
    assertFalse(result.actual().path("head_table_id").asText().isEmpty(), "head_table_id");
  }

  private static ConformanceCase find(String id) throws IOException {
    return ConformanceDataLoader.load(ConformanceTestFiles.REAL_ROOT).cases().stream()
        .filter(c -> c.id().equals(id))
        .findFirst()
        .orElseThrow(() -> new AssertionError(id));
  }

  private static ConformanceCase copy(ConformanceCase original, ObjectNode changed) {
    return new ConformanceCase(
        original.id(),
        original.file(),
        original.format(),
        original.rules(),
        changed.deepCopy(),
        changed);
  }
}
