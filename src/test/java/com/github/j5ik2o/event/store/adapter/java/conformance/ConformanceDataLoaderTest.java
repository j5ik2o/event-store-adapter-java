package com.github.j5ik2o.event.store.adapter.java.conformance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConformanceDataLoaderTest {

  private static ConformanceCase find(ConformanceData data, String id) {
    return data.cases().stream()
        .filter(c -> c.id().equals(id))
        .findFirst()
        .orElseThrow(() -> new AssertionError("case not found: " + id));
  }

  @Test
  void loadsAllHundredSixteenCasesWithUniqueIds() throws IOException {
    ConformanceData data = ConformanceDataLoader.load(ConformanceTestFiles.REAL_ROOT);

    assertEquals(116, data.cases().size());
    Set<String> ids = new HashSet<>();
    data.cases().forEach(c -> assertTrue(ids.add(c.id()), "duplicated id: " + c.id()));
    assertEquals("1.0.0", data.dataVersion());
    assertNotNull(data.coverageExclusions());
  }

  @Test
  void unknownVersionFailsOnCopy(@TempDir Path tempDir) throws IOException {
    Path copy = ConformanceTestFiles.copyOfRealData(tempDir);
    ConformanceTestFiles.replaceInFile(
        copy.resolve("values/aid.json"), "\"version\": \"1.0.0\"", "\"version\": \"9.9.9\"");

    assertThrows(Exception.class, () -> ConformanceDataLoader.load(copy));
  }

  @Test
  void unknownFormatFailsOnCopy(@TempDir Path tempDir) throws IOException {
    Path copy = ConformanceTestFiles.copyOfRealData(tempDir);
    ConformanceTestFiles.replaceInFile(
        copy.resolve("values/aid.json"), "\"format\": \"values\"", "\"format\": \"unknown\"");

    assertThrows(Exception.class, () -> ConformanceDataLoader.load(copy));
  }

  @Test
  void seqNrKeepsArbitraryPrecisionIntegers() throws IOException {
    ConformanceData data = ConformanceDataLoader.load(ConformanceTestFiles.REAL_ROOT);

    JsonNode negative = find(data, "seq-negative-value").materialized().at("/input/seq_nr");
    JsonNode above = find(data, "seq-above-max-value").materialized().at("/input/seq_nr");

    assertTrue(negative.isBigInteger(), negative.getNodeType().toString());
    assertEquals(BigInteger.valueOf(-1), negative.bigIntegerValue());
    assertTrue(above.isBigInteger(), above.getNodeType().toString());
    assertEquals(BigInteger.TWO.pow(53), above.bigIntegerValue());
  }

  @Test
  void duplicateKeysAreRejected() {
    byte[] json = "{\"a\":1,\"a\":2}".getBytes(StandardCharsets.UTF_8);

    assertThrows(Exception.class, () -> ConformanceJson.readTree(json, "dup.json"));
  }

  @Test
  void nanIsRejected() {
    byte[] json = "{\"a\":NaN}".getBytes(StandardCharsets.UTF_8);

    assertThrows(Exception.class, () -> ConformanceJson.readTree(json, "nan.json"));
  }

  @Test
  void generatorsInRealDataExpandToFourHundredTwentyThousandBytes() throws IOException {
    ConformanceData data = ConformanceDataLoader.load(ConformanceTestFiles.REAL_ROOT);

    ConformanceCase c =
        data.cases().stream()
            .filter(x -> x.file().equals("dynamodb/write-errors.json"))
            .filter(x -> x.raw().has("generators"))
            .filter(x -> x.raw().at("/fixtures/events/e1/payload").isTextual())
            .findFirst()
            .orElseThrow(() -> new AssertionError("generator case not found"));

    String expanded = c.materialized().at("/fixtures/events/e1/payload").asText();
    assertEquals(420000, expanded.getBytes(StandardCharsets.UTF_8).length);
    assertEquals("", c.raw().at("/fixtures/events/e1/payload").asText());
    assertFalse(expanded.isEmpty());
  }
}
