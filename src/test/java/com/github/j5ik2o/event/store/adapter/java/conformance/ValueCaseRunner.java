package com.github.j5ik2o.event.store.adapter.java.conformance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.j5ik2o.event.store.adapter.java.core.AggregateId;
import com.github.j5ik2o.event.store.adapter.java.core.ConformanceValueOperations;
import com.github.j5ik2o.event.store.adapter.java.core.ContractViolationException;
import com.github.j5ik2o.event.store.adapter.java.core.ErrorCategory;
import com.github.j5ik2o.event.store.adapter.java.core.EventStoreException;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

/** 値の表のうち、中核だけで実行できる buildAid と validateSeqNr を実行する。 */
final class ValueCaseRunner {

  private static final JsonNodeFactory F = JsonNodeFactory.instance;

  private ValueCaseRunner() {}

  static boolean supports(ConformanceCase c) {
    return c.operation()
        .map(op -> op.equals("buildAid") || op.equals("validateSeqNr"))
        .orElse(false);
  }

  static CaseResult run(ConformanceCase c, Backend backend) {
    JsonNode input = c.materialized().path("input");
    JsonNode expect = c.materialized().path("expect");
    ObjectNode actual = F.objectNode();
    try {
      switch (c.operation().get()) {
        case "buildAid":
          {
            JsonNode aid = input.path("aggregate_id");
            AggregateId id =
                AggregateId.of(aid.path("type_name").asText(), aid.path("value").asText());
            actual.put("value", id.asString());
            break;
          }
        case "validateSeqNr":
          {
            BigInteger seq = input.path("seq_nr").bigIntegerValue();
            if (seq.bitLength() > 63) {
              return result(
                  c,
                  backend,
                  ConformanceStatus.UNREPRESENTABLE,
                  "seq_nr が符号付き 64bit に収まらない",
                  expect,
                  null);
            }
            long v =
                ConformanceValueOperations.validateSeqNr(
                    seq.longValueExact(), input.path("context").asText());
            actual.put("value", v);
            break;
          }
        default:
          throw new IllegalStateException("unsupported operation");
      }
    } catch (EventStoreException e) {
      ObjectNode error = actual.putObject("error");
      error.put("category", categoryName(e.category()));
      if (e instanceof ContractViolationException) {
        error.put("rule", ((ContractViolationException) e).rule());
      }
      error.put("message", e.getMessage());
    }
    List<String> problems = compare(expect, actual);
    return result(
        c,
        backend,
        problems.isEmpty() ? ConformanceStatus.PASSED : ConformanceStatus.FAILED,
        problems.isEmpty() ? null : String.join("; ", problems),
        expect,
        actual);
  }

  private static List<String> compare(JsonNode expect, JsonNode actual) {
    List<String> problems = new ArrayList<>();
    if (expect.has("value")) {
      if (!actual.has("value")) {
        problems.add("値を期待したがエラーになった");
      } else if (!valuesEqual(expect.get("value"), actual.get("value"))) {
        problems.add("値が違う");
      }
      return problems;
    }
    JsonNode expError = expect.path("error");
    JsonNode actError = actual.path("error");
    if (actError.isMissingNode()) {
      problems.add("エラーを期待したが成功した");
      return problems;
    }
    if (!expError.path("category").asText().equals(actError.path("category").asText())) {
      problems.add("分類が違う");
    }
    if (expError.has("rule")
        && !expError.get("rule").asText().equals(actError.path("rule").asText())) {
      problems.add("規則番号が違う");
    }
    String message = actError.path("message").asText();
    for (JsonNode s : expError.path("message").path("must_contain")) {
      if (!message.contains(s.asText())) {
        problems.add("メッセージに含まれない: " + s.asText());
      }
    }
    for (JsonNode s : expError.path("message").path("must_not_contain")) {
      if (message.contains(s.asText())) {
        problems.add("メッセージに含まれてはいけない: " + s.asText());
      }
    }
    return problems;
  }

  /** 期待値と実際値を型に応じて比較する。文字列は文字列として、数値は値として比べる。 */
  private static boolean valuesEqual(JsonNode expected, JsonNode actual) {
    if (expected.isTextual() && actual.isTextual()) {
      return expected.asText().equals(actual.asText());
    }
    if (expected.isNumber() && actual.isNumber()) {
      return expected.decimalValue().compareTo(actual.decimalValue()) == 0;
    }
    return false;
  }

  private static String categoryName(ErrorCategory category) {
    switch (category) {
      case OPTIMISTIC_LOCK:
        return "optimistic-lock";
      case CONTRACT_VIOLATION:
        return "contract-violation";
      case SERIALIZATION:
        return "serialization";
      case CONFIGURATION:
        return "configuration";
      case STORAGE:
        return "storage";
      default:
        throw new IllegalStateException("unknown category: " + category);
    }
  }

  private static CaseResult result(
      ConformanceCase c,
      Backend backend,
      ConformanceStatus status,
      String reason,
      JsonNode expected,
      JsonNode actual) {
    return new CaseResult(
        c.id(), c.file(), c.rules(), backend, status, reason, null, expected, actual);
  }
}
