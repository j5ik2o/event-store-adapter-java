package com.github.j5ik2o.event.store.adapter.java.dynamodbtest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import software.amazon.awssdk.core.SdkRequest;
import software.amazon.awssdk.core.SdkResponse;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;

final class DynamoDbFaultEffects {
  private DynamoDbFaultEffects() {}

  private abstract static class RequestEffect implements FaultRegistry.Effect {
    @Override
    public boolean supports(FaultRegistry.Injection injection) {
      return injection == FaultRegistry.Injection.REPLACE_REQUEST;
    }
  }

  private abstract static class ResponseEffect implements FaultRegistry.Effect {
    @Override
    public boolean supports(FaultRegistry.Injection injection) {
      return injection == FaultRegistry.Injection.REPLACE_RESPONSE;
    }
  }

  static FaultRegistry.Effect serializationError(RuntimeException error) {
    return new FaultRegistry.Effect() {
      @Override
      public boolean supports(FaultRegistry.Injection injection) {
        return true;
      }

      @Override
      public RuntimeException serializationError() {
        return error;
      }
    };
  }

  static FaultRegistry.Effect sdkError(String code) {
    return new RequestEffect() {
      @Override
      public HttpReply reply(DynamoDbRequestRecorder.Request request) {
        return error(code, DynamoDbJson.object());
      }
    };
  }

  static FaultRegistry.Effect transactionCanceled(
      DynamoDbRequestTargets targets,
      Map<String, String> codes,
      Long oldHeadSeqNr,
      Runnable installItems) {
    return new RequestEffect() {
      @Override
      public void beforeTransmission(DynamoDbRequestRecorder.Request request) {
        installItems.run();
      }

      @Override
      public HttpReply reply(DynamoDbRequestRecorder.Request request) {
        List<String> actual = targets.transactionTargets(request.marshalled);
        if (!actual.containsAll(codes.keySet()) || actual.size() != codes.size()) {
          throw new IllegalArgumentException(
              "Cancellation reasons do not match actual transaction targets");
        }
        ObjectNode body = DynamoDbJson.object();
        ArrayNode reasons = body.putArray("CancellationReasons");
        for (String target : actual) {
          ObjectNode reason = reasons.addObject().put("Code", codes.get(target));
          if (target.equals("head")
              && codes.get(target).equals("ConditionalCheckFailed")
              && oldHeadSeqNr != null) {
            reason.putObject("Item").putObject("seq_nr").put("N", oldHeadSeqNr.toString());
          }
        }
        return error("TransactionCanceledException", body);
      }
    };
  }

  private static HttpReply error(String code, ObjectNode body) {
    int status;
    switch (code) {
      case "InternalServerError":
        status = 500;
        break;
      case "ProvisionedThroughputExceededException":
      case "TransactionCanceledException":
        status = 400;
        break;
      default:
        throw new IllegalArgumentException("Unsupported SDK error code: " + code);
    }
    body.put("__type", "com.amazonaws.dynamodb.v20120810#" + code);
    body.put("Message", "Injected DynamoDB test failure");
    return new HttpReply(status, body);
  }

  static FaultRegistry.Effect response(Function<SdkResponse, SdkResponse> transform) {
    return new ResponseEffect() {
      @Override
      public SdkResponse response(DynamoDbRequestRecorder.Request request, SdkResponse response) {
        return transform.apply(response);
      }
    };
  }

  static FaultRegistry.Effect unprocessedFirst(int count) {
    if (count < 0) throw new IllegalArgumentException("Unprocessed count must be non-negative");
    return new UnprocessedBatchWrite(count);
  }

  static final class UnprocessedBatchWrite extends RequestEffect {
    private final int count;
    private Map<String, List<WriteRequest>> excluded;
    private boolean empty;

    private UnprocessedBatchWrite(int count) {
      this.count = count;
    }

    UnprocessedBatchWrite forRequest() {
      return new UnprocessedBatchWrite(count);
    }

    @Override
    public SdkRequest prepare(SdkRequest request, FaultRegistry.Injection injection) {
      BatchWriteItemRequest batch = (BatchWriteItemRequest) request;
      excluded = new LinkedHashMap<>();
      Map<String, List<WriteRequest>> remainder = new LinkedHashMap<>();
      int left = count;
      for (Map.Entry<String, List<WriteRequest>> entry : batch.requestItems().entrySet()) {
        int take = Math.min(left, entry.getValue().size());
        if (take > 0) excluded.put(entry.getKey(), List.copyOf(entry.getValue().subList(0, take)));
        if (take < entry.getValue().size())
          remainder.put(
              entry.getKey(), List.copyOf(entry.getValue().subList(take, entry.getValue().size())));
        left -= take;
      }
      if (left != 0)
        throw new IllegalArgumentException("Unprocessed count exceeds actual batch size");
      empty = remainder.isEmpty();
      // SDK validates empty batches; the HTTP layer skips the original full batch instead.
      return empty ? request : batch.toBuilder().requestItems(remainder).build();
    }

