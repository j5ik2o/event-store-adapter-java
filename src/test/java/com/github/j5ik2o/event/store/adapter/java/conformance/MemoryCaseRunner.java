package com.github.j5ik2o.event.store.adapter.java.conformance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.j5ik2o.event.store.adapter.java.memory.ConformanceMemoryOperations;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** 実際のメモリ操作の結果を、共通データの期待値と比較する。 */
final class MemoryCaseRunner {
  private MemoryCaseRunner() {}

  static boolean supports(ConformanceCase c) {
    return c.file().startsWith("scenarios/core/")
        || c.operation().map("validateOccurredAt"::equals).orElse(false);
  }

  static CaseResult run(ConformanceCase c) {
    JsonNode input = c.materialized();
    List<String> problems = new ArrayList<>();
    Integer failedOperation = null;
    ObjectNode actual;
    if (c.operation().isPresent()) {
      actual = ConformanceMemoryOperations.validateOccurredAt(input.path("input"), c.id());
      ObjectNode expected = input.path("expect").deepCopy();
      // 精度方針は実行器への指定であり、保存操作の戻り値ではない。
      expected.remove("precision_policy");
      compare(expected, actual, problems, "時刻");
    } else {
      actual = ConformanceMemoryOperations.executeScenario(input);
      if (actual.has("unsupported")) {
        return result(
            c, ConformanceStatus.UNVERIFIED, actual.path("unsupported").asText(), null, actual);
      }
      JsonNode initialization = input.path("initialization").path("expect");
      if (initialization.isMissingNode()) {
        if (!"success".equals(actual.path("initialization").path("result").asText())) {
          problems.add("保存先の生成に失敗した");
          failedOperation = 0;
        }
      } else {
        compare(initialization, actual.path("initialization"), problems, "生成");
        if (!problems.isEmpty()) {
          failedOperation = 0;
        }
      }
      JsonNode steps = input.path("steps");
      JsonNode results = actual.path("steps");
      if (steps.size() != results.size()) {
        problems.add("実行した操作数が違う");
      }
      for (int i = 0; i < steps.size() && i < results.size(); i++) {
        int before = problems.size();
        JsonNode step = steps.get(i);
        ObjectNode expected = step.path("expect").deepCopy();
        if (expected.path("snapshot").isTextual()) {
          expected.set(
              "snapshot",
              envelope(
                  input.path("fixtures").path("snapshots").path(expected.path("snapshot").asText()),
                  false));
        }
        if (expected.has("events")) {
          com.fasterxml.jackson.databind.node.ArrayNode events = expected.putArray("events");
          for (JsonNode reference : step.path("expect").path("events")) {
            events.add(
                envelope(input.path("fixtures").path("events").path(reference.asText()), true));
          }
        }
        compare(expected, results.get(i), problems, "操作 " + (i + 1));
        observe(step.path("observe"), results.get(i).path("observe"), problems);
        if (failedOperation == null && before != problems.size()) {
          failedOperation = i + 1;
        }
      }
      if (actual.has("fault_failure")) {
        problems.add(actual.path("fault_failure").asText());
        if (failedOperation == null) {
          failedOperation = actual.path("fault_operation").intValue();
        }
      }
    }
    return result(
        c,
        problems.isEmpty() ? ConformanceStatus.PASSED : ConformanceStatus.FAILED,
        problems.isEmpty() ? null : String.join("; ", problems),
        failedOperation,
        actual);
  }

  private static ObjectNode envelope(JsonNode fixture, boolean event) {
    ObjectNode result = fixture.deepCopy();
    if (!result.has("manifest")) {
      result.put("manifest", "");
    }
    if (event) {
      result.put("occurred_at", Instant.parse(result.path("occurred_at").asText()).toString());
    }
    return result;
  }

  private static void compare(
      JsonNode expected, JsonNode actual, List<String> problems, String where) {
    if (expected.has("error")) {
      JsonNode error = expected.path("error");
      JsonNode received = actual.path("error");
      for (String field : List.of("category", "rule", "seq_nr")) {
        if (error.has(field) && !equal(error.get(field), received.path(field))) {
          problems.add(where + ": エラーの " + field + " が違う");
        }
      }
      String message = received.path("message").asText();
      for (JsonNode part : error.path("message").path("must_contain")) {
        if (!message.contains(part.asText())) {
          problems.add(where + ": メッセージに含まれない " + part.asText());
        }
      }
      for (JsonNode part : error.path("message").path("must_not_contain")) {
        if (message.contains(part.asText())) {
          problems.add(where + ": メッセージに禁止された値がある");
        }
      }
      return;
    }
    expected
        .fields()
        .forEachRemaining(
            entry -> {
              if (!equal(entry.getValue(), actual.path(entry.getKey()))) {
                problems.add(where + ": " + entry.getKey() + " が違う");
              }
            });
  }

  private static boolean equal(JsonNode expected, JsonNode actual) {
    if (expected.isNumber() && actual.isNumber()) {
      return expected.decimalValue().compareTo(actual.decimalValue()) == 0;
    }
    if (expected.isArray() && actual.isArray()) {
      if (expected.size() != actual.size()) {
        return false;
      }
      for (int i = 0; i < expected.size(); i++) {
        if (!equal(expected.get(i), actual.get(i))) {
          return false;
        }
      }
      return true;
    }
    if (expected.isObject() && actual.isObject()) {
      if (expected.size() != actual.size()) {
        return false;
      }
      java.util.Iterator<String> fields = expected.fieldNames();
      while (fields.hasNext()) {
        String field = fields.next();
        if (!equal(expected.get(field), actual.path(field))) {
          return false;
        }
      }
      return true;
    }
    return expected.equals(actual);
  }

  private static void observe(JsonNode expected, JsonNode actual, List<String> problems) {
    if (expected.has("notifications")
        && !equal(expected.get("notifications"), actual.path("notifications"))) {
      problems.add("失敗通知が違う");
    }
    if (expected.has("history")) {
      JsonNode history = expected.path("history");
      JsonNode received = actual.path("history");
      for (String field : List.of("active", "marked")) {
        if (!set(history.path(field)).equals(set(received.path(field)))) {
          problems.add("履歴の " + field + " が違う");
        }
      }
      Set<JsonNode> present = set(received.path("active"));
      present.addAll(set(received.path("marked")));
      for (JsonNode absent : set(history.path("absent"))) {
        if (present.contains(absent)) {
          problems.add("削除対象の履歴が残る");
        }
      }
    }
  }

  private static Set<JsonNode> set(JsonNode values) {
    Set<JsonNode> result = new HashSet<>();
    values.forEach(
        value ->
            result.add(
                value.isNumber()
                    ? com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.numberNode(
                        value.bigIntegerValue())
                    : value));
    return result;
  }

  private static CaseResult result(
      ConformanceCase c,
      ConformanceStatus status,
      String reason,
      Integer operation,
      JsonNode actual) {
    return new CaseResult(
        c.id(),
        c.file(),
        c.rules(),
        Backend.MEMORY,
        status,
        reason,
        operation,
        c.materialized(),
        actual);
  }
}
