package com.github.j5ik2o.event.store.adapter.java.dynamodbtest;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.core.SdkResponse;
import software.amazon.awssdk.core.interceptor.ExecutionAttributes;
import software.amazon.awssdk.core.interceptor.InterceptorContext;
import software.amazon.awssdk.core.interceptor.SdkExecutionAttribute;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.SdkHttpFullRequest;
import software.amazon.awssdk.http.SdkHttpMethod;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryResponse;

class DynamoDbEventQueryPaginationTest {
  private static final int PAGE_BYTES = 1024 * 1024;

  private static Map<String, AttributeValue> item(int seq, int payloadBytes) {
    return Map.of(
        "aid", AttributeValue.fromS("A-x"),
        "seq_nr", AttributeValue.fromN(Integer.toString(seq)),
        "payload", AttributeValue.fromB(SdkBytes.fromByteArray(new byte[payloadBytes])));
  }

  private static QueryResponse response(List<Map<String, AttributeValue>> items) {
    return QueryResponse.builder()
        .items(items)
        .count(items.size())
        .scannedCount(items.size())
        .build();
  }

  @Test
  void oversizedTerminalPageUsesOnlyItsPrefixAndLastRealJournalKey() {
    QueryResponse raw =
        response(List.of(item(1, 320000), item(2, 320000), item(3, 320000), item(4, 320000)));
    JsonNode original = DynamoDbJson.sdk(raw);

    QueryResponse corrected = DynamoDbEventQueryPagination.correct(raw);

    assertEquals(raw.items().subList(0, 3), corrected.items());
    assertEquals(
        Map.of("aid", raw.items().get(2).get("aid"), "seq_nr", raw.items().get(2).get("seq_nr")),
        corrected.lastEvaluatedKey());
    assertEquals(3, corrected.count());
    assertEquals(3, corrected.scannedCount());
    assertEquals(original, DynamoDbJson.sdk(raw));
    assertFalse(raw.hasLastEvaluatedKey());
  }

  @Test
  void oversizedPageWithLocalContinuationUsesItsReturnedPrefixKey() {
    List<Map<String, AttributeValue>> items =
        List.of(item(1, 320000), item(2, 320000), item(3, 320000), item(4, 320000));
    Map<String, AttributeValue> key =
        Map.of("aid", items.get(3).get("aid"), "seq_nr", items.get(3).get("seq_nr"));
    QueryResponse raw = response(items).toBuilder().lastEvaluatedKey(key).build();

    QueryResponse corrected = DynamoDbEventQueryPagination.correct(raw);

    assertEquals(items.subList(0, 3), corrected.items());
    assertEquals(items.get(2).get("seq_nr"), corrected.lastEvaluatedKey().get("seq_nr"));
    assertEquals(key, raw.lastEvaluatedKey());
  }

  @Test
  void subLimitBinaryPagesKeepExistingContinuationAndTerminalAbsence() {
    List<Map<String, AttributeValue>> items =
        List.of(item(1, 270000), item(2, 270000), item(3, 270000));
    // These actual bytes fit even though their JSON base64 text alone exceeds 1MiB.
    Map<String, AttributeValue> key =
        Map.of("aid", items.get(2).get("aid"), "seq_nr", items.get(2).get("seq_nr"));
    for (Map<String, AttributeValue> cursor : List.of(Map.<String, AttributeValue>of(), key)) {
      QueryResponse raw = response(items).toBuilder().lastEvaluatedKey(cursor).build();
      assertEquals(raw, DynamoDbEventQueryPagination.correct(raw));
    }
    QueryResponse empty = response(List.of()).toBuilder().lastEvaluatedKey(key).build();
    assertEquals(empty, DynamoDbEventQueryPagination.correct(empty));
  }

  @Test
  void exactLimitKeepsThePageAndOneExtraByteIsRejected() {
    // aid: 3+3, seq_nr: 6+2, payload: 7+N = 21+N bytes.
    QueryResponse exact = response(List.of(item(1, PAGE_BYTES - 21)));
    assertEquals(exact, DynamoDbEventQueryPagination.correct(exact));
    assertThrows(
        IllegalStateException.class,
        () -> DynamoDbEventQueryPagination.correct(response(List.of(item(1, PAGE_BYTES - 20)))));
    QueryResponse next = response(List.of(item(1, PAGE_BYTES - 21), item(2, 1)));
    assertEquals(exact.items(), DynamoDbEventQueryPagination.correct(next).items());
  }

