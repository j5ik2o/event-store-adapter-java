package com.github.j5ik2o.event.store.adapter.java.dynamodb;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

/** AWS attribute-size estimate for the S/N/B/L/M attributes used by event writes. */
final class DynamoDbItemSize {
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
    if (value.n() != null) {
      // AWS documents one byte per two significant digits, rounded up, plus one byte.
      // This is an estimate, not a guarantee of DynamoDB's internal number encoding.
      int digits = new BigDecimal(value.n()).stripTrailingZeros().precision();
      return (digits + 1L) / 2 + 1;
    }
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
