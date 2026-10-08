package com.github.j5ik2o.event.store.adapter.java.dynamodbtest;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.util.Collection;
import java.util.Map;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.core.SdkField;
import software.amazon.awssdk.core.SdkPojo;
import software.amazon.awssdk.core.util.SdkAutoConstructList;
import software.amazon.awssdk.core.util.SdkAutoConstructMap;

final class DynamoDbJson {
  private static final ObjectMapper MAPPER = new ObjectMapper();

  private DynamoDbJson() {}

  static ObjectMapper mapper() {
    return MAPPER;
  }

  static ObjectNode object() {
    return MAPPER.createObjectNode();
  }

  static JsonNode read(byte[] bytes) {
    try {
      return MAPPER.readTree(bytes);
    } catch (IOException error) {
      throw new IllegalArgumentException("Invalid SDK JSON", error);
    }
  }

  static byte[] bytes(JsonNode json) {
    try {
      return MAPPER.writeValueAsBytes(json);
    } catch (JsonProcessingException error) {
      throw new IllegalArgumentException("Invalid SDK JSON", error);
    }
  }

  /** Uses actual SDK fields (including SDK-supplied defaults), never scenario expectations. */
  static JsonNode sdk(Object value) {
    if (value instanceof SdkPojo) {
      ObjectNode result = object();
      for (SdkField<?> field : ((SdkPojo) value).sdkFields()) {
        Object member = field.getValueOrDefault(value);
        if (member == null
            || member instanceof SdkAutoConstructList
            || member instanceof SdkAutoConstructMap) continue;
        result.set(field.locationName(), sdk(member));
      }
      return result;
    }
    if (value instanceof Map) {
      ObjectNode result = object();
      ((Map<?, ?>) value).forEach((key, member) -> result.set((String) key, sdk(member)));
      return result;
    }
    if (value instanceof Collection) {
      com.fasterxml.jackson.databind.node.ArrayNode result = MAPPER.createArrayNode();
      ((Collection<?>) value).forEach(member -> result.add(sdk(member)));
      return result;
    }
    if (value instanceof SdkBytes)
      return MAPPER
          .getNodeFactory()
          .textNode(java.util.Base64.getEncoder().encodeToString(((SdkBytes) value).asByteArray()));
    return MAPPER.valueToTree(value);
  }
}