  @Test
  void sizeUsesUtf8AttributeNamesValuesAndNumericSignificantDigits() {
    Map<String, AttributeValue> attributes = new LinkedHashMap<>(item(1, PAGE_BYTES - 30));
    attributes.put("é", AttributeValue.fromS("界")); // 2+3 bytes.
    attributes.put(
        "seq_nr", AttributeValue.fromN("123456789")); // 6 bytes, rather than 9 text bytes.
    QueryResponse exact = response(List.of(attributes));
    assertEquals(exact, DynamoDbEventQueryPagination.correct(exact));
    attributes.put(
        "payload", AttributeValue.fromB(SdkBytes.fromByteArray(new byte[PAGE_BYTES - 29])));
    assertThrows(
        IllegalStateException.class,
        () -> DynamoDbEventQueryPagination.correct(response(List.of(attributes))));
  }

  @Test
  void nestedAttributesAndSetsContributeTheirActualSize() {
    Map<String, AttributeValue> attributes = new LinkedHashMap<>(item(1, PAGE_BYTES - 65));
    attributes.put(
        "l",
        AttributeValue.fromL(
            List.of(AttributeValue.fromS("é"), AttributeValue.fromN("12")))); // 1+3+2+2+2 = 10.
    attributes.put(
        "m", AttributeValue.fromM(Map.of("é", AttributeValue.fromS("界")))); // 1+3+1+2+3 = 10.
    attributes.put("t", AttributeValue.fromBool(true)); // 1+1.
    attributes.put("z", AttributeValue.fromNul(true)); // 1+1.
    attributes.put("ss", AttributeValue.builder().ss("é", "界").build()); // 2+2+3.
    attributes.put("ns", AttributeValue.builder().ns("12", "1234").build()); // 2+2+3.
    attributes.put(
        "bs",
        AttributeValue.builder()
            .bs(SdkBytes.fromByteArray(new byte[1]), SdkBytes.fromByteArray(new byte[3]))
            .build()); // 2+1+3.
    QueryResponse exact = response(List.of(attributes));
    assertEquals(exact, DynamoDbEventQueryPagination.correct(exact));
    attributes.put(
        "payload", AttributeValue.fromB(SdkBytes.fromByteArray(new byte[PAGE_BYTES - 64])));
    assertThrows(
        IllegalStateException.class,
        () -> DynamoDbEventQueryPagination.correct(response(List.of(attributes))));
  }

  @Test
  void missingOrInvalidJournalKeyIsRejectedOnlyWhenCorrectionNeedsIt() {
    for (String missing : List.of("aid", "seq_nr")) {
      Map<String, AttributeValue> invalid = new LinkedHashMap<>(item(1, 320000));
      invalid.remove(missing);
      assertEquals(
          response(List.of(invalid)),
          DynamoDbEventQueryPagination.correct(response(List.of(invalid))));
      assertThrows(
          IllegalStateException.class,
          () -> DynamoDbEventQueryPagination.correct(response(List.of(invalid, item(2, 800000)))));
    }
    Map<String, AttributeValue> invalid = new LinkedHashMap<>(item(1, 320000));
    invalid.put("seq_nr", AttributeValue.fromS("1"));
    assertThrows(
        IllegalStateException.class,
        () -> DynamoDbEventQueryPagination.correct(response(List.of(invalid, item(2, 800000)))));
  }

