package com.github.j5ik2o.event.store.adapter.java.apisurface;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** api-surface/ の 2 つの JSON を読む。重複キーは失敗にする。 */
final class ApiSurfaceFiles {

  private static final JsonMapper MAPPER =
      JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();

  private ApiSurfaceFiles() {}

  static JsonNode surface() throws IOException {
    return read("api-surface/surface.json");
  }

  static JsonNode mapping() throws IOException {
    return read("api-surface/java.json");
  }

  private static JsonNode read(String path) throws IOException {
    return MAPPER.readTree(Files.readAllBytes(Path.of(path)));
  }
}
