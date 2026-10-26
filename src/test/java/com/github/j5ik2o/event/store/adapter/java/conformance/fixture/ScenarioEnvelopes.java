package com.github.j5ik2o.event.store.adapter.java.conformance.fixture;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.j5ik2o.event.store.adapter.java.core.AggregateId;
import com.github.j5ik2o.event.store.adapter.java.core.ContractViolationException;
import com.github.j5ik2o.event.store.adapter.java.core.EventEnvelope;
import com.github.j5ik2o.event.store.adapter.java.core.EventStoreException;
import com.github.j5ik2o.event.store.adapter.java.core.SnapshotEnvelope;
import java.time.Instant;

/** 適合データの JSON と封筒・エラーの相互変換。保存先に依らず共有する。 */
public final class ScenarioEnvelopes {
  private static final JsonNodeFactory F = JsonNodeFactory.instance;

  private ScenarioEnvelopes() {}

  public static AggregateId aid(JsonNode input) {
    return AggregateId.of(input.path("type_name").asText(), input.path("value").asText());
  }

  public static EventEnvelope<JsonNode> event(JsonNode input) {
    return EventEnvelope.<JsonNode>builder()
        .aggregateId(aid(input.path("aggregate_id")))
        .seqNr(input.path("seq_nr").bigIntegerValue().longValueExact())
        .occurredAt(Instant.parse(input.path("occurred_at").asText()))
        .manifest(input.path("manifest").asText(""))
        .payload(input.get("payload").deepCopy())
        .build();
  }

  public static SnapshotEnvelope<JsonNode> snapshot(JsonNode input) {
    return SnapshotEnvelope.<JsonNode>builder()
        .seqNr(input.path("seq_nr").bigIntegerValue().longValueExact())
        .manifest(input.path("manifest").asText(""))
        .aggregate(input.get("aggregate").deepCopy())
        .build();
  }

  public static ObjectNode eventJson(EventEnvelope<JsonNode> event) {
    ObjectNode json = F.objectNode();
    json.putObject("aggregate_id")
        .put("type_name", event.aggregateId().typeName())
        .put("value", event.aggregateId().value());
    json.put("seq_nr", event.seqNr());
    json.put("occurred_at", event.occurredAt().toString());
    json.put("manifest", event.manifest());
    json.set("payload", event.payload().deepCopy());
    return json;
  }

  public static ObjectNode snapshotJson(SnapshotEnvelope<JsonNode> snapshot) {
    ObjectNode json =
        F.objectNode().put("seq_nr", snapshot.seqNr()).put("manifest", snapshot.manifest());
    json.set("aggregate", snapshot.aggregate().deepCopy());
    return json;
  }

  public static ObjectNode error(EventStoreException failure) {
    ObjectNode actual = F.objectNode();
    ObjectNode error = actual.putObject("error");
    error.put(
        "category", failure.category().name().toLowerCase(java.util.Locale.ROOT).replace('_', '-'));
    error.put("message", failure.getMessage());
    if (failure instanceof ContractViolationException) {
      ContractViolationException violation = (ContractViolationException) failure;
      error.put("rule", violation.rule());
      violation.seqNr().ifPresent(seq -> error.put("seq_nr", seq));
    }
    return actual;
  }
}
