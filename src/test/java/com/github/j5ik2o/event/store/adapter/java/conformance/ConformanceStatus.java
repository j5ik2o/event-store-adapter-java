package com.github.j5ik2o.event.store.adapter.java.conformance;

/** 報告で使う 5 つの状態（設計文書 5.7）。 */
enum ConformanceStatus {
  PASSED("passed"),
  FAILED("failed"),
  NOT_APPLICABLE("not-applicable"),
  UNVERIFIED("unverified"),
  UNREPRESENTABLE("unrepresentable");

  private final String label;

  ConformanceStatus(String label) {
    this.label = label;
  }

  String label() {
    return label;
  }
}
