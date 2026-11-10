package com.github.j5ik2o.event.store.adapter.java.dynamodb;

import static org.junit.jupiter.api.Assertions.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

class DynamoDbItemSizeTest {
  @Test
  void stringsAndNamesUseUtf8AndBinaryUsesRawBytes() {
    assertEquals(8, DynamoDbItemSize.estimate(Map.of("名前", AttributeValue.fromS("é"))));
    assertEquals(
        10,
        DynamoDbItemSize.estimate(
            Map.of("payload", AttributeValue.fromB(SdkBytes.fromByteArray(new byte[3])))));
    assertEquals(1, DynamoDbItemSize.estimate(Map.of("s", AttributeValue.fromS(""))));
  }

  @Test
  void everyNumberIsCountedAsTheTwentyOneByteUpperBound() {
    List<String> values =
        List.of(
            "0",
            "1",
            "-1",
            "1000",
            "1.00",
            "1E+12",
            "99000.00",
            "00012300.00",
            Long.toString(Long.MIN_VALUE),
            Long.toString(Long.MAX_VALUE),
            "9".repeat(38));
    for (String value : values) {
      assertEquals(22, DynamoDbItemSize.estimate(Map.of("n", AttributeValue.fromN(value))), value);
    }
  }

  @Test
  void nestedContainersCountTheirNamesAndEachElementOverhead() {
    AttributeValue nested =
        AttributeValue.fromL(
            List.of(
                AttributeValue.fromM(
                    Map.of("é", AttributeValue.fromB(SdkBytes.fromByteArray(new byte[2]))))));
    // events(6), L(3), L element(1), M(3), M element(1), é(2), binary(2).
    assertEquals(18, DynamoDbItemSize.estimate(Map.of("events", nested)));
    assertEquals(4, DynamoDbItemSize.estimate(Map.of("l", AttributeValue.fromL(List.of()))));
    assertEquals(4, DynamoDbItemSize.estimate(Map.of("m", AttributeValue.fromM(Map.of()))));
  }

  @Test
  void journalAndHeadHaveIndependentExactBoundaryEstimates() {
    for (int size : List.of(409600, 409601)) {
      // aid A-x; seq 1; occurred_at 1; empty manifest. Journal overhead is 42 + 2 * 19.
      assertEquals(size, DynamoDbItemSize.estimate(journal(size - 80)));
      // Head overhead: 77 + 3 * 19 for its three numbers.
      Map<String, AttributeValue> head = head(size - 134);
      assertEquals(size, DynamoDbItemSize.estimate(head));
      assertEquals(size - 54, DynamoDbItemSize.estimate(journal(size - 134)));
    }
  }

  static Map<String, AttributeValue> journal(int payloadBytes) {
    return Map.of(
        "aid",
        AttributeValue.fromS("A-x"),
        "seq_nr",
        AttributeValue.fromN("1"),
        "occurred_at",
        AttributeValue.fromN("1"),
        "manifest",
        AttributeValue.fromS(""),
        "payload",
        AttributeValue.fromB(SdkBytes.fromByteArray(new byte[payloadBytes])));
  }

  @Test
  void currentAndHistoryHaveIndependentExactBoundaryEstimates() {
    for (int size : List.of(409600, 409601)) {
      // Names: aid(3), skey(4), seq_nr(6), manifest(8), payload(7), last_updated_at(15).
      // Values: A-x(3), three numbers(21 each), empty manifest(0), raw binary.
      assertEquals(size, DynamoDbItemSize.estimate(snapshot(size - 109, false)));
      // History adds active_history_seq_nr(21) and its number(21); skey has the same width.
      assertEquals(size, DynamoDbItemSize.estimate(snapshot(size - 151, true)));
      assertEquals(size - 42, DynamoDbItemSize.estimate(snapshot(size - 151, false)));
    }
  }

  static Map<String, AttributeValue> snapshot(int payloadBytes, boolean history) {
    Map<String, AttributeValue> item = new LinkedHashMap<>();
    item.put("aid", AttributeValue.fromS("A-x"));
    item.put("skey", AttributeValue.fromN(history ? "1" : "0"));
    item.put("seq_nr", AttributeValue.fromN("1"));
    item.put("manifest", AttributeValue.fromS(""));
    item.put("payload", AttributeValue.fromB(SdkBytes.fromByteArray(new byte[payloadBytes])));
    item.put("last_updated_at", AttributeValue.fromN("0"));
    if (history) item.put("active_history_seq_nr", AttributeValue.fromN("1"));
    return item;
  }

  private static Map<String, AttributeValue> head(int payloadBytes) {
    Map<String, AttributeValue> journal = journal(payloadBytes);
    return Map.of(
        "aid",
        journal.get("aid"),
        "type_name",
        AttributeValue.fromS("A"),
        "seq_nr",
        journal.get("seq_nr"),
        "events",
        AttributeValue.fromL(
            List.of(
                AttributeValue.fromM(
                    Map.of(
                        "seq_nr",
                        journal.get("seq_nr"),
                        "occurred_at",
                        journal.get("occurred_at"),
                        "manifest",
                        journal.get("manifest"),
                        "payload",
                        journal.get("payload"))))));
  }
}