    @Override
    public HttpReply reply(DynamoDbRequestRecorder.Request request) {
      return empty
          ? new HttpReply(
              200,
              DynamoDbJson.sdk(BatchWriteItemResponse.builder().unprocessedItems(excluded).build()))
          : null;
    }

    @Override
    public SdkResponse afterResponse(
        DynamoDbRequestRecorder.Request request, SdkResponse response) {
      BatchWriteItemResponse batch = (BatchWriteItemResponse) response;
      Map<String, List<WriteRequest>> combined = new LinkedHashMap<>();
      batch
          .unprocessedItems()
          .forEach((table, writes) -> combined.put(table, new ArrayList<>(writes)));
      excluded.forEach(
          (table, writes) ->
              combined.computeIfAbsent(table, key -> new ArrayList<>()).addAll(writes));
      // For an entirely skipped batch the HTTP reply already contains the excluded writes.
      return empty ? response : batch.toBuilder().unprocessedItems(combined).build();
    }
  }

  static FaultRegistry.Effect partialBatchGet(
      DynamoDbClient admin, Map<String, List<Map<String, AttributeValue>>> unprocessed) {
    Map<String, List<Map<String, AttributeValue>>> plan = new LinkedHashMap<>();
    unprocessed.forEach(
        (table, keys) -> {
          List<Map<String, AttributeValue>> copy = new ArrayList<>();
          keys.forEach(key -> copy.add(Map.copyOf(key)));
          plan.put(table, List.copyOf(copy));
        });
    return new PartialBatchGet(admin, Map.copyOf(plan));
  }

  static final class PartialBatchGet implements FaultRegistry.Effect {
    private final DynamoDbClient admin;
    private final Map<String, List<Map<String, AttributeValue>>> plan;
    private BatchGetItemRequest original;
    private Map<String, List<String>> addedAttributes;

    private PartialBatchGet(
        DynamoDbClient admin, Map<String, List<Map<String, AttributeValue>>> plan) {
      this.admin = admin;
      this.plan = plan;
    }

    PartialBatchGet forRequest() {
      return new PartialBatchGet(admin, plan);
    }

    @Override
    public boolean supports(FaultRegistry.Injection injection) {
      return true;
    }

    @Override
    public SdkRequest prepare(SdkRequest request, FaultRegistry.Injection injection) {
      original = (BatchGetItemRequest) request;
      addedAttributes = new LinkedHashMap<>();
      for (Map.Entry<String, List<Map<String, AttributeValue>>> entry : plan.entrySet()) {
        KeysAndAttributes actual = original.requestItems().get(entry.getKey());
        if (actual == null || !actual.keys().containsAll(entry.getValue())) {
          throw new IllegalArgumentException("Unprocessed key was not requested");
        }
      }
      if (injection == FaultRegistry.Injection.REPLACE_REQUEST) return request;
      Map<String, KeysAndAttributes> supplemented = new LinkedHashMap<>(original.requestItems());
      plan.forEach(
          (table, omitted) -> {
            KeysAndAttributes keys = original.requestItems().get(table);
            if (omitted.isEmpty() || keys.projectionExpression() == null) return;
            Set<String> projected = new LinkedHashSet<>();
            for (String term : keys.projectionExpression().split(",", -1)) {
              String attribute = term.trim();
              if (attribute.matches("#[A-Za-z0-9_]+")) {
                String bound = keys.expressionAttributeNames().get(attribute);
                if (bound == null)
                  throw new IllegalArgumentException("Unbound projection attribute");
                attribute = bound;
              }
              projected.add(attribute);
            }
            List<String> added = new ArrayList<>();
            Map<String, String> names = new LinkedHashMap<>(keys.expressionAttributeNames());
            String projection = keys.projectionExpression();
            int aliasNumber = 0;
            for (String attribute : keys.keys().get(0).keySet()) {
              if (projected.contains(attribute)) continue;
              String alias;
              do {
                alias = "#partialKey" + aliasNumber++;
              } while (names.containsKey(alias));
              names.put(alias, attribute);
              projection += ", " + alias;
              added.add(attribute);
            }
            if (!added.isEmpty()) {
              addedAttributes.put(table, List.copyOf(added));
              supplemented.put(
                  table,
                  keys.toBuilder()
                      .projectionExpression(projection)
                      .expressionAttributeNames(names)
                      .build());
            }
          });
      return original.toBuilder().requestItems(supplemented).build();
    }

