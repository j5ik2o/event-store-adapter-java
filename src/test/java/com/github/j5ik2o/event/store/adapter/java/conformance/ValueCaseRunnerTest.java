package com.github.j5ik2o.event.store.adapter.java.conformance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import org.junit.jupiter.api.Test;

class ValueCaseRunnerTest {

  private static final JsonNodeFactory F = JsonNodeFactory.instance;

  private static ConformanceCase buildAidCase(String typeName, String value, String expectedValue) {
    ObjectNode raw = F.objectNode();
    raw.put("operation", "buildAid");
    ObjectNode materialized = F.objectNode();
    ObjectNode aggregateId = materialized.putObject("input").putObject("aggregate_id");
    aggregateId.put("type_name", typeName);
    aggregateId.put("value", value);
    materialized.putObject("expect").put("value", expectedValue);
    return new ConformanceCase(
        "synthetic", "synthetic.json", "values", List.of(), raw, materialized);
  }

  private static ConformanceCase validateSeqNrCase(long seqNr, String context, long expectedValue) {
    ObjectNode raw = F.objectNode();
    raw.put("operation", "validateSeqNr");
    ObjectNode materialized = F.objectNode();
    ObjectNode input = materialized.putObject("input");
    input.put("seq_nr", seqNr);
    input.put("context", context);
    materialized.putObject("expect").put("value", expectedValue);
    return new ConformanceCase(
        "synthetic", "synthetic.json", "values", List.of(), raw, materialized);
  }

  @Test
  void matchingStringValuesArePassed() {
    ConformanceCase c = buildAidCase("order", "item-1", "order-item-1");

    CaseResult r = ValueCaseRunner.run(c, Backend.MEMORY);

    assertEquals(ConformanceStatus.PASSED, r.status(), r.reason());
  }

  @Test
  void differingStringValuesAreFailed() {
    ConformanceCase c = buildAidCase("order", "1", "Order-123");

    CaseResult r = ValueCaseRunner.run(c, Backend.MEMORY);

    assertEquals(ConformanceStatus.FAILED, r.status());
    assertNotNull(r.reason());
    assertFalse(r.reason().isBlank(), r.reason());
  }

  @Test
  void matchingNumericValuesArePassed() {
    ConformanceCase c = validateSeqNrCase(1, "value", 1);

    CaseResult r = ValueCaseRunner.run(c, Backend.MEMORY);

    assertEquals(ConformanceStatus.PASSED, r.status(), r.reason());
  }

  @Test
  void differingNumericValuesAreFailed() {
    ConformanceCase c = validateSeqNrCase(1, "value", 2);

    CaseResult r = ValueCaseRunner.run(c, Backend.MEMORY);

    assertEquals(ConformanceStatus.FAILED, r.status());
    assertNotNull(r.reason());
    assertFalse(r.reason().isBlank(), r.reason());
  }
}
