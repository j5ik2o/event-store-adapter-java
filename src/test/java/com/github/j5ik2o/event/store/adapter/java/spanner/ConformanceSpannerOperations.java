package com.github.j5ik2o.event.store.adapter.java.spanner;

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
import com.github.j5ik2o.event.store.adapter.java.core.SnapshotReadResult;
import com.google.cloud.spanner.ErrorCode;
import com.google.cloud.spanner.SpannerExceptionFactory;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** 試験側の Spanner 接続。期待値を使わず、エミュレーター上の保存結果と内部観測を返す。 */
public final class ConformanceSpannerOperations {
  private static final JsonNodeFactory F = JsonNodeFactory.instance;
  private static final String PRIMARY = "primary";

  private ConformanceSpannerOperations() {}

  /** FNV-1a 64 の符号なし値。適合データの fnv1a64 の実行に使う。 */
  public static long fnv1a64(String utf8) {
    return ByteBuffer.wrap(SpannerKey.hashPrefix(utf8)).getLong();
  }

  /** 適合データの障害を、Spanner の差し込み口へ渡す。 */
  private static final class Faults implements SpannerStorageHooks {
    final ScenarioFaults scenario;

    Faults(JsonNode faults) {
      scenario = new ScenarioFaults(faults);
    }

    public void beforeCommit(AggregateId id) {
      if (scenario.beforeCommit()) {
        throw SpannerExceptionFactory.newSpannerException(
            ErrorCode.ABORTED, "injected transaction abort");
      }
    }

