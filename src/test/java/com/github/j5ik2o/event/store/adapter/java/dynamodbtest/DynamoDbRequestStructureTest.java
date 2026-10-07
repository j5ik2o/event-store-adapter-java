package com.github.j5ik2o.event.store.adapter.java.dynamodbtest;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.dynamodb.model.*;

class DynamoDbRequestStructureTest {
  static JsonNode json(String text) {
    return DynamoDbJson.read(text.getBytes(StandardCharsets.UTF_8));
  }

  @Test
  void updateIgnoresWhitespaceAndClauseOrderButResolvesActualBindings() {
    UpdateItemRequest request =
        UpdateItemRequest.builder()
            .tableName("snapshot")
            .updateExpression("SET #ttl = :expires REMOVE active_history_seq_nr")
            .conditionExpression("attribute_exists(active_history_seq_nr)")
            .expressionAttributeNames(Map.of("#ttl", "ttl"))
            .expressionAttributeValues(Map.of(":expires", AttributeValue.fromN("4102444800")))
            .build();
    JsonNode first = DynamoDbRequestStructure.parse(DynamoDbJson.sdk(request));
    JsonNode reordered =
        DynamoDbRequestStructure.parse(
            DynamoDbJson.sdk(
                request.toBuilder()
                    .updateExpression(" REMOVE  active_history_seq_nr  SET  #ttl=:expires ")
                    .build()));

    assertEquals(first, reordered);
    assertEquals("4102444800", first.at("/update/set/ttl/N").asText());
    assertEquals("active_history_seq_nr", first.at("/update/remove/0").asText());
    assertEquals("ttl", first.at("/expression_attribute_names/#ttl").asText());
    assertEquals("attribute_exists", first.at("/condition/all/0/operator").asText());
  }

  @Test
  void conjunctionOrderAndExactNumericValuesArePreserved() {
    QueryRequest query =
        QueryRequest.builder()
            .tableName("journal")
            .keyConditionExpression("#a = :aid AND #s >= :seq")
            .expressionAttributeNames(Map.of("#a", "aid", "#s", "seq_nr"))
            .expressionAttributeValues(
                Map.of(
                    ":aid",
                    AttributeValue.fromS("User-A"),
                    ":seq",
                    AttributeValue.fromN("9007199254740991")))
            .build();
    JsonNode first = DynamoDbRequestStructure.parse(DynamoDbJson.sdk(query));
    JsonNode second =
        DynamoDbRequestStructure.parse(
            DynamoDbJson.sdk(
                query.toBuilder().keyConditionExpression("#s>=:seq AND #a=:aid").build()));
    assertEquals(first, second);
    assertEquals("9007199254740991", first.at("/key_condition/all/1/argument/N").asText());
  }

  @Test
  void invalidExpressionsAndMissingBindingsFailBeforeTransmission() {
    for (String invalid :
        new String[] {
          "ADD a :x",
          "SET a = :missing",
          "REMOVE #missing",
          "SET a = :x SET b = :x",
          "REMOVE a,",
          "SET a = :x + :x"
        }) {
      assertThrows(
          IllegalArgumentException.class,
          () ->
              DynamoDbRequestStructure.parse(
                  json(
                      "{\"UpdateExpression\":\""
                          + invalid
                          + "\",\"ExpressionAttributeValues\":{\":x\":{\"N\":\"1\"}}}")),
          invalid);
    }
    assertThrows(
        IllegalArgumentException.class,
        () ->
            DynamoDbRequestStructure.parse(
                json("{\"KeyConditionExpression\":\"aid=:aid OR seq_nr>=:seq\"}")));
  }

  @Test
  void sdkJsonCopiesNestedCollectionsAndUnsafeBinarySources() {
    byte[] bytes = {1, 2};
    AttributeValue value =
        AttributeValue.builder()
            .m(Map.of("payload", AttributeValue.fromB(SdkBytes.fromByteArrayUnsafe(bytes))))
            .build();
    JsonNode captured = DynamoDbJson.sdk(value);
    bytes[0] = 9;
    assertEquals("AQI=", captured.at("/M/payload/B").asText());
    JsonNode copy = captured.deepCopy();
    ((com.fasterxml.jackson.databind.node.ObjectNode) copy.get("M")).removeAll();
    assertEquals("AQI=", captured.at("/M/payload/B").asText());
    assertEquals(captured, DynamoDbJson.read(DynamoDbJson.bytes(captured)));
  }

  @Test
  void expressionKeywordsInsideAliasesDoNotBecomeClauses() {
    JsonNode structure =
        DynamoDbRequestStructure.parse(
            json(
                "{\"UpdateExpression\":\"SET #SET=:REMOVE REMOVE #REMOVE\","
                    + "\"ExpressionAttributeNames\":{\"#SET\":\"ttl\",\"#REMOVE\":\"active_history_seq_nr\"},"
                    + "\"ExpressionAttributeValues\":{\":REMOVE\":{\"N\":\"10\"}}}"));
    assertEquals("10", structure.at("/update/set/ttl/N").asText());
    assertEquals("active_history_seq_nr", structure.at("/update/remove/0").asText());
  }
}
