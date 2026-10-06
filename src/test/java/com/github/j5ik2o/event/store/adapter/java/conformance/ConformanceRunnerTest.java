package com.github.j5ik2o.event.store.adapter.java.conformance;

import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.DynamicContainer.dynamicContainer;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DynamicNode;
import org.junit.jupiter.api.TestFactory;

class ConformanceRunnerTest {

  @TestFactory
  Stream<DynamicNode> conformance() throws IOException {
    ManifestVerifier.Result manifest = ManifestVerifier.verify(Paths.get("conformance"));
    ConformanceData data = ConformanceDataLoader.load(Paths.get("conformance"));
    Map<Backend, Set<String>> required = RequiredCases.load();

    List<CaseResult> results = new ArrayList<>();
    for (ConformanceCase c : data.cases()) {
      for (Backend backend : Backend.values()) {
        results.add(CaseClassifier.classify(c, backend));
      }
    }

    String implementationVersion = Files.readString(Paths.get("version")).trim();
    ConformanceReport report =
        new ConformanceReport(
            data.dataVersion(),
            manifest,
            implementationVersion,
            ConformanceReport.commitFrom(System.getenv()),
            results,
            data.coverageExclusions());
    report.write(Paths.get("build/reports/conformance"));
    System.out.println(report.summary());

    List<DynamicNode> nodes = new ArrayList<>();
    nodes.add(
        dynamicTest(
            "manifest",
            () -> {
              if (!manifest.ok()) {
                fail("manifest mismatch: " + manifest.mismatches());
              }
            }));
    nodes.add(
        dynamicTest(
            "required-case-ids",
            () -> {
              for (Backend backend : Backend.values()) {
                List<String> unknown =
                    CaseClassifier.unknownRequiredIds(required.get(backend), data.cases());
                if (!unknown.isEmpty()) {
                  fail(backend.reportName() + ": unknown required case ids " + unknown);
                }
              }
            }));
    List<DynamicNode> caseTests = new ArrayList<>();
    for (CaseResult r : results) {
      caseTests.add(
          dynamicTest(
              "[" + r.backend().reportName() + "] " + r.caseId(),
              () -> {
                if (CaseClassifier.violatesRequirement(r, required)) {
                  fail(r.caseId() + " is required but " + r.status().label() + ": " + r.reason());
                }
                switch (r.status()) {
                  case PASSED:
                    return;
                  case FAILED:
                    fail(r.caseId() + ": " + r.reason());
                    return;
                  default:
                    Assumptions.abort(r.status().label() + ": " + r.reason());
                }
              }));
    }
    nodes.add(dynamicContainer("cases", caseTests));
    return nodes.stream();
  }
}
