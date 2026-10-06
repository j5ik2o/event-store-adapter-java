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

  private static ConformanceCase find(String id) {
    return data.cases().stream()
        .filter(c -> c.id().equals(id))
        .findFirst()
        .orElseThrow(() -> new AssertionError("case not found: " + id));
  }

  @Test
  void noCaseIsPassedFailedOrUnrepresentableAndEveryNotApplicableHasAReason() {
    for (ConformanceCase c : data.cases()) {
      for (Backend b : Backend.values()) {
        CaseResult r = CaseClassifier.classify(c, b);
        assertTrue(
            r.status() == ConformanceStatus.UNVERIFIED
                || r.status() == ConformanceStatus.NOT_APPLICABLE,
            c.id() + "/" + b + ": " + r.status());
        assertFalse(r.reason() == null || r.reason().isBlank(), c.id() + "/" + b);
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
  void requiredListIsEmptyOnBothBackends() throws IOException {
    Map<Backend, Set<String>> required = RequiredCases.load();

    for (Backend b : Backend.values()) {
      assertTrue(required.get(b).isEmpty(), b + ": " + required.get(b));
    }
  }

  @Test
  void unverifiedCaseViolatesRequirementOnlyWhenListed() {
    CaseResult r = CaseClassifier.classify(find("seq-zero-value"), Backend.MEMORY);
    assertEquals(ConformanceStatus.UNVERIFIED, r.status());

    assertFalse(CaseClassifier.violatesRequirement(r, Set.of()));
    assertTrue(CaseClassifier.violatesRequirement(r, Set.of("seq-zero-value")));
    assertFalse(CaseClassifier.violatesRequirement(r, Set.of("other-case")));
  }

  @Test
  void unknownRequiredIdsAreReturned() {
    List<String> unknown =
        CaseClassifier.unknownRequiredIds(Set.of("seq-zero-value", "no-such-case"), data.cases());

    assertEquals(List.of("no-such-case"), unknown);
  }
}