    @Override
    public SdkResponse response(DynamoDbRequestRecorder.Request request, SdkResponse response) {
      BatchGetItemResponse batch = (BatchGetItemResponse) response;
      Map<String, List<Map<String, AttributeValue>>> responses =
          new LinkedHashMap<>(batch.responses());
      Map<String, KeysAndAttributes> pending = new LinkedHashMap<>(batch.unprocessedKeys());
      plan.forEach(
          (table, omitted) -> {
            if (omitted.isEmpty()) return;
            KeysAndAttributes originalKeys = original.requestItems().get(table);
            responses.computeIfPresent(
                table,
                (name, items) -> {
                  List<Map<String, AttributeValue>> processed = new ArrayList<>();
                  for (Map<String, AttributeValue> item : items) {
                    Map<String, AttributeValue> itemKey =
                        originalKeys.keys().stream()
                            .filter(key -> item.entrySet().containsAll(key.entrySet()))
                            .findFirst()
                            .orElseThrow(
                                () ->
                                    new IllegalStateException(
                                        "Cannot identify BatchGet response item"));
                    if (omitted.contains(itemKey)) continue;
                    Map<String, AttributeValue> projected = new LinkedHashMap<>(item);
                    addedAttributes.getOrDefault(table, List.of()).forEach(projected::remove);
                    processed.add(projected);
                  }
                  return processed;
                });
            KeysAndAttributes servicePending = pending.get(table);
            List<Map<String, AttributeValue>> keys =
                servicePending == null ? new ArrayList<>() : new ArrayList<>(servicePending.keys());
            for (Map<String, AttributeValue> key : omitted) if (!keys.contains(key)) keys.add(key);
            pending.put(table, originalKeys.toBuilder().keys(keys).build());
          });
      return batch.toBuilder().responses(responses).unprocessedKeys(pending).build();
    }

    @Override
    public HttpReply reply(DynamoDbRequestRecorder.Request request) {
      Map<String, List<Map<String, AttributeValue>>> responses = new LinkedHashMap<>();
      Map<String, KeysAndAttributes> pending = new LinkedHashMap<>();
      original
          .requestItems()
          .forEach(
              (table, keys) -> {
                List<Map<String, AttributeValue>> items = new ArrayList<>();
                List<Map<String, AttributeValue>> omitted = plan.getOrDefault(table, List.of());
                for (Map<String, AttributeValue> key : keys.keys()) {
                  if (omitted.contains(key)) continue;
                  Map<String, AttributeValue> stored =
                      admin
                          .getItem(
                              GetItemRequest.builder()
                                  .tableName(table)
                                  .key(key)
                                  .consistentRead(true)
                                  .projectionExpression(keys.projectionExpression())
                                  .expressionAttributeNames(keys.expressionAttributeNames())
                                  .build())
                          .item();
                  if (!stored.isEmpty()) items.add(stored);
                }
                responses.put(table, items);
                if (!omitted.isEmpty()) pending.put(table, keys.toBuilder().keys(omitted).build());
              });
      return new HttpReply(
          200,
          DynamoDbJson.sdk(
              BatchGetItemResponse.builder()
                  .responses(responses)
                  .unprocessedKeys(pending)
                  .build()));
    }
  }

  static FaultRegistry.Effect readInterleave(
      DynamoDbClient admin, String headTable, Runnable commit) {
    return new ResponseEffect() {
      private Map<String, AttributeValue> captured;

      @Override
      public void beforeTransmission(DynamoDbRequestRecorder.Request request) {
        JsonNode keys = request.marshalled.path("RequestItems").path(headTable).path("Keys");
        if (keys.size() != 1) throw new IllegalArgumentException("One head key is required");
        captured =
            admin
                .getItem(
                    GetItemRequest.builder()
                        .tableName(headTable)
                        .key(
                            Map.of(
                                "aid",
                                AttributeValue.fromS(keys.get(0).path("aid").path("S").asText())))
                        .consistentRead(true)
                        .build())
                .item();
        commit.run();
      }

      @Override
      public SdkResponse response(DynamoDbRequestRecorder.Request request, SdkResponse response) {
        BatchGetItemResponse batch = (BatchGetItemResponse) response;
        Map<String, List<Map<String, AttributeValue>>> replaced =
            new LinkedHashMap<>(batch.responses());
        replaced.put(headTable, captured.isEmpty() ? List.of() : List.of(captured));
        return batch.toBuilder().responses(replaced).build();
      }
    };
  }

