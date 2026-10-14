package com.github.j5ik2o.event.store.adapter.java.dynamodbtest;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 適合データの {@code observe.request_scope} の判定。ライブラリが送った要求の API 名がすべて許可されており、各要求が読み書きする項目の aid
 * がすべて操作の集約の aid であることを確かめる。
 */
final class DynamoDbRequestScope {
  private DynamoDbRequestScope() {}

  /** 記録した全要求を判定し、違反が 1 件でもあれば失敗させる。 */
  static void check(JsonNode scope, String aid, List<DynamoDbRequestRecorder.Request> requests) {
    Set<String> apis = new HashSet<>();
    scope.path("apis").forEach(api -> apis.add(api.asText()));
    List<String> all = new ArrayList<>();
    for (DynamoDbRequestRecorder.Request request : requests)
      all.addAll(violations(apis, aid, request.api, request.marshalled));
    assertTrue(all.isEmpty(), String.join("; ", all));
  }

  static List<String> violations(Set<String> apis, String aid, String api, JsonNode body) {
    List<String> result = new ArrayList<>();
    if (!apis.contains(api)) {
      result.add("API not allowed in request_scope: " + api);
      return result;
    }
    List<JsonNode> keyHolders = new ArrayList<>();
    switch (api) {
      case "GetItem":
      case "UpdateItem":
        keyHolders.add(body.path("Key"));
        break;
      case "BatchGetItem":
        body.path("RequestItems").forEach(table -> table.path("Keys").forEach(keyHolders::add));
        break;
      case "TransactWriteItems":
        body.path("TransactItems")
            .forEach(
                action -> {
                  keyHolders.add(action.path("Put").path("Item"));
                  keyHolders.add(action.path("Update").path("Key"));
                  keyHolders.add(action.path("Delete").path("Key"));
                  keyHolders.add(action.path("ConditionCheck").path("Key"));
                });
        break;
      case "BatchWriteItem":
        body.path("RequestItems")
            .forEach(
                table ->
                    table.forEach(
                        write -> {
                          keyHolders.add(write.path("PutRequest").path("Item"));
                          keyHolders.add(write.path("DeleteRequest").path("Key"));
                        }));
        break;
      case "Query":
        queryViolations(aid, body, result);
        return result;
      default:
        result.add("API has no aid extraction rule: " + api);
        return result;
    }
    int items = 0;
    for (JsonNode holder : keyHolders) {
      if (holder.isMissingNode()) continue;
      items++;
      JsonNode value = holder.path("aid");
      if (!value.path("S").isTextual()) result.add(api + " item has no string aid: " + holder);
      else if (!value.path("S").asText().equals(aid))
        result.add(api + " touches another aggregate: " + value.path("S").asText());
    }
    if (items == 0) result.add(api + " has no item to check for aid");
    return result;
  }

  private static void queryViolations(String aid, JsonNode body, List<String> result) {
    JsonNode condition;
    try {
      condition = DynamoDbRequestStructure.parse(body).path("key_condition").path("all");
    } catch (IllegalArgumentException e) {
      result.add("Query key condition cannot be parsed: " + e.getMessage());
      return;
    }
    if (!condition.isArray() || condition.isEmpty()) {
      result.add("Query has no KeyConditionExpression");
      return;
    }
    int equalities = 0;
    for (JsonNode term : condition) {
      if (!term.path("attribute").asText().equals("aid")
          || !term.path("operator").asText().equals("eq")) continue;
      equalities++;
      JsonNode value = term.path("argument").path("S");
      if (!value.isTextual()) result.add("Query aid condition is not a string: " + term);
      else if (!value.asText().equals(aid))
        result.add("Query touches another aggregate: " + value.asText());
    }
    if (equalities == 0) result.add("Query key condition has no aid equality");
  }
}
