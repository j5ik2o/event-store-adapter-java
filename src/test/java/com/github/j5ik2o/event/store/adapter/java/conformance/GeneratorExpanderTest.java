package com.github.j5ik2o.event.store.adapter.java.conformance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import org.junit.jupiter.api.Test;

class GeneratorExpanderTest {

  private static ObjectNode caseWith(String payloadJson, String generatorsJson) throws IOException {
    String json =
        "{\"id\":\"gen-case\",\"rules\":[\"E-1\"],"
            + "\"fixtures\":{\"events\":{\"e1\":{\"payload\":"
            + payloadJson
            + "}}},\"steps\":[{\"op\":\"x\"}],\"generators\":"
            + generatorsJson
            + "}";
    return (ObjectNode) ConformanceJson.readTree(json.getBytes("UTF-8"), "inline");
  }

  private static String generator(String target, String character, int byteLength) {
    return "{\"target\":\""
        + target
        + "\",\"character\":\""
        + character
        + "\",\"byte_length\":"
        + byteLength
        + "}";
  }

  @Test
  void escapedPointersExpandEmptyStringsToTheRequestedByteLength() throws IOException {
    ObjectNode input =
        caseWith(
            "{\"a/b\":\"\",\"c~d\":\"\"}",
            "["
                + generator("/fixtures/events/e1/payload/a~1b", "x", 4)
                + ","
                + generator("/fixtures/events/e1/payload/c~0d", "é", 4)
                + "]");
    JsonNode before = input.deepCopy();

    JsonNode expanded = GeneratorExpander.materialize(input);

    JsonNode payload = expanded.at("/fixtures/events/e1/payload");
    assertEquals("xxxx", payload.get("a/b").asText());
    assertEquals("éé", payload.get("c~d").asText());
    assertEquals(before, input);
    assertEquals("", input.at("/fixtures/events/e1/payload").get("a/b").asText());
  }

  @Test
  void targetOutsideFixturesIsRejectedWithTheCaseId() throws IOException {
    ObjectNode input = caseWith("\"\"", "[" + generator("/steps/0", "x", 4) + "]");

    IllegalArgumentException e =
        assertThrows(IllegalArgumentException.class, () -> GeneratorExpander.materialize(input));
    assertTrue(e.getMessage().contains("gen-case"), e.getMessage());
  }

  @Test
  void nonEmptyTargetValueIsRejectedWithTheCaseId() throws IOException {
    ObjectNode input =
        caseWith("\"abc\"", "[" + generator("/fixtures/events/e1/payload", "x", 4) + "]");

    IllegalArgumentException e =
        assertThrows(IllegalArgumentException.class, () -> GeneratorExpander.materialize(input));
    assertTrue(e.getMessage().contains("gen-case"), e.getMessage());
  }

  @Test
  void duplicatedTargetIsRejectedWithTheCaseId() throws IOException {
    String g = generator("/fixtures/events/e1/payload", "x", 4);
    ObjectNode input = caseWith("\"\"", "[" + g + "," + g + "]");

    IllegalArgumentException e =
        assertThrows(IllegalArgumentException.class, () -> GeneratorExpander.materialize(input));
    assertTrue(e.getMessage().contains("gen-case"), e.getMessage());
  }

  @Test
  void byteLengthNotDivisibleByCharacterWidthIsRejected() throws IOException {
    ObjectNode input =
        caseWith("\"\"", "[" + generator("/fixtures/events/e1/payload", "é", 3) + "]");

    assertThrows(IllegalArgumentException.class, () -> GeneratorExpander.materialize(input));
  }

  @Test
  void multiCharacterGeneratorIsRejected() throws IOException {
    ObjectNode input =
        caseWith("\"\"", "[" + generator("/fixtures/events/e1/payload", "ab", 4) + "]");

    assertThrows(IllegalArgumentException.class, () -> GeneratorExpander.materialize(input));
  }
}
