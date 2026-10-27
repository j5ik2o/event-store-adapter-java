package com.github.j5ik2o.event.store.adapter.java.bigtable;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
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
import com.google.cloud.bigtable.data.v2.models.Row;
import com.google.protobuf.ByteString;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import org.slf4j.LoggerFactory;

/** 試験側の Bigtable 接続。期待値を使わず、エミュレーター上の保存結果と内部観測を返す。 */
public final class ConformanceBigtableOperations {
  private static final JsonNodeFactory F = JsonNodeFactory.instance;
  private static final String PRIMARY = "primary";

  private ConformanceBigtableOperations() {}

  /** FNV-1a 64 の符号なし値。適合データの fnv1a64 の実行に使う。 */
  public static long fnv1a64(String utf8) {
    return BigtableKey.fnv1a64(utf8.getBytes(StandardCharsets.UTF_8));
  }

  /** イベントの封筒を符号化し、16 進（小文字）で返す。 */
  public static String encodeEvent(
      String aid, long seqNr, long occurredAtNs, String manifest, String payloadHex) {
    return hex(BigtableEnvelope.encodeEvent(aid, seqNr, occurredAtNs, manifest, unhex(payloadHex)));
  }

  /** スナップショットの封筒を符号化し、16 進（小文字）で返す。 */
  public static String encodeSnapshot(long seqNr, String manifest, String payloadHex) {
    return hex(BigtableEnvelope.encodeSnapshot(seqNr, manifest, unhex(payloadHex)));
  }

  /** 封筒を復元する。復元できなければ保存先エラーを投げる。成功したときは種類を返す。 */
  public static String decode(String kind, String bytesHex) {
    byte[] bytes = unhex(bytesHex);
    if ("event".equals(kind)) {
      BigtableEnvelope.decodeEvent(bytes);
    } else {
      BigtableEnvelope.decodeSnapshot(bytes);
    }
    return "decoded:" + kind;
  }

  private static String hex(byte[] bytes) {
    StringBuilder out = new StringBuilder();
    for (byte b : bytes) {
      out.append(String.format("%02x", b));
    }
    return out.toString();
  }

  private static byte[] unhex(String text) {
    byte[] out = new byte[text.length() / 2];
    for (int i = 0; i < out.length; i++) {
      out[i] = (byte) Integer.parseInt(text.substring(2 * i, 2 * i + 2), 16);
    }
    return out;
  }

  /** 適合データの障害を、Bigtable の差し込み口へ渡す。 */
  private static final class Faults implements BigtableStorageHooks {
    final ScenarioFaults scenario;

    Faults(JsonNode faults) {
      scenario = new ScenarioFaults(faults);
    }

    public void journalWritePrevious(AggregateId id, long seqNr) {
      scenario.journalWritePrevious();
    }

    public void beforeCommit(AggregateId id, long seqNr) {
      scenario.beforeCommit();
    }

    public void afterCommit(AggregateId id, long seqNr) {
      scenario.afterCommit();
    }

