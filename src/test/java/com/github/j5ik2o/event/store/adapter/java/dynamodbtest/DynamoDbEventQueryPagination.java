package com.github.j5ik2o.event.store.adapter.java.dynamodbtest;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.QueryResponse;

/** Corrects oversized Local event pages using only attributes in the actual response. */
final class DynamoDbEventQueryPagination {
  private static final long PAGE_BYTES = 1024 * 1024;

  private DynamoDbEventQueryPagination() {}

  static QueryResponse correct(QueryResponse response) {
    List<Map<String, AttributeValue>> items = response.items();
    long bytes = 0;
    for (int index = 0; index < items.size(); index++) {
      bytes += itemSize(items.get(index));
      if (bytes <= PAGE_BYTES) continue;
      if (index == 0) throw new IllegalStateException("No event item fits in a 1MiB Query page");
      Map<String, AttributeValue> last = items.get(index - 1);
      AttributeValue aid = last.get("aid"), seqNr = last.get("seq_nr");
      if (aid == null || aid.s() == null || aid.s().isEmpty() || seqNr == null || seqNr.n() == null)
        throw new IllegalStateException("Cannot continue an event Query without its journal key");
      return response.toBuilder()
          .items(items.subList(0, index))
          .count(index)
          .scannedCount(index)
          .lastEvaluatedKey(Map.of("aid", aid, "seq_nr", seqNr))
          .build();
    }
    return response;
  }

  private static long itemSize(Map<String, AttributeValue> item) {
    long bytes = 0;
    for (Map.Entry<String, AttributeValue> attribute : item.entrySet())
      bytes += utf8(attribute.getKey()) + valueSize(attribute.getValue());
    return bytes;
  }

  private static long valueSize(AttributeValue value) {
    if (value.s() != null) return utf8(value.s());
    if (value.n() != null) return numberSize(value.n());
    if (value.b() != null) return value.b().asByteBuffer().remaining();
    if (value.bool() != null || value.nul() != null) return 1;
    if (value.hasL()) {
      long bytes = 3;
      for (AttributeValue element : value.l()) bytes += 1 + valueSize(element);
      return bytes;
    }
    if (value.hasM()) return 3 + value.m().size() + itemSize(value.m());
    if (value.hasSs())
      return value.ss().stream().mapToLong(DynamoDbEventQueryPagination::utf8).sum();
    if (value.hasNs())
      return value.ns().stream().mapToLong(DynamoDbEventQueryPagination::numberSize).sum();
    if (value.hasBs()) {
      long bytes = 0;
      for (SdkBytes element : value.bs()) bytes += element.asByteBuffer().remaining();
      return bytes;
    }
    throw new IllegalArgumentException("Unsupported event Query attribute type");
  }

  private static long numberSize(String number) {
    int digits = new BigDecimal(number).stripTrailingZeros().precision();
    return (digits + 1L) / 2 + 1;
  }

  private static int utf8(String value) {
    return value.getBytes(StandardCharsets.UTF_8).length;
  }
}
