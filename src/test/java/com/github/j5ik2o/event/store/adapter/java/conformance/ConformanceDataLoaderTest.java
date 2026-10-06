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
import java.nio.file.Files;
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

  @Test
  void missingCasesFailsOnCopyAndNamesTheFile(@TempDir Path tempDir) throws IOException {
    Path copy = ConformanceTestFiles.copyOfRealData(tempDir);
    ConformanceTestFiles.replaceInFile(copy.resolve("values/aid.json"), "\"cases\":", "\"caces\":");

    IOException e = assertThrows(IOException.class, () -> ConformanceDataLoader.load(copy));

    assertTrue(e.getMessage().contains("values/aid.json"), e.getMessage());
    assertTrue(e.getMessage().contains("cases"), e.getMessage());
  }

  @Test
  void nonArrayCasesFailsOnCopy(@TempDir Path tempDir) throws IOException {
    Path copy = ConformanceTestFiles.copyOfRealData(tempDir);
    Files.writeString(
        copy.resolve("values/aid.json"),
        "{\"format\":\"values\",\"version\":\"1.0.0\",\"cases\":{}}");

    IOException e = assertThrows(IOException.class, () -> ConformanceDataLoader.load(copy));

    assertTrue(e.getMessage().contains("cases が配列でない"), e.getMessage());
  }

  @Test
  void schemaViolationFailsOnCopyAndNamesTheFile(@TempDir Path tempDir) throws IOException {
    Path copy = ConformanceTestFiles.copyOfRealData(tempDir);
    ConformanceTestFiles.replaceInFile(
        copy.resolve("values/aid.json"), "\"aid-library-format\"", "\"Aid-Library-Format\"");

    IOException e = assertThrows(IOException.class, () -> ConformanceDataLoader.load(copy));

    assertTrue(e.getMessage().contains("スキーマ違反"), e.getMessage());
    assertTrue(e.getMessage().contains("values/aid.json"), e.getMessage());
  }

  @Test
  void schemaIsCheckedBeforeGeneratorsAreExpanded(@TempDir Path tempDir) throws IOException {
    Path copy = ConformanceTestFiles.copyOfRealData(tempDir);
    // byte_length 0 は、スキーマ（minimum 1）にも展開（0 以下を拒む）にも違反する。先に失敗するのはスキーマ。
    ConformanceTestFiles.replaceInFile(
        copy.resolve("dynamodb/write-errors.json"),
        "\"byte_length\": 420000",
        "\"byte_length\": 0");

    IOException e = assertThrows(IOException.class, () -> ConformanceDataLoader.load(copy));

    assertTrue(e.getMessage().contains("スキーマ違反"), e.getMessage());
    assertTrue(e.getMessage().contains("dynamodb/write-errors.json"), e.getMessage());
  }

  @Test
  void schemasAreResolvedFromTheLocalCopyIncludingReferences(@TempDir Path tempDir)
      throws IOException {
    Path copy = ConformanceTestFiles.copyOfRealData(tempDir);
    // values.schema.json は common.schema.json の aggregate-id を $ref で参照している。
    ConformanceTestFiles.replaceInFile(
        copy.resolve("schema/common.schema.json"),
        "\"type_name\": {\n          \"type\": \"string\"",
        "\"type_name\": {\n          \"type\": \"integer\"");

    IOException e = assertThrows(IOException.class, () -> ConformanceDataLoader.load(copy));

    assertTrue(e.getMessage().contains("スキーマ違反"), e.getMessage());
  }

  @Test
  void integralByteLengthSpellingsPassTheSchemaButFractionsDoNot(@TempDir Path tempDir)
      throws IOException {
    for (String spelling : new String[] {"420000.0", "4.2e5", "420000E0"}) {
      Path copy = ConformanceTestFiles.copyOfRealData(tempDir.resolve(spelling.replace('.', '_')));
      ConformanceTestFiles.replaceInFile(
          copy.resolve("dynamodb/write-errors.json"),
          "\"byte_length\": 420000",
          "\"byte_length\": " + spelling);
      ConformanceData data = ConformanceDataLoader.load(copy);
      assertEquals(116, data.cases().size(), spelling);
    }
    Path fractional = ConformanceTestFiles.copyOfRealData(tempDir.resolve("fractional"));
    ConformanceTestFiles.replaceInFile(
        fractional.resolve("dynamodb/write-errors.json"),
        "\"byte_length\": 420000",
        "\"byte_length\": 420000.5");

    IOException e = assertThrows(IOException.class, () -> ConformanceDataLoader.load(fractional));

    assertTrue(e.getMessage().contains("スキーマ違反"), e.getMessage());
  }

  @Test
  void malformedUtf8IsRejectedNotReplaced() {
    byte[] json = {'{', '"', 'a', '"', ':', '"', (byte) 0xC3, 0x28, '"', '}'};

    IOException e =
        assertThrows(IOException.class, () -> ConformanceJson.readTree(json, "bad.json"));

    assertTrue(e.getMessage().contains("UTF-8"), e.getMessage());
    assertTrue(e.getMessage().contains("bad.json"), e.getMessage());
  }

  @Test
  void malformedUtf8InADataFileFailsTheLoad(@TempDir Path tempDir) throws IOException {
    Path copy = ConformanceTestFiles.copyOfRealData(tempDir);
    Path file = copy.resolve("values/aid.json");
    byte[] original = Files.readAllBytes(file);
    byte[] broken = new byte[original.length + 2];
    System.arraycopy(original, 0, broken, 0, original.length);
    broken[original.length] = (byte) 0xC3;
    broken[original.length + 1] = 0x28;
    Files.write(file, broken);

    IOException e = assertThrows(IOException.class, () -> ConformanceDataLoader.load(copy));

    assertTrue(e.getMessage().contains("UTF-8"), e.getMessage());
  }

  @Test
  void emptyInputReadsAsMissingNodeNotNull() throws IOException {
    assertTrue(ConformanceJson.readTree(new byte[0], "empty.json").isMissingNode());
  }
}
