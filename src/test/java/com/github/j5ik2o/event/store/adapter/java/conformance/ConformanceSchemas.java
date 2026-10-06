package com.github.j5ik2o.event.store.adapter.java.conformance;

import com.fasterxml.jackson.databind.JsonNode;
import com.networknt.schema.Error;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SchemaRegistryConfig;
import com.networknt.schema.SpecificationVersion;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * conformance/schema/ の各スキーマ（draft 2020-12）でデータのファイルを検査する（設計文書 5.2）。スキーマは `$id`
 * で手元から登録し、ネットワークへは取りに行かない。
 */
final class ConformanceSchemas {

  private static final String SCHEMA_DIR = "schema";

  private final Map<String, Schema> byFormat;

  private ConformanceSchemas(Map<String, Schema> byFormat) {
    this.byFormat = byFormat;
  }

  /** root/schema/ の全スキーマを `$id` で登録し、format ごとの検査器を作る。 */
  static ConformanceSchemas load(Path root) throws IOException {
    Path dir = root.resolve(SCHEMA_DIR);
    List<Path> files;
    try (Stream<Path> list = Files.list(dir)) {
      files =
          list.filter(p -> p.getFileName().toString().endsWith(".schema.json"))
              .sorted()
              .collect(Collectors.toList());
    }
    Map<String, String> registered = new HashMap<>();
    Map<String, String> idByFormat = new HashMap<>();
    for (Path file : files) {
      String name = file.getFileName().toString();
      JsonNode schema = ConformanceJson.readTree(Files.readAllBytes(file), SCHEMA_DIR + "/" + name);
      String id = schema.path("$id").asText(null);
      if (id == null) {
        throw new IOException("スキーマに $id がない: " + SCHEMA_DIR + "/" + name);
      }
      registered.put(id, ConformanceJson.mapper().writeValueAsString(schema));
      String format = name.substring(0, name.length() - ".schema.json".length());
      idByFormat.put(format, id);
    }
    SchemaRegistryConfig config = SchemaRegistryConfig.builder().losslessNarrowing(true).build();
    SchemaRegistry registry =
        SchemaRegistry.withDefaultDialect(
            SpecificationVersion.DRAFT_2020_12,
            builder -> builder.schemas(registered).schemaRegistryConfig(config));
    Map<String, Schema> byFormat = new HashMap<>();
    for (Map.Entry<String, String> entry : idByFormat.entrySet()) {
      if (!entry.getKey().equals("common")) {
        byFormat.put(entry.getKey(), registry.getSchema(SchemaLocation.of(entry.getValue())));
      }
    }
    return new ConformanceSchemas(byFormat);
  }

  /** format に対応するスキーマで doc を検査する。違反があれば relative を含む IOException を投げる。 */
  void validate(String format, JsonNode doc, String relative) throws IOException {
    Schema schema = byFormat.get(format);
    if (schema == null) {
      throw new IOException("format に対応するスキーマがない: " + relative + ": " + format);
    }
    List<Error> errors = schema.validate(doc);
    if (!errors.isEmpty()) {
      String detail =
          errors.stream()
              .limit(5)
              .map(e -> e.getInstanceLocation() + ": " + e.getMessage())
              .collect(Collectors.joining("; "));
      throw new IOException("スキーマ違反: " + relative + ": " + detail);
    }
  }
}
