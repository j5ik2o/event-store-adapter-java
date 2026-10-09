package com.github.j5ik2o.event.store.adapter.java.conformance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

class MemoryCaseRunnerTest {
  @TestFactory
  Stream<DynamicTest> commonMemoryScenariosExecuteIncludingFaultsHistoryAndNotifications()
      throws IOException {
    return ConformanceDataLoader.load(ConformanceTestFiles.REAL_ROOT).cases().stream()
        .filter(c -> c.file().startsWith("scenarios/core/"))
        .filter(c -> !c.timePrecision().map("milliseconds"::equals).orElse(false))
        .map(
            c ->
                dynamicTest(
                    c.id(),
                    () -> {
                      CaseResult result = CaseClassifier.classify(c, Backend.MEMORY);
                      assertEquals(ConformanceStatus.PASSED, result.status(), result.reason());
                    }));
  }

  @Test
  void incorrectReadExpectationCannotBeUsedToGenerateStoredState() throws IOException {
    ConformanceCase original = find("core-replay-without-snapshot");
    ObjectNode changed = original.materialized().deepCopy();
    ((ObjectNode) changed.at("/steps/4/expect")).put("head_seq_nr", 7);

    CaseResult result = CaseClassifier.classify(copy(original, changed), Backend.MEMORY);

    assertEquals(ConformanceStatus.FAILED, result.status(), result.reason());
  }

  @Test
  void incorrectHistoryExpectationIsRejected() throws IOException {
    ConformanceCase original = find("core-retention-delete-1");
    ObjectNode changed = original.materialized().deepCopy();
    ((ArrayNode) changed.at("/steps/1/observe/history/active")).removeAll().add(1);

    CaseResult result = CaseClassifier.classify(copy(original, changed), Backend.MEMORY);

    assertEquals(ConformanceStatus.FAILED, result.status(), result.reason());
  }

  @Test
  void historyDeclaredAbsentMustNotRemainActive() throws IOException {
    ConformanceCase original = find("core-retention-failure-after-commit");
    ObjectNode changed = original.materialized().deepCopy();
    ((ArrayNode) changed.at("/steps/1/observe/history/absent")).add(1);

    CaseResult result = CaseClassifier.classify(copy(original, changed), Backend.MEMORY);

    assertEquals(ConformanceStatus.FAILED, result.status(), result.reason());
  }

  @Test
  void emptyExpectedNotificationsCannotHideRetentionFailure() throws IOException {
    ConformanceCase original = find("core-retention-failure-after-commit");
    ObjectNode changed = original.materialized().deepCopy();
    ((ArrayNode) changed.at("/steps/1/observe/notifications")).removeAll();

    CaseResult result = CaseClassifier.classify(copy(original, changed), Backend.MEMORY);

    assertEquals(ConformanceStatus.FAILED, result.status(), result.reason());
  }

  @Test
  void registeredFaultThatNeverFiresCannotPass() throws IOException {
    ConformanceCase original = find("core-storage-commit-failure");
    ObjectNode changed = original.materialized().deepCopy();
    // The second operation is a read, so this commit fault cannot fire.
    ((ObjectNode) changed.at("/faults/0")).put("operation", 2);
    ObjectNode expectation = (ObjectNode) changed.at("/steps/0/expect");
    expectation.remove("error");
    expectation.put("result", "success");
    ((ObjectNode) changed.at("/steps/1/expect"))
        .put("result", "snapshot")
        .put("head_seq_nr", 1)
        .put("snapshot", "s1");
    ((ArrayNode) changed.at("/steps/2/expect/events")).add("e1");

    CaseResult result = CaseClassifier.classify(copy(original, changed), Backend.MEMORY);

    assertEquals(ConformanceStatus.FAILED, result.status(), result.reason());
    assertEquals(2, result.failedOperation());
  }

  @TestFactory
  Stream<DynamicTest> nanosecondTimeCasesExecuteOnMemoryAndRemainUnverifiedOnDynamoDb()
      throws IOException {
    return ConformanceDataLoader.load(ConformanceTestFiles.REAL_ROOT).cases().stream()
        .filter(c -> c.operation().map("validateOccurredAt"::equals).orElse(false))
        .filter(c -> !c.timePrecision().map("milliseconds"::equals).orElse(false))
        .map(
            c ->
                dynamicTest(
                    c.id(),
                    () -> {
                      CaseResult memory = CaseClassifier.classify(c, Backend.MEMORY);
                      assertEquals(ConformanceStatus.PASSED, memory.status(), memory.reason());
                      assertEquals(
                          ConformanceStatus.UNVERIFIED,
                          CaseClassifier.classify(c, Backend.DYNAMODB).status());
                    }));
  }

  @Test
  void finiteFaultCountMustEqualActualApplications() throws IOException {
    ConformanceCase original = find("core-storage-commit-failure");
    ObjectNode changed = original.materialized().deepCopy();
    ((ObjectNode) changed.at("/faults/0/repeat")).put("count", 2);
    CaseResult result = CaseClassifier.classify(copy(original, changed), Backend.MEMORY);
    assertEquals(ConformanceStatus.FAILED, result.status());
    assertEquals(1, result.failedOperation());
    assertEquals(1, result.actual().at("/faults/0/applications").intValue());
  }

  @TestFactory
  Stream<DynamicTest> unconnectedObservationsAndFaultDetailsRemainUnverified() {
    return Stream.of("requests", "unknown-history", "unknown-fault-detail")
        .map(
            field ->
                dynamicTest(
                    field,
                    () -> {
                      ConformanceCase original = find("core-retention-failure-after-commit");
                      ObjectNode changed = original.materialized().deepCopy();
                      if (field.equals("requests"))
                        ((ObjectNode) changed.at("/steps/1/observe")).putArray(field);
                      else if (field.equals("unknown-history"))
                        ((ObjectNode) changed.at("/steps/1/observe/history")).putArray(field);
                      else ((ObjectNode) changed.at("/faults/0/details")).put(field, true);
                      CaseResult result =
                          CaseClassifier.classify(copy(original, changed), Backend.MEMORY);
                      assertEquals(ConformanceStatus.UNVERIFIED, result.status());
                      org.junit.jupiter.api.Assertions.assertFalse(
                          result.actual().has("initialization"));
                    }));
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
