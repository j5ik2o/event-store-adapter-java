package com.github.j5ik2o.event.store.adapter.java.conformance;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.io.InputStream;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/** 保存先ごとの必須のケース ID の一覧（設計文書 7.3）。クラスパスの conformance/required-cases.json。 */
final class RequiredCases {

  private static final String RESOURCE = "/conformance/required-cases.json";

  private RequiredCases() {}

  static Map<Backend, Set<String>> load() throws IOException {
    JsonNode root;
    try (InputStream in = RequiredCases.class.getResourceAsStream(RESOURCE)) {
      if (in == null) {
        throw new IOException("試験のリソースがない: " + RESOURCE);
      }
      root = ConformanceJson.readTree(in.readAllBytes(), RESOURCE);
    }
    Map<Backend, Set<String>> required = new EnumMap<>(Backend.class);
    for (Backend backend : Backend.values()) {
      JsonNode list = root.get(backend.dataName());
      if (list == null || !list.isArray()) {
        throw new IOException(RESOURCE + " に配列のキー " + backend.dataName() + " がない");
      }
      Set<String> ids = new LinkedHashSet<>();
      for (JsonNode id : list) {
        if (!id.isTextual()) {
          throw new IOException(RESOURCE + " の " + backend.dataName() + " に文字列でない要素がある");
        }
        ids.add(id.asText());
      }
      required.put(backend, ids);
    }
    return required;
  }
}
