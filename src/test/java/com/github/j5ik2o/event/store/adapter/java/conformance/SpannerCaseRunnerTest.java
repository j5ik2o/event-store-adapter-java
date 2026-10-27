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

/** Every Spanner case runs against the real emulator. Nothing is skipped when Docker is absent. */
class SpannerCaseRunnerTest {
  @TestFactory
  Stream<DynamicTest> everyRequiredSpannerCaseRunsAgainstTheEmulatorAndPasses() throws IOException {
    Set<String> required = RequiredCases.load().get(Backend.SPANNER);
    return ConformanceDataLoader.load(ConformanceTestFiles.REAL_ROOT).cases().stream()
        .filter(c -> required.contains(c.id()))
        .map(
            c ->
                dynamicTest(
                    c.id(),
                    () -> {
                      CaseResult result = CaseClassifier.classify(c, Backend.SPANNER);
                      assertEquals(ConformanceStatus.PASSED, result.status(), result.reason());
                    }));
  }

  @Test
  void requiredSpannerSetEqualsTheApplicableCasesAndIncludesTheSpannerSpecificOnes()
      throws IOException {
    Set<String> required = RequiredCases.load().get(Backend.SPANNER);
    Set<String> applicable =
        ConformanceDataLoader.load(ConformanceTestFiles.REAL_ROOT).cases().stream()
            .filter(c -> CaseClassifier.exclusion(c, Backend.SPANNER).isEmpty())
            .map(ConformanceCase::id)
            .collect(Collectors.toSet());

    assertEquals(applicable, required);
    assertTrue(
        required.containsAll(
            Set.of(
                "spanner-aborted-retry-within-limit",
                "spanner-aborted-retry-limit-exceeded",
                "spanner-commit-response-lost",
                "spanner-change-feed-unsupported",
                "spanner-seeded-configuration-match",
                "spanner-seeded-ttl-configuration-match",
                "store-shared-persistent")));
  }

  @Test
  void changeFeedRequestCaseRunsOnSpannerAndPassesAsAConfigurationError() throws IOException {
    CaseResult result =
        CaseClassifier.classify(find("spanner-change-feed-unsupported"), Backend.SPANNER);

    assertEquals(ConformanceStatus.PASSED, result.status(), result.reason());
  }

  @Test
  void dynamoDbLayoutCaseIsNotApplicableToSpanner() throws IOException {
    CaseResult result = CaseClassifier.classify(find("dynamodb-layout-v1"), Backend.SPANNER);

    assertEquals(ConformanceStatus.NOT_APPLICABLE, result.status(), result.reason());
    assertFalse(result.reason().contains("未提供"));
  }

  @Test
  void aFaultThatNeverFiresMakesTheCaseFail() throws IOException {
    ConformanceCase original = find("spanner-aborted-retry-within-limit");
    ObjectNode changed = original.materialized().deepCopy();
    ((ObjectNode) changed.at("/faults/0")).put("operation", 9);

    CaseResult result = CaseClassifier.classify(copy(original, changed), Backend.SPANNER);

    assertEquals(ConformanceStatus.FAILED, result.status(), result.reason());
  }

  @Test
  void moreAbortsThanTheRetryLimitMakesACaseThatExpectsSuccessFail() throws IOException {
    ConformanceCase original = find("spanner-aborted-retry-within-limit");
    ObjectNode changed = original.materialized().deepCopy();
    ((ObjectNode) changed.at("/faults/0/repeat")).put("count", 5);

    CaseResult result = CaseClassifier.classify(copy(original, changed), Backend.SPANNER);

    assertEquals(ConformanceStatus.FAILED, result.status(), result.reason());
  }

  @Test
  void aLostCommitResponseCaseExpectingAStorageErrorOnResendFails() throws IOException {
    ConformanceCase original = find("spanner-commit-response-lost");
    ObjectNode changed = original.materialized().deepCopy();
    ((ObjectNode) changed.at("/steps/1/expect/error")).put("category", "storage");

    CaseResult result = CaseClassifier.classify(copy(original, changed), Backend.SPANNER);

    assertEquals(ConformanceStatus.FAILED, result.status(), result.reason());
    assertEquals("optimistic-lock", result.actual().at("/steps/1/error/category").asText());
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

    CaseResult result = CaseClassifier.classify(copy(original, changed), Backend.SPANNER);

    assertEquals(ConformanceStatus.FAILED, result.status(), result.reason());
  }

  @Test
  void anUnsupportedOperationIsUnverifiedAndAViolationOfTheRequiredSet() throws IOException {
    ConformanceCase original = find("spanner-commit-response-lost");
    ObjectNode changed = original.materialized().deepCopy();
    ((ObjectNode) changed.at("/steps/0")).put("op", "mutateWrittenPayload");

    CaseResult result = CaseClassifier.classify(copy(original, changed), Backend.SPANNER);

    assertEquals(ConformanceStatus.UNVERIFIED, result.status(), result.reason());
    assertTrue(
        CaseClassifier.violatesRequirement(result, RequiredCases.load()),
        "an unverified required Spanner case must violate the requirement");
  }

  @Test
  void dropOneRequiredCaseAndTheRequiredMismatchReportsItAsMissing() throws IOException {
    ConformanceData data = ConformanceDataLoader.load(ConformanceTestFiles.REAL_ROOT);
    Set<String> required = new java.util.HashSet<>(RequiredCases.load().get(Backend.SPANNER));
    required.remove("spanner-commit-response-lost");

    CaseClassifier.RequiredMismatch mismatch =
        CaseClassifier.requiredMismatch(Backend.SPANNER, required, data.cases());

    assertEquals(Set.of("spanner-commit-response-lost"), mismatch.missing());
    assertTrue(mismatch.extra().isEmpty());
  }

  @Test
  void resultsCarryTheEmulatorEvidence() throws IOException {
    CaseResult result =
        CaseClassifier.classify(find("spanner-commit-response-lost"), Backend.SPANNER);

    assertEquals(ConformanceStatus.PASSED, result.status(), result.reason());
    assertFalse(result.actual().path("emulator_image").asText().isEmpty(), "emulator_image");
    assertFalse(result.actual().path("table_prefix").asText().isEmpty(), "table_prefix");
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
