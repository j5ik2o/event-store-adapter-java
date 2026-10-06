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

  @Test
  void replacementCharacterIsAValidCharacterInEscapedAndRawForms() throws IOException {
    ObjectNode escaped =
        caseWith(
            "{\"p\":\"\"}", "[" + generator("/fixtures/events/e1/payload/p", "\\uFFFD", 6) + "]");
    byte[] rawBytes =
        ("{\"id\":\"gen-case\",\"fixtures\":{\"events\":{\"e1\":{\"payload\":{\"p\":\"\"}}}},"
                + "\"generators\":[{\"target\":\"/fixtures/events/e1/payload/p\",\"character\":\"")
            .getBytes(java.nio.charset.StandardCharsets.UTF_8);
    byte[] tail = "\",\"byte_length\":6}]}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    byte[] all = new byte[rawBytes.length + 3 + tail.length];
    System.arraycopy(rawBytes, 0, all, 0, rawBytes.length);
    all[rawBytes.length] = (byte) 0xEF;
    all[rawBytes.length + 1] = (byte) 0xBF;
    all[rawBytes.length + 2] = (byte) 0xBD;
    System.arraycopy(tail, 0, all, rawBytes.length + 3, tail.length);
    ObjectNode raw = (ObjectNode) ConformanceJson.readTree(all, "raw-fffd");

    assertEquals(
        "\uFFFD\uFFFD",
        GeneratorExpander.materialize(escaped).at("/fixtures/events/e1/payload/p").asText());
    assertEquals(
        "\uFFFD\uFFFD",
        GeneratorExpander.materialize(raw).at("/fixtures/events/e1/payload/p").asText());
  }

  @Test
  void byteLengthAcceptsIntegralSpellingsAndRejectsFractions() throws IOException {
    for (String spelling : new String[] {"4.0", "4e0", "0.4E1"}) {
      ObjectNode input =
          caseWith(
              "{\"p\":\"\"}",
              "[{\"target\":\"/fixtures/events/e1/payload/p\",\"character\":\"x\",\"byte_length\":"
                  + spelling
                  + "}]");
      assertEquals(
          "xxxx",
          GeneratorExpander.materialize(input).at("/fixtures/events/e1/payload/p").asText(),
          spelling);
    }
    ObjectNode fractional =
        caseWith(
            "{\"p\":\"\"}",
            "[{\"target\":\"/fixtures/events/e1/payload/p\",\"character\":\"x\",\"byte_length\":4.5}]");

    assertThrows(IllegalArgumentException.class, () -> GeneratorExpander.materialize(fractional));
  }
}