    public void journalWriteTail(AggregateId id, long seqNr) {
      scenario.journalWriteTail();
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

  /** journal 用ロガーの警告を「journal-write-failure」の通知として集める。 */
  private static final class JournalWarnings extends AppenderBase<ILoggingEvent> {
    final List<String> sink;

    JournalWarnings(List<String> sink) {
      this.sink = sink;
    }

    @Override
    protected void append(ILoggingEvent event) {
      if (event.getLevel() == Level.WARN) {
        sink.add("journal-write-failure");
      }
    }
  }

  private static final String JOURNAL_LOGGER =
      "com.github.j5ik2o.event.store.adapter.java.bigtable.journal";

  public static ObjectNode executeScenario(JsonNode scenario, BigtableEmulator emulator) {
    List<String> notifications = new ArrayList<>();
    Logger logger = (Logger) LoggerFactory.getLogger(JOURNAL_LOGGER);
    JournalWarnings warnings = new JournalWarnings(notifications);
    warnings.setContext(logger.getLoggerContext());
    warnings.start();
    logger.addAppender(warnings);
    try {
      return run(scenario, emulator, notifications);
    } finally {
      logger.detachAppender(warnings);
      warnings.stop();
    }
  }

  private static ObjectNode run(
      JsonNode scenario, BigtableEmulator emulator, List<String> notifications) {
    ObjectNode result = F.objectNode();
    String unsupported = unsupported(scenario);
    if (unsupported != null) {
      result.put("unsupported", unsupported);
      return result;
    }
    Faults faults = new Faults(scenario.path("faults"));
    if (!faults.scenario.supported(false, true)) {
      result.put("unsupported", "Bigtable へ接続できない障害がある");
      return result;
    }
    Map<String, BigtableStorage> storages = new HashMap<>();
    Map<String, EventStore<JsonNode, JsonNode>> stores = new HashMap<>();
    BigtableEmulator.Tables tables = null;
    BigtableStorageConfig settings;
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
      // 設定の誤りは、通信の前に build で設定エラーになる。表は設定が通ってから作る。
      settings = settings(null, null, store);
      tables = emulator.createTables();
      writeConfigurationRows(emulator, tables, store, scenario.path("seed"));
      settings = settings(tables, emulator, store);
      BigtableStorage primary =
          BigtableStorage.open(emulator.data(), emulator.admin(), settings, faults);
      storages.put(PRIMARY, primary);
      stores.put(PRIMARY, BigtableEventStore.create(primary, config));
      result.putObject("initialization").put("result", "success");
    } catch (EventStoreException failure) {
      result.set("initialization", ScenarioEnvelopes.error(failure));
      faults.scenario.finish(result);
      evidence(result, tables);
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
            actual = createStore(args, emulator, storages, stores, settings, faults, config);
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
        activeHistory(emulator, tables, id, settings.retentionPolicy()).forEach(active::add);
        history.putArray("marked");
      }
      steps.add(actual);
      faults.scenario.finishOperation(result);
    }
    faults.scenario.finish(result);
    evidence(result, tables);
    return result;
  }