  /** A plan starts once; later pages validate the real cursor without consuming another fault. */
  static HistoryPages historyPages(
      DynamoDbClient admin,
      String snapshot,
      String aid,
      List<List<Long>> pages,
      Long justWritten,
      boolean omitJustWritten) {
    return new HistoryPages(admin, snapshot, aid, pages, justWritten, omitJustWritten);
  }

  static final class HistoryPages implements FaultRegistry.Effect {
    @Override
    public boolean supports(FaultRegistry.Injection injection) {
      return true;
    }

    private final DynamoDbClient admin;
    private final List<List<Long>> pages;
    private final String snapshot;
    private final String aid;
    private int next;
    private Map<String, AttributeValue> lastEvaluatedKey = Map.of();

    private HistoryPages(
        DynamoDbClient admin,
        String snapshot,
        String aid,
        List<List<Long>> plan,
        Long justWritten,
        boolean omitJustWritten) {
      if (plan.isEmpty()) throw new IllegalArgumentException("Page plan is empty");
      this.admin = admin;
      this.snapshot = snapshot;
      this.aid = aid;
      List<List<Long>> copied = new ArrayList<>();
      for (int i = 0; i < plan.size(); i++) {
        if (i + 1 < plan.size() && plan.get(i).isEmpty())
          throw new IllegalArgumentException("Non-final page has no cursor");
        for (Long seq : plan.get(i)) {
          if (omitJustWritten && seq.equals(justWritten))
            throw new IllegalArgumentException("Omitted history occurs in plan");
        }
        copied.add(List.copyOf(plan.get(i)));
      }
      pages = List.copyOf(copied);
    }

    @Override
    public HttpReply reply(DynamoDbRequestRecorder.Request request) {
      return new HttpReply(200, DynamoDbJson.sdk(nextResponse(request)));
    }

    @Override
    public SdkResponse response(DynamoDbRequestRecorder.Request request, SdkResponse response) {
      return nextResponse(request);
    }

    @Override
    public void beforeTransmission(DynamoDbRequestRecorder.Request request) {
      validate(request);
    }

    private void validate(DynamoDbRequestRecorder.Request request) {
      JsonNode terms = request.structure.path("key_condition").path("all");
      if (!request.phase.equals("retention-query")
          || !request.marshalled.path("TableName").asText().equals(snapshot)
          || terms.size() != 1
          || !terms.get(0).path("attribute").asText().equals("aid")
          || !terms.get(0).path("operator").asText().equals("eq")
          || !terms.get(0).path("argument").equals(DynamoDbJson.sdk(AttributeValue.fromS(aid)))) {
        throw new IllegalArgumentException("History plan target does not match request");
      }
      JsonNode cursor = request.marshalled.path("ExclusiveStartKey");
      if (next >= pages.size()) throw new IllegalStateException("History plan exhausted");
      Map<String, AttributeValue> expectedCursor = lastEvaluatedKey;
      JsonNode expected = DynamoDbJson.sdk(expectedCursor);
      if (!(expectedCursor.isEmpty() && cursor.isMissingNode()) && !cursor.equals(expected)) {
        throw new IllegalArgumentException("History cursor does not match previous page");
      }
    }

    private QueryResponse nextResponse(DynamoDbRequestRecorder.Request request) {
      validate(request);
      List<Map<String, AttributeValue>> items = new ArrayList<>();
      for (Long seq : pages.get(next)) {
        Map<String, AttributeValue> stored =
            admin
                .getItem(
                    GetItemRequest.builder()
                        .tableName(snapshot)
                        .key(
                            Map.of(
                                "aid",
                                AttributeValue.fromS(aid),
                                "skey",
                                AttributeValue.fromN(seq.toString())))
                        .consistentRead(true)
                        .build())
                .item();
        if (!stored.containsKey("active_history_seq_nr")
            || new BigDecimal(stored.get("active_history_seq_nr").n())
                    .compareTo(BigDecimal.valueOf(seq))
                != 0) {
          throw new IllegalArgumentException("History page item is not stored and active");
        }
        items.add(
            Map.of(
                "aid",
                stored.get("aid"),
                "skey",
                stored.get("skey"),
                "active_history_seq_nr",
                stored.get("active_history_seq_nr")));
      }
      QueryResponse response =
          QueryResponse.builder()
              .items(items)
              .count(items.size())
              .scannedCount(items.size())
              .lastEvaluatedKey(next + 1 == pages.size() ? Map.of() : items.get(items.size() - 1))
              .build();
      lastEvaluatedKey = response.lastEvaluatedKey();
      next++;
      return response;
    }

    boolean hasNext() {
      return next < pages.size();
    }
  }
}
