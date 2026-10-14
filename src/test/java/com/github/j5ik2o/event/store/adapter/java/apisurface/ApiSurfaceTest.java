package com.github.j5ik2o.event.store.adapter.java.apisurface;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 今の公開 API が、共通の API の形（surface.json）を満たすことを確かめる。 */
class ApiSurfaceTest {

  @Test
  void currentPublicApiMatchesTheSurface() throws IOException {
    JsonNode surface = ApiSurfaceFiles.surface();
    JsonNode mapping = ApiSurfaceFiles.mapping();
    ClassLookup lookup = ClassLookup.inPackage(mapping.path("package").asText());

    List<String> problems = ApiSurfaceChecker.check(surface, mapping, lookup);

    assertEquals(Collections.emptyList(), problems);
  }

  @Test
  void mappingTargetsTheSurfaceVersionItWasWrittenFor() throws IOException {
    assertEquals(
        ApiSurfaceFiles.surface().path("version").asText(),
        ApiSurfaceFiles.mapping().path("surface_version").asText());
  }
}