  public static ObjectNode validateOccurredAt(
      JsonNode input, String caseId, BigtableEmulator emulator) {
    BigtableEmulator.Tables tables = emulator.createStore(null);
    BigtableStorage storage =
        BigtableStorage.open(
            emulator.data(),
            emulator.admin(),
            BigtableStorageConfig.builder()
                .journalTableId(tables.journal)
                .headTableId(tables.head)
                .build());
    EventStore<JsonNode, JsonNode> store =
        BigtableEventStore.create(
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
      return "Bigtable へ未接続のseed・時計・初期化観測がある";
    }
    for (JsonNode step : scenario.path("steps")) {
      if (!Set.of(
              "persistEvent",
              "persistEventAndSnapshot",
              "getLatestSnapshotById",
              "getEventsByIdSinceSeqNr",
              "createStore")
          .contains(step.path("op").asText())) {
        return "Bigtable へ未接続の操作: " + step.path("op").asText();
      }
      Iterator<String> fields = step.path("observe").fieldNames();
      while (fields.hasNext()) {
        String field = fields.next();
        if (!Set.of("history", "notifications").contains(field)) {
          return "Bigtable へ未接続の観測: " + field;
        }
      }
      fields = step.at("/observe/history").fieldNames();
      while (fields.hasNext()) {
        if (!Set.of("active", "marked", "absent").contains(fields.next())) {
          return "Bigtable へ未接続の履歴観測がある";
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

  private static RetentionPolicy policy(JsonNode store) {
    if (!hasRetention(store)) {
      return RetentionPolicy.none();
    }
    int count = store.path("retention_count").intValue();
    // TTL は Bigtable の最初の版で提供しない。設定エラーになることを、そのまま生成で確かめる。
    return ttl(store)
        ? RetentionPolicy.ttl(count, store.path("ttl_grace_seconds").longValue())
        : RetentionPolicy.delete(count);
  }

  /** 表がまだない段階（tables が null）でも build を試せる。 */
  private static BigtableStorageConfig settings(
      BigtableEmulator.Tables tables, BigtableEmulator ignored, JsonNode store) {
    BigtableStorageConfig.Builder builder =
        BigtableStorageConfig.builder()
            .journalTableId(tables == null ? "pending_journal" : tables.journal)
            .headTableId(tables == null ? "pending_head" : tables.head)
            .retentionPolicy(policy(store));
    if (store.has("envelope_limit_bytes")) {
      builder.envelopeLimitBytes(store.path("envelope_limit_bytes").intValue());
    }
    if (store.path("change_feed").asBoolean(false)) {
      builder.changeFeed(true);
    }
    return builder.build();
  }

  /** 両表の設定行を試験側から生で書く。seed があればその値（保持件数は文字列にする）、なければ クライアントの設定と一致する値にする。 */
  private static void writeConfigurationRows(
      BigtableEmulator emulator, BigtableEmulator.Tables tables, JsonNode store, JsonNode seed) {
    JsonNode c = seed.path("configuration");
    if (!c.isMissingNode()) {
      emulator.putConfig(tables.journal, configurationJson(c.path("journal")));
      emulator.putConfig(tables.head, configurationJson(c.path("head")));
      return;
    }
    String keep =
        hasRetention(store) ? Integer.toString(store.path("retention_count").intValue()) : null;
    String json =
        BigtableEmulator.configJson(keep, store.path("envelope_limit_bytes").asInt(1_048_576));
    emulator.putConfig(tables.journal, json);
    emulator.putConfig(tables.head, json);
  }

  private static String configurationJson(JsonNode c) {
    ObjectNode json = F.objectNode();
    json.set("store_id", c.path("store_id"));
    json.set("layout_version", c.path("layout_version"));
    JsonNode keep = c.path("keep_snapshot_count");
    // 保持件数は仕様で 10 進の文字列として保存する。
    json.set("keep_snapshot_count", keep.isNull() ? keep : F.textNode(keep.asText()));
    json.set("retention_mode", c.path("retention_mode"));
    json.set("envelope_limit_bytes", c.path("envelope_limit_bytes"));
    return json.toString();
  }

  private static ObjectNode createStore(
      JsonNode args,
      BigtableEmulator emulator,
      Map<String, BigtableStorage> storages,
      Map<String, EventStore<JsonNode, JsonNode>> stores,
      BigtableStorageConfig settings,
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
        BigtableStorage second =
            BigtableStorage.open(emulator.data(), emulator.admin(), settings, faults);
        storages.put(name, second);
        stores.put(name, BigtableEventStore.create(second, config));
        return F.objectNode().put("result", "success");
      case "new":
        throw new IllegalArgumentException(
            "storage=new cannot be used with the tables of one store");
      default:
        throw new IllegalArgumentException("unknown storage: " + args.path("storage"));
    }
  }

  /**
   * 履歴の観測。journal の active の行の番号に、保持件数が設定されていて、head の has_snapshot が 1 で、journal に行 h がないときの h
   * を加える。標識（marked）は常に空。
   */
  private static List<Long> activeHistory(
      BigtableEmulator emulator,
      BigtableEmulator.Tables tables,
      AggregateId id,
      RetentionPolicy policy) {
    List<Long> active = new ArrayList<>();
    ByteString prefix = BigtableTestFixtures.prefix(id.asString());
    for (Row row : emulator.rows(tables.journal)) {
      OptionalLong seq = BigtableKey.journalSeqNr(prefix, row.getKey());
      if (seq.isPresent()
          && "active".equals(emulator.text(tables.journal, row.getKey(), "history", "state"))) {
        active.add(seq.getAsLong());
      }
    }
    String h = emulator.text(tables.head, prefix, "head", "seq_nr");
    if (h != null
        && policy.keepCount().isPresent()
        && "1".equals(emulator.text(tables.head, prefix, "head", "has_snapshot"))) {
      long head = Long.parseLong(h);
      if (emulator.row(tables.journal, BigtableKey.journalRowKey(prefix, head)) == null) {
        active.add(head);
      }
    }
    java.util.Collections.sort(active);
    return active;
  }

  /** 実際のエミュレーターで実行した証拠。固定した image と、この場面の表の識別子を結果に残す。 */
  private static void evidence(ObjectNode result, BigtableEmulator.Tables tables) {
    result.put("emulator_image", BigtableEmulator.IMAGE);
    if (tables != null) {
      result.put("journal_table_id", tables.journal);
      result.put("head_table_id", tables.head);
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
