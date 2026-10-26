package com.github.j5ik2o.event.store.adapter.java.memory;

import com.fasterxml.jackson.core.JsonPointer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.j5ik2o.event.store.adapter.java.conformance.fixture.ScenarioEnvelopes;
import com.github.j5ik2o.event.store.adapter.java.conformance.fixture.ScenarioFaults;
import com.github.j5ik2o.event.store.adapter.java.core.AggregateId;
import com.github.j5ik2o.event.store.adapter.java.core.EventEnvelope;
import com.github.j5ik2o.event.store.adapter.java.core.EventStore;
import com.github.j5ik2o.event.store.adapter.java.core.EventStoreConfig;
import com.github.j5ik2o.event.store.adapter.java.core.EventStoreException;
import com.github.j5ik2o.event.store.adapter.java.core.JsonPayloadSerializer;
import com.github.j5ik2o.event.store.adapter.java.core.RetentionPolicy;
import com.github.j5ik2o.event.store.adapter.java.core.SnapshotReadResult;
import java.math.BigInteger;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** 試験側のメモリ接続。期待値を使わず、保存結果と内部観測を返す。 */
public final class ConformanceMemoryOperations {
  private static final JsonNodeFactory F = JsonNodeFactory.instance;

  private ConformanceMemoryOperations() {}

  /** 保存先の上にストアを開く処理。単体試験が偽の保存先を差し込めるようにする。 */
  @FunctionalInterface
  public interface StoreOpener {
    EventStore<JsonNode, JsonNode> open(
        MemoryStorage storage, EventStoreConfig<JsonNode, JsonNode> config);

    /** 公開 API だけでストアを開く。 */
    StoreOpener PUBLIC_API = MemoryEventStore::create;
  }

  private static final class OpenStore {
    final MemoryStorage storage;
    final EventStore<JsonNode, JsonNode> store;

    OpenStore(MemoryStorage storage, EventStore<JsonNode, JsonNode> store) {
      this.storage = storage;
      this.store = store;
    }
  }

