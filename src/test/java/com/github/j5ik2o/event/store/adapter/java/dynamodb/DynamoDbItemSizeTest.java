package com.github.j5ik2o.event.store.adapter.java.dynamodb;

import static org.junit.jupiter.api.Assertions.*;

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
  void numberEstimateCountsSignificantDigitsAfterTrimmingZeroes() {
    for (String value : List.of("0", "1", "10", "1000", "1.00", "1E+12", "-1")) {
      assertEquals(3, DynamoDbItemSize.estimate(Map.of("n", AttributeValue.fromN(value))), value);
    }
    for (String value : List.of("99", "-99", "99000.00")) {
      assertEquals(3, DynamoDbItemSize.estimate(Map.of("n", AttributeValue.fromN(value))), value);
    }
    assertEquals(4, DynamoDbItemSize.estimate(Map.of("n", AttributeValue.fromN("00012300.00"))));
    assertEquals(
        12,
        DynamoDbItemSize.estimate(
            Map.of("n", AttributeValue.fromN(Long.toString(Long.MIN_VALUE)))));
    assertEquals(
        12,
        DynamoDbItemSize.estimate(
            Map.of("n", AttributeValue.fromN(Long.toString(Long.MAX_VALUE)))));
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
      // aid A-x; seq 1; occurred_at 1; empty manifest. Journal overhead is 42.
      assertEquals(size, DynamoDbItemSize.estimate(journal(size - 42)));
      // Head overhead: outer names/values 30, containers/elements 11, envelope 36.
      Map<String, AttributeValue> head = head(size - 77);
      assertEquals(size, DynamoDbItemSize.estimate(head));
      assertEquals(size - 35, DynamoDbItemSize.estimate(journal(size - 77)));
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
