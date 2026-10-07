package com.github.j5ik2o.event.store.adapter.java.memory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.j5ik2o.event.store.adapter.java.core.AggregateId;
import com.github.j5ik2o.event.store.adapter.java.core.ContractViolationException;
import com.github.j5ik2o.event.store.adapter.java.core.EventEnvelope;
import com.github.j5ik2o.event.store.adapter.java.core.EventStore;
import com.github.j5ik2o.event.store.adapter.java.core.EventStoreConfig;
import com.github.j5ik2o.event.store.adapter.java.core.EventStoreException;
import com.github.j5ik2o.event.store.adapter.java.core.JsonPayloadSerializer;
import com.github.j5ik2o.event.store.adapter.java.core.PayloadSerializer;
import com.github.j5ik2o.event.store.adapter.java.core.RetentionPolicy;
import com.github.j5ik2o.event.store.adapter.java.core.SerializationException;
import com.github.j5ik2o.event.store.adapter.java.core.SnapshotEnvelope;
import com.github.j5ik2o.event.store.adapter.java.core.SnapshotReadResult;
import com.github.j5ik2o.event.store.adapter.java.core.StorageException;
import java.math.BigInteger;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** 試験側のメモリ接続。期待値を使わず、保存結果と内部観測を返す。 */
public final class ConformanceMemoryOperations {
  private static final JsonNodeFactory F = JsonNodeFactory.instance;

  private ConformanceMemoryOperations() {}

  public static ObjectNode executeScenario(JsonNode scenario) {
    ObjectNode result = F.objectNode();
    Faults faults = new Faults(scenario.path("faults"));
    if (!faults.supported()) {
      result.put("unsupported", "メモリへ接続できない障害がある");
      return result;
    }
    List<String> notifications = new ArrayList<>();
    MemoryStorage storage;
    EventStore<JsonNode, JsonNode> store;
    try {
      JsonNode settings = scenario.path("store");
      RetentionPolicy policy =
          settings.path("retention_count").isNull() || !settings.has("retention_count")
              ? RetentionPolicy.none()
              : "ttl".equals(settings.path("retention_mode").asText())
                  ? RetentionPolicy.ttl(
                      settings.path("retention_count").intValue(),
                      settings.path("ttl_grace_seconds").longValue())
                  : RetentionPolicy.delete(settings.path("retention_count").intValue());
      storage = MemoryStorage.create(policy, faults);
      store =
          MemoryEventStore.create(
              storage,
              EventStoreConfig.<JsonNode, JsonNode>builder()
                  .payloadSerializer(faults.serializer("event"))
                  .snapshotSerializer(faults.serializer("snapshot"))
                  .retentionFailureListener(failure -> notifications.add("retention-failure"))
                  .build());
      result.putObject("initialization").put("result", "success");
    } catch (EventStoreException failure) {
      result.set("initialization", error(failure));
      faults.finish(result);
      return result;
    }
    ArrayNode steps = result.putArray("steps");
    JsonNode fixtures = scenario.path("fixtures");
    for (JsonNode step : scenario.path("steps")) {
      faults.operation++;
      notifications.clear();
      JsonNode args = step.path("arguments");
      ObjectNode actual;
      try {
        switch (step.path("op").asText()) {
          case "persistEvent":
            store.persistEvent(event(fixtures.path("events").path(args.path("event").asText())));
            actual = F.objectNode().put("result", "success");
            break;
          case "persistEventAndSnapshot":
            store.persistEventAndSnapshot(
                event(fixtures.path("events").path(args.path("event").asText())),
                snapshot(fixtures.path("snapshots").path(args.path("snapshot").asText())));
            actual = F.objectNode().put("result", "success");
            break;
          case "getLatestSnapshotById":
            Optional<SnapshotReadResult<JsonNode>> read =
                store.getLatestSnapshotById(aid(args.path("aggregate_id")));
            actual = F.objectNode().put("result", read.isPresent() ? "snapshot" : "none");
            if (read.isPresent()) {
              actual.put("head_seq_nr", read.get().headSeqNr());
              actual.set(
                  "snapshot",
                  read.get()
                      .snapshot()
                      .<JsonNode>map(ConformanceMemoryOperations::snapshotJson)
                      .orElse(F.nullNode()));
            }
            break;
          case "getEventsByIdSinceSeqNr":
            actual = F.objectNode().put("result", "events");
            ArrayNode events = actual.putArray("events");
            for (EventEnvelope<JsonNode> envelope :
                store.getEventsByIdSinceSeqNr(
                    aid(args.path("aggregate_id")),
                    args.path("seq_nr").bigIntegerValue().longValueExact())) {
              events.add(eventJson(envelope));
            }
            break;
          default:
            throw new IllegalArgumentException("unsupported operation: " + step.path("op"));
        }
      } catch (EventStoreException failure) {
        actual = error(failure);
      }
      ObjectNode observe = actual.putObject("observe");
      ArrayNode notified = observe.putArray("notifications");
      notifications.forEach(notified::add);
      if (step.path("observe").has("history")) {
        JsonNode aggregate =
            args.has("aggregate_id")
                ? args.path("aggregate_id")
                : fixtures.path("events").path(args.path("event").asText()).path("aggregate_id");
        ObjectNode history = observe.putObject("history");
        ArrayNode active = history.putArray("active");
        storage.historySeqNrs(aid(aggregate)).stream().sorted().forEach(active::add);
        history.putArray("marked");
      }
      steps.add(actual);
      faults.finishOperation(result);
    }
    faults.finish(result);
    return result;
  }

