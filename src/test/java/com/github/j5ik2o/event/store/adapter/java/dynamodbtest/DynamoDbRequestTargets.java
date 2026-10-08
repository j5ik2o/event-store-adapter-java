package com.github.j5ik2o.event.store.adapter.java.dynamodbtest;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

final class DynamoDbRequestTargets {
  private final Map<String, String> roles;
  private final String historyIndex;

  DynamoDbRequestTargets(String journal, String snapshot, String head, String historyIndex) {
    roles = Map.of(journal, "journal", snapshot, "snapshot", head, "head");
    this.historyIndex = historyIndex;
  }

  String role(String table) {
    String role = roles.get(table);
    if (role == null) throw new IllegalArgumentException("Unknown test table: " + table);
    return role;
  }

  String phase(String api, JsonNode request, FaultRegistry.Operation operation) {
    if (operation.writing
        && operation.transactionSeen
        && List.of("GetItem", "BatchGetItem").contains(api)) {
      return "classify-condition-failure-read";
    }
    switch (api) {
      case "BatchGetItem":
        List<Boolean> configKeys = new ArrayList<>();
        request
            .path("RequestItems")
            .fields()
            .forEachRemaining(
                entry -> {
                  role(entry.getKey());
                  for (JsonNode key : entry.getValue().path("Keys")) configKeys.add(config(key));
                });
        if (configKeys.isEmpty()) throw new IllegalArgumentException("Empty BatchGetItem");
        if (configKeys.stream().allMatch(Boolean::booleanValue)) return "configuration-read";
        if (configKeys.stream().anyMatch(Boolean::booleanValue)) {
          throw new IllegalArgumentException("Mixed configuration and aggregate keys");
        }
        return "read-snapshot";
      case "TransactWriteItems":
        List<String> targets = transactionTargets(request);
        boolean configuration = targets.stream().allMatch(t -> t.startsWith("configuration:"));
        if (!configuration && targets.stream().anyMatch(t -> t.startsWith("configuration:"))) {
          throw new IllegalArgumentException("Mixed configuration and aggregate actions");
        }
        operation.transactionSeen = true;
        return configuration ? "configuration-create" : "commit";
      case "Query":
        String role = role(request.path("TableName").asText());
        if (request.has("IndexName")) {
          if (!role.equals("snapshot") || !request.get("IndexName").asText().equals(historyIndex)) {
            throw new IllegalArgumentException("Unknown history index");
          }
          return "retention-query";
        }
        if (!role.equals("journal")) throw new IllegalArgumentException("Unexpected Query target");
        return operation.writing && operation.transactionSeen
            ? "classify-condition-failure-read"
            : "read-events";
      case "BatchWriteItem":
        request
            .path("RequestItems")
            .fieldNames()
            .forEachRemaining(
                table -> {
                  if (!role(table).equals("snapshot"))
                    throw new IllegalArgumentException("Unexpected delete target");
                });
        return "retention-delete";
      case "UpdateItem":
        JsonNode structure = DynamoDbRequestStructure.parse(request);
        if (role(request.path("TableName").asText()).equals("snapshot")
            && structure.path("update").path("set").has("ttl")) return "retention-mark";
        throw new IllegalArgumentException("Unexpected UpdateItem target");
      case "GetItem":
        role(request.path("TableName").asText());
        return config(request.path("Key")) ? "configuration-read" : "read-snapshot";
      case "CreateTable":
      case "DeleteTable":
      case "DescribeTable":
      case "ListTables":
        return api; // Resource-lifecycle probes, outside storage conformance.
      default:
        throw new IllegalArgumentException("Unsupported observed API: " + api);
    }
  }

  List<String> transactionTargets(JsonNode request) {
    List<String> result = new ArrayList<>();
    for (JsonNode action : request.path("TransactItems")) {
      if (action.size() != 1) throw new IllegalArgumentException("Invalid transaction action");
      Map.Entry<String, JsonNode> entry = action.fields().next();
      JsonNode body = entry.getValue();
      String role = role(body.path("TableName").asText());
      JsonNode key = body.path(entry.getKey().equals("Put") ? "Item" : "Key");
      if (config(key)) result.add("configuration:" + role);
      else if (role.equals("snapshot")) {
        String number = key.path("skey").path("N").asText();
        if (number.isEmpty()) throw new IllegalArgumentException("Missing snapshot sort key");
        result.add(new BigDecimal(number).signum() == 0 ? "current-snapshot" : "history-snapshot");
      } else result.add(role);
    }
    if (result.isEmpty() || result.stream().distinct().count() != result.size()) {
      throw new IllegalArgumentException("Missing or ambiguous transaction targets");
    }
    return result;
  }

  static boolean config(JsonNode key) {
    return key.path("aid").path("S").asText().equals("__config__");
  }
}
