package com.github.j5ik2o.event.store.adapter.java.dynamodbtest;

import static com.github.j5ik2o.event.store.adapter.java.dynamodbtest.DynamoDbRequestStructureTest.json;
import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class DynamoDbRequestScopeTest {
  private static final Set<String> APIS =
      Set.of(
          "GetItem", "BatchGetItem", "Query", "TransactWriteItems", "BatchWriteItem", "UpdateItem");

  private static List<String> violations(String api, String body) {
    return DynamoDbRequestScope.violations(APIS, "Order-9", api, json(body));
  }

  @Test
  void requestOfAllowedApiTargetingTheAggregateHasNoViolation() {
    assertEquals(
        List.of(),
        violations(
            "GetItem",
            "{\"TableName\":\"s\",\"Key\":{\"aid\":{\"S\":\"Order-9\"},\"skey\":{\"N\":\"0\"}}}"));
  }

  @Test
  void operationWithoutAnyRecordedRequestFails() {
    AssertionError error =
        assertThrows(
            AssertionError.class,
            () ->
                DynamoDbRequestScope.check(
                    json("{\"apis\":[\"Query\"],\"aid\":\"aggregate_id\"}"), "Order-9", List.of()));
    assertTrue(error.getMessage().contains("no requests were recorded"));
  }

  @Test
  void apiOutsideTheAllowedSetIsAViolation() {
    List<String> result = violations("Scan", "{\"TableName\":\"s\"}");
    assertFalse(result.isEmpty());
    assertTrue(result.stream().anyMatch(v -> v.contains("Scan")), result.toString());
  }

  @Test
  void exclusiveStartKeyOfAnotherAggregateDoesNotCountWhenKeyConditionMatches() {
    assertEquals(
        List.of(),
        violations(
            "Query",
            "{\"KeyConditionExpression\":\"aid = :aid AND seq_nr >= :seq_nr\","
                + "\"ExpressionAttributeValues\":{\":aid\":{\"S\":\"Order-9\"},\":seq_nr\":{\"N\":\"1\"}},"
                + "\"ExclusiveStartKey\":{\"aid\":{\"S\":\"Customer-5\"},\"seq_nr\":{\"N\":\"2\"}}}"));
  }

  @Test
  void transactionWhoseEveryItemTargetsTheAggregateHasNoViolation() {
    assertEquals(
        List.of(),
        violations(
            "TransactWriteItems",
            "{\"TransactItems\":[{\"Put\":{\"Item\":{\"aid\":{\"S\":\"Order-9\"}}}},"
                + "{\"Update\":{\"Key\":{\"aid\":{\"S\":\"Order-9\"}}}}]}"));
  }

  @Test
  void queryKeyConditionOfAnotherAggregateIsAViolation() {
    List<String> result =
        violations(
            "Query",
            "{\"KeyConditionExpression\":\"aid = :aid\","
                + "\"ExpressionAttributeValues\":{\":aid\":{\"S\":\"Customer-5\"}}}");
    assertEquals(1, result.size(), result.toString());
    assertTrue(result.get(0).contains("Customer-5"), result.toString());
  }

  @Test
  void singleTransactionItemOfAnotherAggregateIsAViolation() {
    List<String> result =
        violations(
            "TransactWriteItems",
            "{\"TransactItems\":[{\"Put\":{\"Item\":{\"aid\":{\"S\":\"Order-9\"}}}},"
                + "{\"Update\":{\"Key\":{\"aid\":{\"S\":\"Customer-5\"}}}}]}");
    assertEquals(1, result.size(), result.toString());
    assertTrue(result.get(0).contains("Customer-5"), result.toString());
  }

  @Test
  void queryWithoutKeyConditionIsAViolation() {
    List<String> result = violations("Query", "{\"TableName\":\"j\"}");
    assertEquals(1, result.size(), result.toString());
    assertTrue(result.get(0).contains("KeyCondition"), result.toString());
  }

  @Test
  void everyAllowedApiAcceptsARequestTargetingTheAggregate() {
    assertEquals(
        List.of(),
        violations("UpdateItem", "{\"TableName\":\"s\",\"Key\":{\"aid\":{\"S\":\"Order-9\"}}}"));
    assertEquals(
        List.of(),
        violations(
            "BatchGetItem",
            "{\"RequestItems\":{\"h\":{\"Keys\":[{\"aid\":{\"S\":\"Order-9\"}}]}}}"));
    assertEquals(
        List.of(),
        violations(
            "BatchWriteItem",
            "{\"RequestItems\":{\"s\":[{\"PutRequest\":{\"Item\":{\"aid\":{\"S\":\"Order-9\"}}}},"
                + "{\"DeleteRequest\":{\"Key\":{\"aid\":{\"S\":\"Order-9\"}}}}]}}"));
    assertEquals(
        List.of(),
        violations(
            "Query",
            "{\"KeyConditionExpression\":\"aid = :aid\","
                + "\"ExpressionAttributeValues\":{\":aid\":{\"S\":\"Order-9\"}}}"));
  }

  @Test
  void keyOfAnotherAggregateIsAViolationInEveryKeyBearingApi() {
    assertFalse(
        violations("GetItem", "{\"TableName\":\"s\",\"Key\":{\"aid\":{\"S\":\"Customer-5\"}}}")
            .isEmpty());
    assertFalse(
        violations(
                "BatchGetItem",
                "{\"RequestItems\":{\"h\":{\"Keys\":[{\"aid\":{\"S\":\"Order-9\"}},{\"aid\":{\"S\":\"Customer-5\"}}]}}}")
            .isEmpty());
    assertFalse(
        violations(
                "BatchWriteItem",
                "{\"RequestItems\":{\"s\":[{\"DeleteRequest\":{\"Key\":{\"aid\":{\"S\":\"Customer-5\"}}}}]}}")
            .isEmpty());
  }
}