  public static ObjectNode validateOccurredAt(JsonNode input, String caseId) {
    EventStore<JsonNode, JsonNode> store =
        MemoryEventStore.create(
            MemoryStorage.create(),
            EventStoreConfig.<JsonNode, JsonNode>builder()
                .payloadSerializer(JsonPayloadSerializer.of(JsonNode.class))
                .snapshotSerializer(JsonPayloadSerializer.of(JsonNode.class))
                .build());
    AggregateId id = AggregateId.of("ConformanceTime", caseId);
    long target = input.path("event_seq_nr").bigIntegerValue().longValueExact();
    try {
      for (long seq = 1; seq < target; seq++) {
        store.persistEvent(timeEvent(id, seq, Instant.parse("1970-01-01T00:00:00.123000000Z")));
      }
      store.persistEvent(timeEvent(id, target, Instant.parse(input.path("iso8601").asText())));
      Instant actual = store.getEventsByIdSinceSeqNr(id, target).get(0).occurredAt();
      BigInteger nanos =
          BigInteger.valueOf(actual.getEpochSecond())
              .multiply(BigInteger.valueOf(1_000_000_000L))
              .add(BigInteger.valueOf(actual.getNano()));
      return F.objectNode().put("value", nanos.toString());
    } catch (EventStoreException failure) {
      return error(failure);
    }
  }

  private static EventEnvelope<JsonNode> timeEvent(AggregateId id, long seq, Instant time) {
    return EventEnvelope.<JsonNode>builder()
        .aggregateId(id)
        .seqNr(seq)
        .occurredAt(time)
        .payload(F.objectNode())
        .build();
  }

  private static AggregateId aid(JsonNode input) {
    return AggregateId.of(input.path("type_name").asText(), input.path("value").asText());
  }

  private static EventEnvelope<JsonNode> event(JsonNode input) {
    return EventEnvelope.<JsonNode>builder()
        .aggregateId(aid(input.path("aggregate_id")))
        .seqNr(input.path("seq_nr").bigIntegerValue().longValueExact())
        .occurredAt(Instant.parse(input.path("occurred_at").asText()))
        .manifest(input.path("manifest").asText(""))
        .payload(input.get("payload"))
        .build();
  }

  private static SnapshotEnvelope<JsonNode> snapshot(JsonNode input) {
    return SnapshotEnvelope.<JsonNode>builder()
        .seqNr(input.path("seq_nr").bigIntegerValue().longValueExact())
        .manifest(input.path("manifest").asText(""))
        .aggregate(input.get("aggregate"))
        .build();
  }

  private static ObjectNode eventJson(EventEnvelope<JsonNode> event) {
    ObjectNode json = F.objectNode();
    json.putObject("aggregate_id")
        .put("type_name", event.aggregateId().typeName())
        .put("value", event.aggregateId().value());
    json.put("seq_nr", event.seqNr());
    json.put("occurred_at", event.occurredAt().toString());
    json.put("manifest", event.manifest());
    json.set("payload", event.payload());
    return json;
  }