  public static ObjectNode executeScenario(JsonNode scenario, StoreOpener opener) {
    ObjectNode result = F.objectNode();
    if (scenario.has("seed")
        || scenario.has("clock")
        || scenario.path("initialization").has("observe")) {
      result.put("unsupported", "メモリへ未接続のseed・時計・初期化観測がある");
      return result;
    }
    for (JsonNode step : scenario.path("steps")) {
      if (!Set.of(
              "persistEvent",
              "persistEventAndSnapshot",
              "getLatestSnapshotById",
              "getEventsByIdSinceSeqNr",
              "createStore",
              "mutateWrittenPayload",
              "mutateReadPayload")
          .contains(step.path("op").asText())) {
        result.put("unsupported", "メモリへ未接続の操作: " + step.path("op").asText());
        return result;
      }
      java.util.Iterator<String> fields = step.path("observe").fieldNames();
      while (fields.hasNext()) {
        String field = fields.next();
        if (!Set.of("history", "notifications").contains(field)) {
          result.put("unsupported", "メモリへ未接続の観測: " + field);
          return result;
        }
      }
      fields = step.at("/observe/history").fieldNames();
      while (fields.hasNext()) {
        if (!Set.of("active", "marked", "absent").contains(fields.next())) {
          result.put("unsupported", "メモリへ未接続の履歴観測がある");
          return result;
        }
      }
    }
    Faults faults = new Faults(scenario.path("faults"));
    if (!faults.scenario.supported()) {
      result.put("unsupported", "メモリへ接続できない障害がある");
      return result;
    }
    List<String> notifications = new ArrayList<>();
    Map<String, OpenStore> stores = new HashMap<>();
    Map<Integer, EventEnvelope<JsonNode>> written = new HashMap<>();
    Map<Integer, List<EventEnvelope<JsonNode>>> reads = new HashMap<>();
    RetentionPolicy policy;
    EventStoreConfig<JsonNode, JsonNode> config;
    try {
      JsonNode settings = scenario.path("store");
      policy =
          settings.path("retention_count").isNull() || !settings.has("retention_count")
              ? RetentionPolicy.none()
              : "ttl".equals(settings.path("retention_mode").asText())
                  ? RetentionPolicy.ttl(
                      settings.path("retention_count").intValue(),
                      settings.path("ttl_grace_seconds").longValue())
                  : RetentionPolicy.delete(settings.path("retention_count").intValue());
      config =
          EventStoreConfig.<JsonNode, JsonNode>builder()
              .payloadSerializer(faults.scenario.serializer("event"))
              .snapshotSerializer(faults.scenario.serializer("snapshot"))
              .retentionFailureListener(failure -> notifications.add("retention-failure"))
              .build();
      MemoryStorage primary = MemoryStorage.create(policy, faults);
      stores.put(PRIMARY, new OpenStore(primary, opener.open(primary, config)));
      result.putObject("initialization").put("result", "success");
    } catch (EventStoreException failure) {
      result.set("initialization", ScenarioEnvelopes.error(failure));
      faults.scenario.finish(result);
      return result;
    }
    ArrayNode steps = result.putArray("steps");
    JsonNode fixtures = scenario.path("fixtures");
    for (JsonNode step : scenario.path("steps")) {
      faults.scenario.startOperation();
      notifications.clear();
      JsonNode args = step.path("arguments");
      String target = step.path("store").asText(PRIMARY);
      OpenStore current = stores.get(target);
      if (current == null) {
        throw new IllegalArgumentException("unknown store: " + target);
      }
      EventStore<JsonNode, JsonNode> store = current.store;
      MemoryStorage storage = current.storage;
      ObjectNode actual;
      try {
        switch (step.path("op").asText()) {
          case "persistEvent":
            EventEnvelope<JsonNode> single =
                ScenarioEnvelopes.event(fixtures.path("events").path(args.path("event").asText()));
            store.persistEvent(single);
            written.put(faults.scenario.operation(), single);
            actual = F.objectNode().put("result", "success");
            break;
          case "persistEventAndSnapshot":
            EventEnvelope<JsonNode> pair =
                ScenarioEnvelopes.event(fixtures.path("events").path(args.path("event").asText()));
            store.persistEventAndSnapshot(
                pair,
                ScenarioEnvelopes.snapshot(
                    fixtures.path("snapshots").path(args.path("snapshot").asText())));
            written.put(faults.scenario.operation(), pair);
            actual = F.objectNode().put("result", "success");
            break;
          case "getLatestSnapshotById":
            Optional<SnapshotReadResult<JsonNode>> read =
                store.getLatestSnapshotById(ScenarioEnvelopes.aid(args.path("aggregate_id")));
            actual = F.objectNode().put("result", read.isPresent() ? "snapshot" : "none");
            if (read.isPresent()) {
              actual.put("head_seq_nr", read.get().headSeqNr());
              actual.set(
                  "snapshot",
                  read.get()
                      .snapshot()
                      .<JsonNode>map(ScenarioEnvelopes::snapshotJson)
                      .orElse(F.nullNode()));
            }
            break;
          case "getEventsByIdSinceSeqNr":
            actual = F.objectNode().put("result", "events");
            ArrayNode events = actual.putArray("events");
            List<EventEnvelope<JsonNode>> received =
                store.getEventsByIdSinceSeqNr(
                    ScenarioEnvelopes.aid(args.path("aggregate_id")),
                    args.path("seq_nr").bigIntegerValue().longValueExact());
            reads.put(faults.scenario.operation(), received);
            for (EventEnvelope<JsonNode> envelope : received) {
              events.add(ScenarioEnvelopes.eventJson(envelope));
            }
            break;
          case "createStore":
            actual = createStore(args, stores, policy, faults, config, opener);
            break;
          case "mutateWrittenPayload":
            EventEnvelope<JsonNode> target1 = written.get(args.path("operation").asInt());
            actual =
                mutation(
                    target1 == null ? null : target1.payload(),
                    args.path("pointer").asText(),
                    args.path("value"));
            break;
          case "mutateReadPayload":
            actual =
                mutation(
                    readPayload(
                        reads.get(args.path("operation").asInt()),
                        fixtures.path("events").path(args.path("event").asText())),
                    args.path("pointer").asText(),
                    args.path("value"));
            break;
          default:
            throw new IllegalArgumentException("unsupported operation: " + step.path("op"));
        }
      } catch (EventStoreException failure) {
        actual = ScenarioEnvelopes.error(failure);
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
        storage.historySeqNrs(ScenarioEnvelopes.aid(aggregate)).stream()
            .sorted()
            .forEach(active::add);
        history.putArray("marked");
      }
      steps.add(actual);
      faults.scenario.finishOperation(result);
    }
    faults.scenario.finish(result);
    return result;
  }

