package com.github.j5ik2o.event.store.adapter.java.conformance;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.io.IOException;

/** 適合テストデータの厳格な JSON の読み方（設計文書 5.2）。 */
final class ConformanceJson {

  private static final ObjectMapper MAPPER =
      JsonMapper.builder()
          .enable(DeserializationFeature.USE_BIG_INTEGER_FOR_INTS)
          .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
          .build();

  private ConformanceJson() {}

  static ObjectMapper mapper() {
    return MAPPER;
  }

  /** 読めなければ source を含む IOException を投げる。重複キー・NaN・Infinity も失敗にする。 */
  static JsonNode readTree(byte[] bytes, String source) throws IOException {
    try {
      return MAPPER.readTree(bytes);
    } catch (IOException e) {
      throw new IOException("JSON を読めない: " + source + ": " + e.getMessage(), e);
    }
  }
}