  private static ObjectNode snapshotJson(SnapshotEnvelope<JsonNode> snapshot) {
    ObjectNode json =
        F.objectNode().put("seq_nr", snapshot.seqNr()).put("manifest", snapshot.manifest());
    json.set("aggregate", snapshot.aggregate());
    return json;
  }

  private static ObjectNode error(EventStoreException failure) {
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

  private static final class Faults implements MemoryStorageHooks {
    final List<JsonNode> registered = new ArrayList<>();
    final List<Integer> fired = new ArrayList<>();
    int operation;

    Faults(JsonNode faults) {
      faults.forEach(
          fault -> {
            registered.add(fault);
            fired.add(0);
          });
    }

    boolean supported() {
      Set<String> phases =
          Set.of(
              "serialize-event",
              "serialize-snapshot",
              "deserialize-event",
              "deserialize-snapshot",
              "commit",
              "read-events",
              "read-snapshot",
              "retention-query",
              "retention-delete");
      return registered.stream()
          .allMatch(
              fault ->
                  phases.contains(fault.path("phase").asText())
                      && (fault.path("kind").asText().equals("serialization-error")
                          || fault.path("kind").asText().equals("storage-error")
                          || (fault.path("kind").asText().equals("sdk-response")
                              && fault.path("phase").asText().equals("retention-query")
                              && fault.path("details").has("history_pages"))));
    }

    JsonNode fire(String phase) {
      for (int i = 0; i < registered.size(); i++) {
        JsonNode fault = registered.get(i);
        boolean repeated =
            fault.path("repeat").path("mode").asText().equals("until-operation-finishes")
                || fired.get(i) < fault.path("repeat").path("count").asInt(1);
        if (fault.path("operation").asInt() == operation
            && fault.path("phase").asText().equals(phase)
            && repeated) {
          fired.set(i, fired.get(i) + 1);
          String kind = fault.path("kind").asText();
          String message = fault.path("details").path("message").asText();
          if (kind.equals("serialization-error")) {
            throw new SerializationException(message);
          }
          if (kind.equals("storage-error")) {
            throw new StorageException(message);
          }
          return fault.path("details");
        }
      }
      return null;
    }

    void finishOperation(ObjectNode result) {
      for (int i = 0; i < registered.size(); i++) {
        if (registered.get(i).path("operation").asInt() == operation
            && fired.get(i) == 0
            && !result.has("fault_failure")) {
          result.put("fault_failure", "登録した障害が発火しない: " + registered.get(i));
          result.put("fault_operation", operation);
        }
      }
    }

    void finish(ObjectNode result) {
      for (int i = 0; i < registered.size(); i++) {
        if (fired.get(i) == 0 && !result.has("fault_failure")) {
          result.put("fault_failure", "登録した障害が発火しない: " + registered.get(i));
          result.put("fault_operation", registered.get(i).path("operation").asInt());
        }
      }
    }

    PayloadSerializer<JsonNode> serializer(String name) {
      PayloadSerializer<JsonNode> delegate = JsonPayloadSerializer.of(JsonNode.class);
      return new PayloadSerializer<JsonNode>() {
        public byte[] serialize(JsonNode value) {
          fire("serialize-" + name);
          return delegate.serialize(value);
        }

        public JsonNode deserialize(byte[] bytes) {
          fire("deserialize-" + name);
          return delegate.deserialize(bytes);
        }
      };
    }

    public void beforeCommit(AggregateId id) {
      fire("commit");
    }

    public void beforeReadEvents(AggregateId id) {
      fire("read-events");
    }

    public void beforeReadSnapshot(AggregateId id) {
      fire("read-snapshot");
    }

    public void beforeRetentionDelete(AggregateId id, List<Long> seqNrs) {
      fire("retention-delete");
    }

    public List<Long> readHistory(AggregateId id, List<Long> actualHistory) {
      JsonNode response = fire("retention-query");
      if (response == null) {
        return actualHistory;
      }
      List<Long> candidates = new ArrayList<>();
      for (JsonNode page : response.path("history_pages")) {
        for (JsonNode seq : page) {
          candidates.add(seq.bigIntegerValue().longValueExact());
        }
      }
      return candidates;
    }
  }
}
