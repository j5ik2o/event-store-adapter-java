package com.github.j5ik2o.event.store.adapter.java.conformance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
  void onlyCoreExecutableCasesArePassedAndNoCaseFailsOrIsUnrepresentable() {
    for (ConformanceCase c : data.cases()) {
      for (Backend b : Backend.values()) {
        CaseResult r = CaseClassifier.classify(c, b);
        if (EXECUTABLE_CASE_IDS.contains(c.id())) {
          assertEquals(ConformanceStatus.PASSED, r.status(), c.id() + "/" + b);
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
  void fnv1a64CasesAreNotApplicableWithAHashReasonOnBothBackends() {
    for (int i = 1; i <= 4; i++) {
      for (Backend b : Backend.values()) {
        CaseResult r = CaseClassifier.classify(find("hash-fnv1a64-" + i), b);
        assertEquals(ConformanceStatus.NOT_APPLICABLE, r.status());
        assertTrue(r.reason().contains("ハッシュ"), r.reason());
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
  void requiredListHoldsTheFifteenCoreExecutableCasesOnBothBackends() throws IOException {
    Map<Backend, Set<String>> required = RequiredCases.load();

    for (Backend b : Backend.values()) {
      assertEquals(EXECUTABLE_CASE_IDS, required.get(b), b.toString());
    }
  }

  @Test
  void unverifiedCaseViolatesRequirementOnlyWhenListed() {
    CaseResult r = CaseClassifier.classify(find("occurred-at-min"), Backend.MEMORY);
    assertEquals(ConformanceStatus.UNVERIFIED, r.status());

    assertFalse(CaseClassifier.violatesRequirement(r, Map.of(Backend.MEMORY, Set.of())));
    assertTrue(
        CaseClassifier.violatesRequirement(r, Map.of(Backend.MEMORY, Set.of("occurred-at-min"))));
    assertFalse(
        CaseClassifier.violatesRequirement(r, Map.of(Backend.MEMORY, Set.of("other-case"))));
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
        Map.of(Backend.MEMORY, Set.of("occurred-at-min"), Backend.DYNAMODB, Set.of());
    CaseResult memory = CaseClassifier.classify(find("occurred-at-min"), Backend.MEMORY);
    CaseResult dynamo = CaseClassifier.classify(find("occurred-at-min"), Backend.DYNAMODB);

    assertTrue(CaseClassifier.violatesRequirement(memory, required));
    assertFalse(CaseClassifier.violatesRequirement(dynamo, required));
  }
}
