package com.github.j5ik2o.event.store.adapter.java.conformance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** 適合テストデータの 1 ケース。raw は読んだままの値、materialized は generators を展開した値。 */
final class ConformanceCase {

  private final String id;
  private final String file;
  private final String format;
  private final List<String> rules;
  private final ObjectNode raw;
  private final ObjectNode materialized;

  ConformanceCase(
      String id,
      String file,
      String format,
      List<String> rules,
      ObjectNode raw,
      ObjectNode materialized) {
    this.id = id;
    this.file = file;
    this.format = format;
    this.rules = List.copyOf(rules);
    this.raw = raw;
    this.materialized = materialized;
  }

  String id() {
    return id;
  }

  String file() {
    return file;
  }

  String format() {
    return format;
  }

  List<String> rules() {
    return rules;
  }

  ObjectNode raw() {
    return raw;
  }

  ObjectNode materialized() {
    return materialized;
  }

  Optional<String> operation() {
    JsonNode node = raw.get("operation");
    return node == null || !node.isTextual() ? Optional.empty() : Optional.of(node.asText());
  }

  Optional<List<String>> backends() {
    JsonNode node = raw.get("backends");
    if (node == null || !node.isArray()) {
      return Optional.empty();
    }
    List<String> names = new ArrayList<>();
    node.forEach(n -> names.add(n.asText()));
    return Optional.of(names);
  }

  Optional<String> timePrecision() {
    JsonNode node = raw.path("representation").get("time_precision");
    return node == null || !node.isTextual() ? Optional.empty() : Optional.of(node.asText());
  }
}
