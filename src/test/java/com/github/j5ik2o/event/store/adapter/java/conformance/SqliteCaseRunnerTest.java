package com.github.j5ik2o.event.store.adapter.java.conformance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

class SqliteCaseRunnerTest {
  @TestFactory
  Stream<DynamicTest> everyRequiredSqliteCaseRunsAgainstARealFileAndPasses() throws IOException {
    Set<String> required = RequiredCases.load().get(Backend.SQLITE);
    return ConformanceDataLoader.load(ConformanceTestFiles.REAL_ROOT).cases().stream()
        .filter(c -> required.contains(c.id()))
        .map(
            c ->
                dynamicTest(
                    c.id(),
                    () -> {
                      CaseResult result = CaseClassifier.classify(c, Backend.SQLITE);
                      assertEquals(ConformanceStatus.PASSED, result.status(), result.reason());
                    }));
  }

  @Test
  void requiredSqliteSetHasSixtySevenCasesAndExcludesTheUnrepresentableChangeFeedCase()
      throws IOException {
    Set<String> required = RequiredCases.load().get(Backend.SQLITE);

    assertEquals(67, required.size());
    assertFalse(required.contains("sqlite-change-feed-unsupported"));
    assertTrue(required.contains("sqlite-write-lock-busy"));
    assertTrue(required.contains("store-shared-persistent"));
  }

  @Test
  void requiredSqliteSetEqualsTheApplicableCases() throws IOException {
    Set<String> required = RequiredCases.load().get(Backend.SQLITE);
    Set<String> applicable =
        ConformanceDataLoader.load(ConformanceTestFiles.REAL_ROOT).cases().stream()
            .filter(c -> CaseClassifier.exclusion(c, Backend.SQLITE).isEmpty())
            .map(ConformanceCase::id)
            .collect(Collectors.toSet());

    assertEquals(applicable, required);
  }

  @Test
  void changeFeedRequestCaseIsUnrepresentableNotUnprovided() throws IOException {
    CaseResult result =
        CaseClassifier.classify(find("sqlite-change-feed-unsupported"), Backend.SQLITE);

    assertEquals(ConformanceStatus.UNREPRESENTABLE, result.status(), result.reason());
  }

  @Test
  void dynamoDbLayoutCaseIsNotApplicableToSqliteWithItsOwnReason() throws IOException {
    CaseResult result = CaseClassifier.classify(find("dynamodb-layout-v1"), Backend.SQLITE);

    assertEquals(ConformanceStatus.NOT_APPLICABLE, result.status(), result.reason());
    assertFalse(result.reason().contains("未提供"));
  }

  @Test
  void aFaultThatNeverFiresMakesTheCaseFail() throws IOException {
    ConformanceCase original = find("core-retention-failure-after-commit");
    ObjectNode changed = original.materialized().deepCopy();
    // Move the retention-delete fault to an operation that never reaches retention.
    ((ObjectNode) changed.at("/faults/0")).put("operation", 4);

    CaseResult result = CaseClassifier.classify(copy(original, changed), Backend.SQLITE);

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

    CaseResult result = CaseClassifier.classify(copy(original, changed), Backend.SQLITE);

    assertEquals(ConformanceStatus.FAILED, result.status(), result.reason());
  }

  @Test
  void aWriteLockThatIsNeverReleasedMakesTheCaseFail() throws IOException {
    ConformanceCase original = find("sqlite-write-lock-busy");
    ObjectNode changed = original.materialized().deepCopy();
    ArrayNode steps = changed.withArray("steps");
    steps.remove(3); // releaseWriteLock

    CaseResult result = CaseClassifier.classify(copy(original, changed), Backend.SQLITE);

    assertEquals(ConformanceStatus.FAILED, result.status(), result.reason());
  }

  @Test
  void lockWaitCaseExpectingAnOptimisticLockInsteadOfStorageErrorFails() throws IOException {
    ConformanceCase original = find("sqlite-write-lock-busy");
    ObjectNode changed = original.materialized().deepCopy();
    ((ObjectNode) changed.at("/steps/2/expect/error")).put("category", "optimistic_lock");

    CaseResult result = CaseClassifier.classify(copy(original, changed), Backend.SQLITE);

    assertEquals(ConformanceStatus.FAILED, result.status(), result.reason());
  }

  @Test
  void anUnsupportedOperationIsUnverifiedAndAViolationOfTheRequiredSet() throws IOException {
    ConformanceCase original = find("sqlite-write-lock-busy");
    ObjectNode changed = original.materialized().deepCopy();
    ((ObjectNode) changed.at("/steps/0")).put("op", "mutateWrittenPayload");

    CaseResult result = CaseClassifier.classify(copy(original, changed), Backend.SQLITE);

    assertEquals(ConformanceStatus.UNVERIFIED, result.status(), result.reason());
    assertTrue(
        CaseClassifier.violatesRequirement(result, RequiredCases.load()),
        "an unverified required SQLite case must violate the requirement");
  }

  @Test
  void dropOneRequiredCaseAndTheRequiredMismatchReportsItAsMissing() throws IOException {
    ConformanceData data = ConformanceDataLoader.load(ConformanceTestFiles.REAL_ROOT);
    Set<String> required = new java.util.HashSet<>(RequiredCases.load().get(Backend.SQLITE));
    required.remove("sqlite-write-lock-busy");

    CaseClassifier.RequiredMismatch mismatch =
        CaseClassifier.requiredMismatch(Backend.SQLITE, required, data.cases());

    assertEquals(Set.of("sqlite-write-lock-busy"), mismatch.missing());
    assertTrue(mismatch.extra().isEmpty());
  }

  @Test
  void anExtraRequiredIdIsReportedAsInapplicable() throws IOException {
    ConformanceData data = ConformanceDataLoader.load(ConformanceTestFiles.REAL_ROOT);
    Set<String> required = new java.util.HashSet<>(RequiredCases.load().get(Backend.SQLITE));
    required.add("sqlite-change-feed-unsupported");

    CaseClassifier.RequiredMismatch mismatch =
        CaseClassifier.requiredMismatch(Backend.SQLITE, required, data.cases());

    assertTrue(mismatch.missing().isEmpty());
    assertEquals(Set.of("sqlite-change-feed-unsupported"), mismatch.extra());
  }

  @Test
  void resultsCarryTheSqliteFileEvidence() throws IOException {
    CaseResult result = CaseClassifier.classify(find("sqlite-write-lock-busy"), Backend.SQLITE);

    assertEquals(ConformanceStatus.PASSED, result.status(), result.reason());
    assertTrue(result.actual().path("sqlite_version").isTextual(), "sqlite_version evidence");
    assertFalse(result.actual().path("storage_file").asText().isEmpty(), "storage_file evidence");
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