    public void afterCommit(AggregateId id) {
      scenario.afterCommit();
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

  public static ObjectNode executeScenario(JsonNode scenario, SpannerEmulator emulator) {
    ObjectNode result = F.objectNode();
    String unsupported = unsupported(scenario);
    if (unsupported != null) {
      result.put("unsupported", unsupported);
      return result;
    }
    Faults faults = new Faults(scenario.path("faults"));
    if (!faults.scenario.supported(true)) {
      result.put("unsupported", "Spanner へ接続できない障害がある");
      return result;
    }
    List<String> notifications = new ArrayList<>();
    Map<String, SpannerStorage> storages = new HashMap<>();
    Map<String, EventStore<JsonNode, JsonNode>> stores = new HashMap<>();
    String prefix = null;
    SpannerStorageConfig settings;
    EventStoreConfig<JsonNode, JsonNode> config;
    try {
      settings = null;
      config =
          EventStoreConfig.<JsonNode, JsonNode>builder()
              .payloadSerializer(faults.scenario.serializer("event"))
              .snapshotSerializer(faults.scenario.serializer("snapshot"))
              .retentionFailureListener(failure -> notifications.add("retention-failure"))
              .build();
      JsonNode store = scenario.path("store");
      SpannerRetentionPolicy policy = policy(store);
      // 表を作る前に設定を検査できるよう、接頭辞は作成後に build する。
      SpannerEmulator.ConfigRow row = configurationRow(store, scenario.path("seed"));
      Integer policyDays = policyDays(store, scenario.path("seed"));
      prefix = emulator.createStore(policyDays, row);
      settings = settings(prefix, store, policy);
      SpannerStorage primary = SpannerStorage.open(emulator.client(), settings, faults);
      storages.put(PRIMARY, primary);
      stores.put(PRIMARY, SpannerEventStore.create(primary, config));
      result.putObject("initialization").put("result", "success");
    } catch (EventStoreException failure) {
      result.set("initialization", ScenarioEnvelopes.error(failure));
      faults.scenario.finish(result);
      evidence(result, prefix);
      return result;
    }
    ArrayNode steps = result.putArray("steps");
    JsonNode fixtures = scenario.path("fixtures");
    for (JsonNode step : scenario.path("steps")) {
      faults.scenario.startOperation();
      notifications.clear();
      JsonNode args = step.path("arguments");
      String target = step.path("store").asText(PRIMARY);
      EventStore<JsonNode, JsonNode> store = stores.get(target);
      if (store == null) {
        throw new IllegalArgumentException("unknown store: " + target);
      }
      ObjectNode actual;
      try {
        switch (step.path("op").asText()) {
          case "persistEvent":
            store.persistEvent(
                ScenarioEnvelopes.event(fixtures.path("events").path(args.path("event").asText())));
            actual = F.objectNode().put("result", "success");
            break;
          case "persistEventAndSnapshot":
            store.persistEventAndSnapshot(
                ScenarioEnvelopes.event(fixtures.path("events").path(args.path("event").asText())),
                ScenarioEnvelopes.snapshot(
                    fixtures.path("snapshots").path(args.path("snapshot").asText())));
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
            for (EventEnvelope<JsonNode> envelope :
                store.getEventsByIdSinceSeqNr(
                    ScenarioEnvelopes.aid(args.path("aggregate_id")),
                    args.path("seq_nr").bigIntegerValue().longValueExact())) {
              events.add(ScenarioEnvelopes.eventJson(envelope));
            }
            break;
          case "createStore":
            actual =
                createStore(args, emulator, prefix, storages, stores, settings, faults, config);
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
        AggregateId id = ScenarioEnvelopes.aid(aggregate);
        ObjectNode history = observe.putObject("history");
        ArrayNode active = history.putArray("active");
        history(emulator, prefix, id, true).forEach(active::add);
        ArrayNode marked = history.putArray("marked");
        history(emulator, prefix, id, false).forEach(marked::add);
      }
      steps.add(actual);
      faults.scenario.finishOperation(result);
    }
    faults.scenario.finish(result);
    evidence(result, prefix);
    return result;
  }

  public static ObjectNode validateOccurredAt(
      JsonNode input, String caseId, SpannerEmulator emulator) {
    String prefix =
        emulator.createStore(null, SpannerEmulator.ConfigRow.matching(null, "delete", null));
    SpannerStorage storage = SpannerStorage.open(emulator.client(), settings(prefix, null, null));
    EventStore<JsonNode, JsonNode> store =
        SpannerEventStore.create(
            storage,
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

  private static String unsupported(JsonNode scenario) {
    if (scenario.path("seed").has("items")
        || scenario.has("clock")
        || scenario.path("initialization").has("observe")) {
      return "Spanner へ未接続のseed・時計・初期化観測がある";
    }
    for (JsonNode step : scenario.path("steps")) {
      if (!Set.of(
              "persistEvent",
              "persistEventAndSnapshot",
              "getLatestSnapshotById",
              "getEventsByIdSinceSeqNr",
              "createStore")
          .contains(step.path("op").asText())) {
        return "Spanner へ未接続の操作: " + step.path("op").asText();
      }
      Iterator<String> fields = step.path("observe").fieldNames();
      while (fields.hasNext()) {
        String field = fields.next();
        if (!Set.of("history", "notifications").contains(field)) {
          return "Spanner へ未接続の観測: " + field;
        }
      }
      fields = step.at("/observe/history").fieldNames();
      while (fields.hasNext()) {
        if (!Set.of("active", "marked", "absent").contains(fields.next())) {
          return "Spanner へ未接続の履歴観測がある";
        }
      }
    }
    return null;
  }

  private static boolean ttl(JsonNode store) {
    return "ttl".equals(store.path("retention_mode").asText());
  }

  private static boolean hasRetention(JsonNode store) {
    return store.has("retention_count") && !store.path("retention_count").isNull();
  }

  private static SpannerRetentionPolicy policy(JsonNode store) {
    if (!hasRetention(store)) {
      return SpannerRetentionPolicy.none();
    }
    int count = store.path("retention_count").intValue();
    return ttl(store)
        ? SpannerRetentionPolicy.ttl(count, store.path("ttl_grace_days").longValue())
        : SpannerRetentionPolicy.delete(count);
  }

  private static SpannerStorageConfig settings(
      String prefix, JsonNode store, SpannerRetentionPolicy policy) {
    SpannerStorageConfig.Builder builder = SpannerStorageConfig.builder().tablePrefix(prefix);
    if (policy != null) {
      builder.retentionPolicy(policy);
    }
    if (store != null) {
      if (store.has("transaction_retry_limit")) {
        builder.transactionRetryLimit(store.path("transaction_retry_limit").intValue());
      }
      if (store.path("change_feed").asBoolean(false)) {
        builder.changeFeed(true);
      }
    }
    return builder.build();
  }

  /** 設定行。seed があればその値、なければクライアントの設定と一致する値にする。 */
  private static SpannerEmulator.ConfigRow configurationRow(JsonNode store, JsonNode seed) {
    JsonNode c = seed.path("configuration");
    if (!c.isMissingNode()) {
      return new SpannerEmulator.ConfigRow(
          c.path("store_id").asText(),
          c.path("layout_version").longValue(),
          c.path("keep_snapshot_count").isNull() ? null : c.path("keep_snapshot_count").intValue(),
          c.path("retention_mode").asText(),
          c.path("ttl_grace_days").isNull() ? null : c.path("ttl_grace_days").longValue(),
          c.path("change_feed_enabled").asBoolean());
    }
    return SpannerEmulator.ConfigRow.matching(
        hasRetention(store) ? store.path("retention_count").intValue() : null,
        ttl(store) ? "ttl" : "delete",
        ttl(store) ? store.path("ttl_grace_days").longValue() : null);
  }

  private static Integer policyDays(JsonNode store, JsonNode seed) {
    if (seed.has("row_deletion_policy_days")) {
      JsonNode days = seed.path("row_deletion_policy_days");
      return days.isNull() ? null : days.intValue();
    }
    return ttl(store) ? Integer.valueOf(store.path("ttl_grace_days").intValue()) : null;
  }

  private static ObjectNode createStore(
      JsonNode args,
      SpannerEmulator emulator,
      String prefix,
      Map<String, SpannerStorage> storages,
      Map<String, EventStore<JsonNode, JsonNode>> stores,
      SpannerStorageConfig settings,
      Faults faults,
      EventStoreConfig<JsonNode, JsonNode> config) {
    String name = args.path("name").asText();
    if (stores.containsKey(name)) {
      throw new IllegalArgumentException("store name already used: " + name);
    }
    switch (args.path("storage").asText()) {
      case "shared":
        if (!stores.containsKey(args.path("shared_with").asText())) {
          throw new IllegalArgumentException("unknown shared_with: " + args.path("shared_with"));
        }
        SpannerStorage second = SpannerStorage.open(emulator.client(), settings, faults);
        storages.put(name, second);
        stores.put(name, SpannerEventStore.create(second, config));
        return F.objectNode().put("result", "success");
      case "new":
        throw new IllegalArgumentException(
            "storage=new cannot be used with the tables of one prefix");
      default:
        throw new IllegalArgumentException("unknown storage: " + args.path("storage"));
    }
  }

  private static List<Long> history(
      SpannerEmulator emulator, String prefix, AggregateId id, boolean unmarked) {
    String aid = id.asString().replace("\\", "\\\\").replace("'", "\\'");
    return emulator.longs(
        "SELECT seq_nr FROM "
            + prefix
            + "_snapshot_history WHERE aid = '"
            + aid
            + "' AND expired_at IS "
            + (unmarked ? "" : "NOT ")
            + "NULL ORDER BY seq_nr");
  }

  /** 実際のエミュレーターで実行した証拠。固定した image と、この場面の表の接頭辞を結果に残す。 */
  private static void evidence(ObjectNode result, String prefix) {
    result.put("emulator_image", SpannerEmulator.IMAGE);
    if (prefix != null) {
      result.put("table_prefix", prefix);
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
}