  private static final String PRIMARY = "primary";

  private static ObjectNode createStore(
      JsonNode args,
      Map<String, OpenStore> stores,
      RetentionPolicy policy,
      Faults faults,
      EventStoreConfig<JsonNode, JsonNode> config,
      StoreOpener opener) {
    String name = args.path("name").asText();
    if (stores.containsKey(name)) {
      throw new IllegalArgumentException("store name already used: " + name);
    }
    MemoryStorage storage;
    switch (args.path("storage").asText()) {
      case "new":
        storage = MemoryStorage.create(policy, faults);
        break;
      case "shared":
        OpenStore source = stores.get(args.path("shared_with").asText());
        if (source == null) {
          throw new IllegalArgumentException("unknown shared_with: " + args.path("shared_with"));
        }
        storage = source.storage;
        break;
      default:
        throw new IllegalArgumentException("unknown storage: " + args.path("storage"));
    }
    stores.put(name, new OpenStore(storage, opener.open(storage, config)));
    return F.objectNode().put("result", "success");
  }

  /** 手元のペイロードを JSON Pointer の位置で書き換える。書き換えられなければデータの誤りとして失敗にする。 */
  private static ObjectNode mutation(JsonNode payload, String pointer, JsonNode value) {
    boolean replaced = payload != null && replaceAt(payload, pointer, value);
    return F.objectNode().put("result", replaced ? "success" : "invalid-mutation");
  }

  private static JsonNode readPayload(List<EventEnvelope<JsonNode>> read, JsonNode fixture) {
    if (read == null) {
      return null;
    }
    AggregateId id = ScenarioEnvelopes.aid(fixture.path("aggregate_id"));
    long seqNr = fixture.path("seq_nr").bigIntegerValue().longValueExact();
    for (EventEnvelope<JsonNode> envelope : read) {
      if (envelope.aggregateId().equals(id) && envelope.seqNr() == seqNr) {
        return envelope.payload();
      }
    }
    return null;
  }

  /** 空の位置、存在しない位置、同じ値への書き換えは行わず false を返す。 */
  static boolean replaceAt(JsonNode root, String pointer, JsonNode value) {
    JsonPointer last = JsonPointer.compile(pointer);
    if (last.matches()) {
      return false;
    }
    JsonNode parent = root.at(last.head());
    String property = last.last().getMatchingProperty();
    JsonNode existing = parent.path(property);
    if (existing.isMissingNode() || existing.equals(value)) {
      return false;
    }
    if (parent instanceof ObjectNode) {
      ((ObjectNode) parent).set(property, value);
      return true;
    }
    if (parent instanceof ArrayNode) {
      ((ArrayNode) parent).set(last.last().getMatchingIndex(), value);
      return true;
    }
    return false;
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
      return ScenarioEnvelopes.error(failure);
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

  private static final class Faults implements MemoryStorageHooks {
    final ScenarioFaults scenario;

    Faults(JsonNode faults) {
      scenario = new ScenarioFaults(faults);
    }

    public void beforeCommit(AggregateId id) {
      scenario.beforeCommit();
    }

    public void beforeReadEvents(AggregateId id) {
      scenario.beforeReadEvents();
    }

    public void beforeReadSnapshot(AggregateId id) {
      scenario.beforeReadSnapshot();
    }

    public void beforeRetentionDelete(AggregateId id, List<Long> seqNrs) {
      scenario.beforeRetentionDelete();
    }

    public List<Long> readHistory(AggregateId id, List<Long> actualHistory) {
      return scenario.readHistory(actualHistory);
    }
  }
}