  @Test
  void recorderCorrectsAllJournalQueriesBeforeResponseFaultsEvenBeforeAsyncSendAccounting() {
    QueryResponse raw =
        response(List.of(item(1, 320000), item(2, 320000), item(3, 320000), item(4, 320000)));
    List<SdkResponse> captured = new ArrayList<>();
    for (boolean writing : List.of(false, true)) {
      DynamoDbRequestRecorder.Request recorded =
          record(
              query("journal"),
              raw,
              writing,
              FaultRegistry.Injection.REPLACE_RESPONSE,
              DynamoDbFaultEffects.response(
                  page -> {
                    captured.add(page);
                    return page;
                  }));
      assertEquals(4, recorded.originalResponse.path("Items").size());
      assertEquals(3, recorded.effectiveResponse.path("Items").size());
      assertEquals(
          0, recorded.transmissions); // The real async callback can precede send accounting.
    }
    assertEquals(2, captured.size());
    for (SdkResponse page : captured) assertEquals(3, ((QueryResponse) page).items().size());
  }

  @Test
  void recorderKeepsHistoryQueriesAndActuallyReplacedResponses() {
    QueryResponse raw =
        response(List.of(item(1, 320000), item(2, 320000), item(3, 320000), item(4, 320000)));
    QueryRequest history = query("snapshot").toBuilder().indexName("history").build();
    DynamoDbRequestRecorder.Request retained =
        record(
            history,
            raw,
            false,
            FaultRegistry.Injection.REPLACE_RESPONSE,
            DynamoDbFaultEffects.response(page -> page));
    assertEquals(DynamoDbJson.sdk(raw), retained.effectiveResponse);
    FaultRegistry.Effect replacement =
        new FaultRegistry.Effect() {
          public boolean supports(FaultRegistry.Injection injection) {
            return injection == FaultRegistry.Injection.REPLACE_REQUEST;
          }

          public HttpReply reply(DynamoDbRequestRecorder.Request request) {
            return new HttpReply(200, DynamoDbJson.sdk(raw));
          }
        };
    DynamoDbRequestRecorder.Request replaced =
        record(query("journal"), raw, false, FaultRegistry.Injection.REPLACE_REQUEST, replacement);
    assertEquals(DynamoDbJson.sdk(raw), replaced.effectiveResponse);
  }

  private static QueryRequest query(String table) {
    return QueryRequest.builder()
        .tableName(table)
        .keyConditionExpression("aid = :aid")
        .expressionAttributeValues(Map.of(":aid", AttributeValue.fromS("A-x")))
        .build();
  }

  private static DynamoDbRequestRecorder.Request record(
      QueryRequest request,
      QueryResponse response,
      boolean writing,
      FaultRegistry.Injection injection,
      FaultRegistry.Effect effect) {
    FaultRegistry faults = new FaultRegistry();
    String phase =
        request.indexName() != null
            ? "retention-query"
            : writing ? "classify-condition-failure-read" : "read-events";
    FaultRegistry.Fault fault = faults.register(1, phase, 1, injection, effect);
    FaultRegistry.Operation operation = faults.begin(1, writing);
    if (writing) operation.transactionSeen = true;
    DynamoDbRequestRecorder recorder =
        new DynamoDbRequestRecorder(
            faults, new DynamoDbRequestTargets("journal", "snapshot", "head", "history"));
    ExecutionAttributes attributes =
        new ExecutionAttributes().putAttribute(SdkExecutionAttribute.OPERATION_NAME, "Query");
    InterceptorContext context =
        InterceptorContext.builder()
            .request(request)
            .response(response)
            .requestBody(RequestBody.fromBytes(DynamoDbJson.bytes(DynamoDbJson.sdk(request))))
            .build();
    recorder.beforeExecution(context, attributes);
    recorder.modifyRequest(context, attributes);
    recorder.afterMarshalling(context, attributes);
    SdkHttpFullRequest http =
        SdkHttpFullRequest.builder()
            .uri(URI.create("http://localhost"))
            .method(SdkHttpMethod.POST)
            .build();
    recorder.replacement(
        recorder.modifyHttpRequest(context.toBuilder().httpRequest(http).build(), attributes));
    recorder.modifyResponse(context, attributes);
    recorder.afterExecution(context, attributes);
    assertEquals(1, faults.applications(fault));
    assertEquals(0, faults.reservations(fault));
    assertEquals(0, faults.pending(operation));
    assertTrue(recorder.requestsFinished(operation).isDone());
    assertEquals("passed", faults.finish(operation).status);
    return recorder.requests().get(0);
  }
}
