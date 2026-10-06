package com.github.j5ik2o.event.store.adapter.java.conformance;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

/** 1 ケース × 1 保存先の結果。 */
final class CaseResult {

  private final String caseId;
  private final String file;
  private final List<String> rules;
  private final Backend backend;
  private final ConformanceStatus status;
  private final String reason;
  private final Integer failedOperation;
  private final JsonNode expected;
  private final JsonNode actual;

  CaseResult(
      String caseId,
      String file,
      List<String> rules,
      Backend backend,
      ConformanceStatus status,
      String reason,
      Integer failedOperation,
      JsonNode expected,
      JsonNode actual) {
    this.caseId = caseId;
    this.file = file;
    this.rules = List.copyOf(rules);
    this.backend = backend;
    this.status = status;
    this.reason = reason;
    this.failedOperation = failedOperation;
    this.expected = expected;
    this.actual = actual;
  }

  String caseId() {
    return caseId;
  }

  String file() {
    return file;
  }

  List<String> rules() {
    return rules;
  }

  Backend backend() {
    return backend;
  }

  ConformanceStatus status() {
    return status;
  }

  String reason() {
    return reason;
  }

  Integer failedOperation() {
    return failedOperation;
  }

  JsonNode expected() {
    return expected;
  }

  JsonNode actual() {
    return actual;
  }
}
