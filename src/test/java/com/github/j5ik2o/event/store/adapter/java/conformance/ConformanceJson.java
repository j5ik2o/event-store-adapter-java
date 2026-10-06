package com.github.j5ik2o.event.store.adapter.java.conformance;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.MissingNode;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

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

  /**
   * 読めなければ source を含む IOException を投げる。重複キー・NaN・Infinity・不正な UTF-8 も失敗にする。空の入力は MissingNode
   * を返す（null は返さない）。
   */
  static JsonNode readTree(byte[] bytes, String source) throws IOException {
    String text;
    try {
      text =
          StandardCharsets.UTF_8
              .newDecoder()
              .onMalformedInput(CodingErrorAction.REPORT)
              .onUnmappableCharacter(CodingErrorAction.REPORT)
              .decode(ByteBuffer.wrap(bytes))
              .toString();
    } catch (CharacterCodingException e) {
      throw new IOException("UTF-8 として不正: " + source + ": " + e.getMessage(), e);
    }
    try {
      JsonNode node = MAPPER.readTree(text);
      return node == null ? MissingNode.getInstance() : node;
    } catch (IOException e) {
      throw new IOException("JSON を読めない: " + source + ": " + e.getMessage(), e);
    }
  }
}
