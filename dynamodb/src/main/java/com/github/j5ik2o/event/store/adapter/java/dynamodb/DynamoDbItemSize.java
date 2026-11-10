package com.github.j5ik2o.event.store.adapter.java.dynamodb;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

/** Upper-bound size of the S/N/B/L/M attributes used by event writes. */
final class DynamoDbItemSize {
  /** Fixed per-number bound shared with the rs and go adapters, whatever the actual digits. */
  private static final long NUMBER_MAX_BYTES = 21;

  private DynamoDbItemSize() {}

  static long estimate(Map<String, AttributeValue> item) {
    long bytes = 0;
    for (Map.Entry<String, AttributeValue> attribute : item.entrySet()) {
      bytes += utf8(attribute.getKey()) + valueSize(attribute.getValue());
    }
    return bytes;
  }

  private static long valueSize(AttributeValue value) {
    if (value.s() != null) return utf8(value.s());
    if (value.n() != null) return NUMBER_MAX_BYTES;
    if (value.b() != null) return value.b().asByteBuffer().remaining();
    if (value.hasL()) {
      long bytes = 3;
      for (AttributeValue element : value.l()) bytes += 1 + valueSize(element);
      return bytes;
    }
    if (value.hasM()) return 3 + value.m().size() + estimate(value.m());
    throw new IllegalArgumentException("Unsupported event-write attribute type");
  }

  private static int utf8(String value) {
    return value.getBytes(StandardCharsets.UTF_8).length;
  }
}
