package com.github.j5ik2o.event.store.adapter.java.dynamodbtest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
    body.put("__type", "com.amazonaws.dynamodb.v20120810#" + code);
    body.put("Message", "Injected DynamoDB test failure");
    return new HttpReply(400, body);
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
    if (count < 1) throw new IllegalArgumentException("Unprocessed count must be positive");
    return new RequestEffect() {
      private Map<String, List<WriteRequest>> excluded;
      private boolean empty;

      @Override
      public SdkRequest prepare(SdkRequest request) {
        BatchWriteItemRequest batch = (BatchWriteItemRequest) request;
        excluded = new LinkedHashMap<>();
        Map<String, List<WriteRequest>> remainder = new LinkedHashMap<>();
        int left = count;
        for (Map.Entry<String, List<WriteRequest>> entry : batch.requestItems().entrySet()) {
          int take = Math.min(left, entry.getValue().size());
          if (take > 0)
            excluded.put(entry.getKey(), List.copyOf(entry.getValue().subList(0, take)));
          if (take < entry.getValue().size())
            remainder.put(
                entry.getKey(),
                List.copyOf(entry.getValue().subList(take, entry.getValue().size())));
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
                DynamoDbJson.sdk(
                    BatchWriteItemResponse.builder().unprocessedItems(excluded).build()))
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
    };
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
    return new RequestEffect() {
      private BatchGetItemRequest original;

      @Override
      public SdkRequest prepare(SdkRequest request) {
        original = (BatchGetItemRequest) request;
        return request;
      }

      @Override
      public HttpReply reply(DynamoDbRequestRecorder.Request request) {
        for (Map.Entry<String, List<Map<String, AttributeValue>>> entry : plan.entrySet()) {
          KeysAndAttributes actual = original.requestItems().get(entry.getKey());
          if (actual == null || !actual.keys().containsAll(entry.getValue())) {
            throw new IllegalArgumentException("Unprocessed key was not requested");
          }
        }
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
                                    .build())
                            .item();
                    if (!stored.isEmpty()) items.add(stored);
                  }
                  responses.put(table, items);
                  if (!omitted.isEmpty())
                    pending.put(table, keys.toBuilder().keys(omitted).build());
                });
        return new HttpReply(
            200,
            DynamoDbJson.sdk(
                BatchGetItemResponse.builder()
                    .responses(responses)
                    .unprocessedKeys(pending)
                    .build()));
      }
    };
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

    private final List<List<Map<String, AttributeValue>>> pages = new ArrayList<>();
    private final String snapshot;
    private final String aid;
    private int next;

    private HistoryPages(
        DynamoDbClient admin,
        String snapshot,
        String aid,
        List<List<Long>> plan,
        Long justWritten,
        boolean omitJustWritten) {
      if (plan.isEmpty()) throw new IllegalArgumentException("Page plan is empty");
      this.snapshot = snapshot;
      this.aid = aid;
      for (int i = 0; i < plan.size(); i++) {
        List<Map<String, AttributeValue>> page = new ArrayList<>();
        if (i + 1 < plan.size() && plan.get(i).isEmpty())
          throw new IllegalArgumentException("Non-final page has no cursor");
        for (Long seq : plan.get(i)) {
          if (omitJustWritten && seq.equals(justWritten))
            throw new IllegalArgumentException("Omitted history occurs in plan");
          Map<String, AttributeValue> key =
              Map.of(
                  "aid", AttributeValue.fromS(aid), "skey", AttributeValue.fromN(seq.toString()));
          Map<String, AttributeValue> stored =
              admin
                  .getItem(
                      GetItemRequest.builder()
                          .tableName(snapshot)
                          .key(key)
                          .consistentRead(true)
                          .build())
                  .item();
          if (!stored.containsKey("active_history_seq_nr")
              || new BigDecimal(stored.get("active_history_seq_nr").n())
                      .compareTo(BigDecimal.valueOf(seq))
                  != 0) {
            throw new IllegalArgumentException("History page item is not stored and active");
          }
          Map<String, AttributeValue> projected = new LinkedHashMap<>(key);
          projected.put("active_history_seq_nr", stored.get("active_history_seq_nr"));
          page.add(Map.copyOf(projected));
        }
        pages.add(List.copyOf(page));
      }
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
      Map<String, AttributeValue> expectedCursor = cursor();
      JsonNode expected = DynamoDbJson.sdk(expectedCursor);
      if (!(expectedCursor.isEmpty() && cursor.isMissingNode()) && !cursor.equals(expected)) {
        throw new IllegalArgumentException("History cursor does not match previous page");
      }
    }

    private QueryResponse nextResponse(DynamoDbRequestRecorder.Request request) {
      validate(request);
      List<Map<String, AttributeValue>> items = pages.get(next++);
      return QueryResponse.builder()
          .items(items)
          .count(items.size())
          .scannedCount(items.size())
          .lastEvaluatedKey(cursor())
          .build();
    }

    boolean hasNext() {
      return next < pages.size();
    }

    private Map<String, AttributeValue> cursor() {
      if (next == 0 || next == pages.size()) return Map.of();
      List<Map<String, AttributeValue>> previous = pages.get(next - 1);
      return previous.get(previous.size() - 1);
    }
  }
}
