package com.github.j5ik2o.event.store.adapter.java.conformance;

import com.fasterxml.jackson.core.JsonPointer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

/** ケースの generators を展開する（設計文書 5.2、conformance/README.md）。 */
final class GeneratorExpander {

  private GeneratorExpander() {}

  /** 深い写しの上で展開して返す。引数は書き換えない。 */
  static ObjectNode materialize(ObjectNode caseNode) {
    String caseId = caseNode.path("id").asText("(id なし)");
    ObjectNode copy = caseNode.deepCopy();
    JsonNode generators = caseNode.get("generators");
    if (generators == null) {
      return copy;
    }
    if (!generators.isArray()) {
      throw invalid(caseId, "generators が配列でない");
    }
    Set<String> targets = new HashSet<>();
    for (JsonNode generator : generators) {
      String target = textOf(generator, "target", caseId);
      if (!targets.add(target)) {
        throw invalid(caseId, "target が重複している: " + target);
      }
      if (!target.startsWith("/fixtures/events/") && !target.startsWith("/fixtures/snapshots/")) {
        throw invalid(caseId, "target が fixtures の外: " + target);
      }
      JsonPointer pointer;
      try {
        pointer = JsonPointer.compile(target);
      } catch (IllegalArgumentException e) {
        throw invalid(caseId, "target が JSON Pointer でない: " + target);
      }
      JsonNode current = copy.at(pointer);
      if (!current.isTextual() || !current.asText().isEmpty()) {
        throw invalid(caseId, "target が既存の空文字列を指していない: " + target);
      }
      String character = textOf(generator, "character", caseId);
      if (character.codePointCount(0, character.length()) != 1) {
        throw invalid(caseId, "character が 1 文字でない: " + character);
      }
      JsonNode lengthNode = generator.get("byte_length");
      int byteLength = integerOf(lengthNode, caseId);
      int width = character.getBytes(StandardCharsets.UTF_8).length;
      if (byteLength <= 0 || byteLength % width != 0) {
        throw invalid(caseId, "byte_length " + byteLength + " が " + width + " バイトで割り切れない");
      }
      String value = character.repeat(byteLength / width);
      JsonNode parent = copy.at(pointer.head());
      if (parent instanceof ObjectNode) {
        ((ObjectNode) parent).put(pointer.last().getMatchingProperty(), value);
      } else if (parent instanceof ArrayNode) {
        ((ArrayNode) parent).set(pointer.last().getMatchingIndex(), TextNode.valueOf(value));
      } else {
        throw invalid(caseId, "target の親が展開できない: " + target);
      }
    }
    return copy;
  }

  /** JSON Schema の整数（`4.0`・`4e0` を含む）を int で返す。小数部が 0 でない値や int に収まらない値は拒む。 */
  private static int integerOf(JsonNode node, String caseId) {
    if (node == null || !node.isNumber()) {
      throw invalid(caseId, "byte_length が整数でない");
    }
    try {
      return node.decimalValue().intValueExact();
    } catch (ArithmeticException e) {
      throw invalid(caseId, "byte_length が整数でない: " + node);
    }
  }

  private static String textOf(JsonNode node, String field, String caseId) {
    JsonNode value = node.get(field);
    if (value == null || !value.isTextual()) {
      throw invalid(caseId, field + " が文字列でない");
    }
    return value.asText();
  }

  private static IllegalArgumentException invalid(String caseId, String message) {
    return new IllegalArgumentException("generators が不正: ケース " + caseId + ": " + message);
  }
}
