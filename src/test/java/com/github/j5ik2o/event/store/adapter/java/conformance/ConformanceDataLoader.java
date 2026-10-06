package com.github.j5ik2o.event.store.adapter.java.conformance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** conformance/ の JSON を読み、format と version を確かめ、ケースを集める。 */
final class ConformanceDataLoader {

  private static final Set<String> KNOWN_FORMATS =
      Set.of("values", "scenarios", "layout", "coverage", "manifest");
  private static final Set<String> CASE_FORMATS = Set.of("values", "scenarios", "layout");

  private ConformanceDataLoader() {}

  static ConformanceData load(Path root) throws IOException {
    List<Path> files;
    try (Stream<Path> walk = Files.walk(root)) {
      files =
          walk.filter(Files::isRegularFile)
              .filter(p -> p.getFileName().toString().endsWith(".json"))
              .sorted()
              .collect(Collectors.toList());
    }
    ConformanceSchemas schemas = ConformanceSchemas.load(root);
    List<ConformanceCase> cases = new ArrayList<>();
    Map<String, String> idToFile = new HashMap<>();
    JsonNode exclusions = null;
    for (Path path : files) {
      String relative = ManifestVerifier.relativePosix(root, path);
      if (relative.startsWith("schema/")) {
        continue;
      }
      JsonNode doc = ConformanceJson.readTree(Files.readAllBytes(path), relative);
      String format = doc.path("format").asText(null);
      if (format == null || !KNOWN_FORMATS.contains(format)) {
        throw new IOException("未知の format: " + relative + ": " + format);
      }
      JsonNode caseList = doc.get("cases");
      if (CASE_FORMATS.contains(format) && (caseList == null || !caseList.isArray())) {
        throw new IOException("cases が配列でない: " + relative);
      }
      schemas.validate(format, doc, relative);
      String version = doc.path("version").asText(null);
      if (!ManifestVerifier.DATA_VERSION.equals(version)) {
        throw new IOException("未対応の version: " + relative + ": " + version);
      }
      if ("coverage".equals(format)) {
        exclusions = doc.path("exclusions");
      }
      if (!CASE_FORMATS.contains(format)) {
        continue;
      }
      for (JsonNode node : caseList) {
        if (!(node instanceof ObjectNode)) {
          throw new IOException("ケースがオブジェクトでない: " + relative);
        }
        ObjectNode raw = (ObjectNode) node;
        String id = raw.path("id").asText(null);
        if (id == null) {
          throw new IOException("ケースに id がない: " + relative);
        }
        String previous = idToFile.put(id, relative);
        if (previous != null) {
          throw new IOException("ケース ID が重複: " + id + " (" + previous + ", " + relative + ")");
        }
        List<String> rules = new ArrayList<>();
        raw.path("rules").forEach(r -> rules.add(r.asText()));
        cases.add(
            new ConformanceCase(
                id, relative, format, rules, raw, GeneratorExpander.materialize(raw)));
      }
    }
    if (exclusions == null) {
      throw new IOException("coverage.json が見つからない");
    }
    return new ConformanceData(ManifestVerifier.DATA_VERSION, cases, exclusions);
  }
}
