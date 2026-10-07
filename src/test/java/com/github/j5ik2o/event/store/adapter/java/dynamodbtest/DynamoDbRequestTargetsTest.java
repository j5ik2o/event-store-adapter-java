package com.github.j5ik2o.event.store.adapter.java.dynamodbtest;

import static com.github.j5ik2o.event.store.adapter.java.dynamodbtest.DynamoDbRequestStructureTest.json;
import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class DynamoDbRequestTargetsTest {
  private final DynamoDbRequestTargets targets =
      new DynamoDbRequestTargets("j", "s", "h", "history");

  @Test
  void structureDeterminesEverySdkPhaseAndAdditionalClassificationRead() {
    FaultRegistry registry = new FaultRegistry();
    FaultRegistry.Operation operation = registry.begin(0, false);
    assertEquals(
        "configuration-read",
        targets.phase(
            "BatchGetItem",
            json("{\"RequestItems\":{\"h\":{\"Keys\":[{\"aid\":{\"S\":\"__config__\"}}]}}}"),
            operation));
    assertEquals(
        "read-snapshot",
        targets.phase(
            "BatchGetItem",
            json("{\"RequestItems\":{\"h\":{\"Keys\":[{\"aid\":{\"S\":\"User-A\"}}]}}}"),
            operation));
    assertEquals(
        "configuration-create",
        targets.phase(
            "TransactWriteItems",
            json(
                "{\"TransactItems\":[{\"Put\":{\"TableName\":\"h\",\"Item\":{\"aid\":{\"S\":\"__config__\"}}}}]}"),
            operation));
    assertEquals(
        "commit",
        targets.phase(
            "TransactWriteItems",
            json(
                "{\"TransactItems\":[{\"Put\":{\"TableName\":\"j\",\"Item\":{\"aid\":{\"S\":\"User-A\"}}}}]}"),
            operation));
    assertEquals("read-events", targets.phase("Query", json("{\"TableName\":\"j\"}"), operation));
    assertEquals(
        "retention-query",
        targets.phase("Query", json("{\"TableName\":\"s\",\"IndexName\":\"history\"}"), operation));
    assertEquals(
        "retention-delete",
        targets.phase("BatchWriteItem", json("{\"RequestItems\":{\"s\":[]}}"), operation));
    assertEquals(
        "retention-mark",
        targets.phase(
            "UpdateItem",
            json(
                "{\"TableName\":\"s\",\"UpdateExpression\":\"SET #expiry=:expiry\",\"ExpressionAttributeNames\":{\"#expiry\":\"ttl\"},\"ExpressionAttributeValues\":{\":expiry\":{\"N\":\"4102444800\"}}}"),
            operation));
    registry.finish(operation);
    FaultRegistry.Operation write = registry.begin(1, true);
    assertEquals("read-events", targets.phase("Query", json("{\"TableName\":\"j\"}"), write));
    assertEquals(
        "retention-query",
        targets.phase("Query", json("{\"TableName\":\"s\",\"IndexName\":\"history\"}"), write));
    targets.phase(
        "TransactWriteItems",
        json(
            "{\"TransactItems\":[{\"Put\":{\"TableName\":\"h\",\"Item\":{\"aid\":{\"S\":\"User-A\"}}}}]}"),
        write);
    for (String api : List.of("GetItem", "BatchGetItem")) {
      assertEquals("classify-condition-failure-read", targets.phase(api, json("{}"), write));
    }
    assertEquals(
        "classify-condition-failure-read",
        targets.phase("Query", json("{\"TableName\":\"j\"}"), write));
    assertEquals(
        "retention-query",
        targets.phase("Query", json("{\"TableName\":\"s\",\"IndexName\":\"history\"}"), write));
    for (String request :
        List.of(
            "{\"TableName\":\"s\",\"IndexName\":\"wrong\"}",
            "{\"TableName\":\"j\",\"IndexName\":\"history\"}",
            "{\"TableName\":\"unknown\",\"IndexName\":\"history\"}",
            "{\"TableName\":\"s\"}")) {
      assertThrows(
          IllegalArgumentException.class, () -> targets.phase("Query", json(request), write));
    }
  }

  @Test
  void transactionTargetsFollowActualActionOrder() {
    assertEquals(
        List.of("history-snapshot", "head", "current-snapshot", "journal"),
        targets.transactionTargets(
            json(
                "{\"TransactItems\":["
                    + "{\"Put\":{\"TableName\":\"s\",\"Item\":{\"skey\":{\"N\":\"3\"}}}},"
                    + "{\"Update\":{\"TableName\":\"h\",\"Key\":{}}},"
                    + "{\"Put\":{\"TableName\":\"s\",\"Item\":{\"skey\":{\"N\":\"0\"}}}},"
                    + "{\"Put\":{\"TableName\":\"j\",\"Item\":{}}}]}")));
  }

  @Test
  void unknownTargetsAndAmbiguousActionsAreRejected() {
    assertThrows(IllegalArgumentException.class, () -> targets.role("unknown"));
    assertThrows(
        IllegalArgumentException.class,
        () -> targets.transactionTargets(json("{\"TransactItems\":[]}")));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            targets.phase(
                "Query",
                json("{\"TableName\":\"s\",\"IndexName\":\"wrong\"}"),
                new FaultRegistry().begin(1, false)));
  }
}
