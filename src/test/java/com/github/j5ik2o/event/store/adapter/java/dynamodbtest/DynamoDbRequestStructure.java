package com.github.j5ik2o.event.store.adapter.java.dynamodbtest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parses the expression subset used by the next-major contract, rejecting unsupported syntax. */
final class DynamoDbRequestStructure {
  private static final String ATTRIBUTE = "(?:#[A-Za-z0-9_]+|[A-Za-z_][A-Za-z0-9_]*)";
  private static final Pattern CLAUSE = Pattern.compile("(?i)(?<![#:\\w])(SET|REMOVE)(?!\\w)");
  private static final Pattern ASSIGN =
      Pattern.compile("(" + ATTRIBUTE + ")\\s*=\\s*(:[A-Za-z0-9_]+)");
  private static final Pattern EXISTS =
      Pattern.compile(
          "(?i)(attribute_exists|attribute_not_exists)\\s*\\(\\s*(" + ATTRIBUTE + ")\\s*\\)");
  private static final Pattern COMPARE =
      Pattern.compile("(" + ATTRIBUTE + ")\\s*(>=|=)\\s*(:[A-Za-z0-9_]+)");

  private DynamoDbRequestStructure() {}

  static ObjectNode parse(JsonNode request) {
    ObjectNode result = DynamoDbJson.object();
    if (request.has("UpdateExpression")) {
      Map<String, JsonNode> set = new TreeMap<>();
      TreeSet<String> remove = new TreeSet<>();
      String expression = request.get("UpdateExpression").asText();
      Matcher clauses = CLAUSE.matcher(expression);
      List<String> kinds = new ArrayList<>();
      List<Integer> starts = new ArrayList<>();
      List<Integer> ends = new ArrayList<>();
      while (clauses.find()) {
        kinds.add(clauses.group(1).toUpperCase(java.util.Locale.ROOT));
        starts.add(clauses.start());
        ends.add(clauses.end());
      }
      if (kinds.isEmpty()
          || !expression.substring(0, starts.get(0)).isBlank()
          || new TreeSet<>(kinds).size() != kinds.size()) throw invalid(expression);
      for (int i = 0; i < kinds.size(); i++) {
        String body =
            expression
                .substring(
                    ends.get(i), i + 1 < kinds.size() ? starts.get(i + 1) : expression.length())
                .trim();
        for (String part : body.split(",", -1)) {
          if (kinds.get(i).equals("SET")) {
            Matcher assign = ASSIGN.matcher(part.trim());
            if (!assign.matches()) throw invalid(part);
            String attribute = attribute(assign.group(1), request);
            if (set.put(attribute, value(assign.group(2), request)) != null) throw invalid(part);
          } else {
            if (!part.trim().matches(ATTRIBUTE)) throw invalid(part);
            if (!remove.add(attribute(part.trim(), request))) throw invalid(part);
          }
        }
      }
      ObjectNode update = result.putObject("update");
      ObjectNode assignments = update.putObject("set");
      set.forEach(assignments::set);
      update.set("remove", DynamoDbJson.mapper().valueToTree(remove));
    }
    if (request.has("ConditionExpression")) {
      result.set(
          "condition", conjunction(request.get("ConditionExpression").asText(), request, false));
    }
    if (request.has("KeyConditionExpression")) {
      result.set(
          "key_condition",
          conjunction(request.get("KeyConditionExpression").asText(), request, true));
    }
    if (request.has("ExpressionAttributeNames")) {
      result.set("expression_attribute_names", request.get("ExpressionAttributeNames").deepCopy());
    }
    if (request.has("ExpressionAttributeValues")) {
      result.set(
          "expression_attribute_values", request.get("ExpressionAttributeValues").deepCopy());
    }
    if (request.has("TransactItems")) {
      for (JsonNode action : request.get("TransactItems")) {
        Iterator<JsonNode> values = action.elements();
        if (action.size() != 1) throw invalid(action.toString());
        result.withArray("actions").add(parse(values.next()));
      }
    }
    return result;
  }

  private static JsonNode conjunction(String expression, JsonNode request, boolean key) {
    Map<String, JsonNode> terms = new TreeMap<>();
    for (String text : expression.trim().split("(?i)\\s+AND\\s+", -1)) {
      Matcher exists = EXISTS.matcher(text.trim());
      Matcher compare = COMPARE.matcher(text.trim());
      ObjectNode term = DynamoDbJson.object();
      if (!key && exists.matches()) {
        term.put("attribute", attribute(exists.group(2), request));
        term.put("operator", exists.group(1).toLowerCase(java.util.Locale.ROOT));
      } else if (compare.matches()) {
        term.put("attribute", attribute(compare.group(1), request));
        term.put("operator", compare.group(2).equals("=") ? "eq" : "gte");
        term.set("argument", value(compare.group(3), request));
      } else throw invalid(text);
      if (terms.put(term.toString(), term) != null) throw invalid(text);
    }
    ObjectNode result = DynamoDbJson.object();
    terms.values().forEach(result.putArray("all")::add);
    return result;
  }

  private static String attribute(String token, JsonNode request) {
    if (!token.startsWith("#")) return token;
    JsonNode name = request.path("ExpressionAttributeNames").get(token);
    if (name == null || !name.isTextual()) throw invalid("Unbound attribute " + token);
    return name.asText();
  }

  private static JsonNode value(String token, JsonNode request) {
    JsonNode value = request.path("ExpressionAttributeValues").get(token);
    if (value == null) throw invalid("Unbound value " + token);
    return value.deepCopy();
  }

  private static IllegalArgumentException invalid(String expression) {
    return new IllegalArgumentException(
        "Unsupported or invalid DynamoDB expression: " + expression);
  }
}
