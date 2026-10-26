package com.github.j5ik2o.event.store.adapter.java.sqlite;

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
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** 試験側の SQLite 接続。期待値を使わず、実ファイルの保存結果と内部観測を返す。 */
public final class ConformanceSqliteOperations {
  private static final JsonNodeFactory F = JsonNodeFactory.instance;
  private static final String PRIMARY = "primary";
  private static final String FILE_NAME = "store.db";

  private ConformanceSqliteOperations() {}

  private static final class OpenStore {
    final SqliteStorage storage;
    final EventStore<JsonNode, JsonNode> store;

    OpenStore(SqliteStorage storage, EventStore<JsonNode, JsonNode> store) {
      this.storage = storage;
      this.store = store;
    }
  }

  /** 適合データの障害を、SQLite の差し込み口へ渡す。 */
  private static final class Faults implements SqliteStorageHooks {
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

  public static ObjectNode executeScenario(JsonNode scenario, Path directory) {
    ObjectNode result = F.objectNode();
    String unsupported = unsupported(scenario);
    if (unsupported != null) {
      result.put("unsupported", unsupported);
      return result;
    }
    Faults faults = new Faults(scenario.path("faults"));
    if (!faults.scenario.supported()) {
      result.put("unsupported", "SQLite へ接続できない障害がある");
      return result;
    }
    Path file = directory.resolve(FILE_NAME);
    List<String> notifications = new ArrayList<>();
    Map<String, OpenStore> stores = new HashMap<>();
    Connection[] lockHolder = new Connection[1];
    try {
      SqliteStorageConfig settings;
      EventStoreConfig<JsonNode, JsonNode> config;
      try {
        settings = settings(scenario.path("store"));
        config =
            EventStoreConfig.<JsonNode, JsonNode>builder()
                .payloadSerializer(faults.scenario.serializer("event"))
                .snapshotSerializer(faults.scenario.serializer("snapshot"))
                .retentionFailureListener(failure -> notifications.add("retention-failure"))
                .build();
        SqliteStorage.initialize(file, settings);
        seedConfiguration(file, scenario.path("seed").path("configuration"));
        SqliteStorage primary = SqliteStorage.open(file, settings, faults);
        stores.put(PRIMARY, new OpenStore(primary, SqliteEventStore.create(primary, config)));
        result.putObject("initialization").put("result", "success");
      } catch (EventStoreException failure) {
        result.set("initialization", ScenarioEnvelopes.error(failure));
        faults.scenario.finish(result);
        evidence(result, file);
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
        ObjectNode actual;
        try {
          switch (step.path("op").asText()) {
            case "persistEvent":
              store.persistEvent(
                  ScenarioEnvelopes.event(
                      fixtures.path("events").path(args.path("event").asText())));
              actual = F.objectNode().put("result", "success");
              break;
            case "persistEventAndSnapshot":
              store.persistEventAndSnapshot(
                  ScenarioEnvelopes.event(
                      fixtures.path("events").path(args.path("event").asText())),
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
              actual = createStore(args, stores, file, settings, faults, config);
              break;
            case "holdWriteLock":
              if (lockHolder[0] != null) {
                throw new IllegalArgumentException("the write lock is already held");
              }
              lockHolder[0] = holdWriteLock(file);
              actual = F.objectNode().put("result", "success");
              break;
            case "releaseWriteLock":
              if (lockHolder[0] == null) {
                throw new IllegalArgumentException("the write lock is not held");
              }
              releaseWriteLock(lockHolder[0]);
              lockHolder[0] = null;
              actual = F.objectNode().put("result", "success");
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
          history(file, ScenarioEnvelopes.aid(aggregate)).forEach(active::add);
          history.putArray("marked");
        }
        steps.add(actual);
        faults.scenario.finishOperation(result);
      }
      if (lockHolder[0] != null) {
        result.put("fault_failure", "書き込みロックを解放しないまま終了した");
        result.put("fault_operation", faults.scenario.operation());
      }
      faults.scenario.finish(result);
      evidence(result, file);
      return result;
    } finally {
      closeAll(stores, lockHolder[0]);
    }
  }

  public static ObjectNode validateOccurredAt(JsonNode input, String caseId, Path directory) {
    Path file = directory.resolve(FILE_NAME);
    SqliteStorageConfig settings = SqliteStorageConfig.builder().build();
    SqliteStorage.initialize(file, settings);
    try (SqliteStorage storage = SqliteStorage.open(file, settings)) {
      EventStore<JsonNode, JsonNode> store =
          SqliteEventStore.create(
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
  }

  private static String unsupported(JsonNode scenario) {
    if (scenario.path("seed").has("items")
        || scenario.has("clock")
        || scenario.path("initialization").has("observe")) {
      return "SQLite へ未接続のseed・時計・初期化観測がある";
    }
    for (JsonNode step : scenario.path("steps")) {
      if (!Set.of(
              "persistEvent",
              "persistEventAndSnapshot",
              "getLatestSnapshotById",
              "getEventsByIdSinceSeqNr",
              "createStore",
              "holdWriteLock",
              "releaseWriteLock")
          .contains(step.path("op").asText())) {
        return "SQLite へ未接続の操作: " + step.path("op").asText();
      }
      Iterator<String> fields = step.path("observe").fieldNames();
      while (fields.hasNext()) {
        String field = fields.next();
        if (!Set.of("history", "notifications").contains(field)) {
          return "SQLite へ未接続の観測: " + field;
        }
      }
      fields = step.at("/observe/history").fieldNames();
      while (fields.hasNext()) {
        if (!Set.of("active", "marked", "absent").contains(fields.next())) {
          return "SQLite へ未接続の履歴観測がある";
        }
      }
    }
    return null;
  }

  private static SqliteStorageConfig settings(JsonNode store) {
    RetentionPolicy policy =
        store.path("retention_count").isNull() || !store.has("retention_count")
            ? RetentionPolicy.none()
            : "ttl".equals(store.path("retention_mode").asText())
                ? RetentionPolicy.ttl(
                    store.path("retention_count").intValue(),
                    store.path("ttl_grace_seconds").longValue())
                : RetentionPolicy.delete(store.path("retention_count").intValue());
    SqliteStorageConfig.Builder builder = SqliteStorageConfig.builder().retentionPolicy(policy);
    if (store.has("lock_wait_milliseconds")) {
      builder.lockWait(Duration.ofMillis(store.path("lock_wait_milliseconds").longValue()));
    }
    return builder.build();
  }

  /** 初期化済みのファイルの config 行を、生の JDBC で書き換える。1 行でなければデータの誤り。 */
  private static void seedConfiguration(Path file, JsonNode configuration) {
    if (configuration.isMissingNode()) {
      return;
    }
    try (Connection c = raw(file);
        PreparedStatement p =
            c.prepareStatement(
                "UPDATE config SET store_id = ?, layout_version = ?, keep_snapshot_count = ?,"
                    + " retention_mode = ?, change_feed_enabled = ? WHERE singleton = 1")) {
      p.setString(1, configuration.path("store_id").asText());
      p.setLong(2, configuration.path("layout_version").longValue());
      if (configuration.path("keep_snapshot_count").isNull()) {
        p.setNull(3, java.sql.Types.INTEGER);
      } else {
        p.setLong(3, configuration.path("keep_snapshot_count").longValue());
      }
      p.setString(4, configuration.path("retention_mode").asText());
      p.setLong(5, configuration.path("change_feed_enabled").longValue());
      if (p.executeUpdate() != 1) {
        throw new IllegalArgumentException("seed.configuration must update exactly one row");
      }
    } catch (SQLException failure) {
      throw new IllegalStateException("failed to seed the configuration", failure);
    }
  }

  private static ObjectNode createStore(
      JsonNode args,
      Map<String, OpenStore> stores,
      Path file,
      SqliteStorageConfig settings,
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
        SqliteStorage second = SqliteStorage.open(file, settings, faults);
        stores.put(name, new OpenStore(second, SqliteEventStore.create(second, config)));
        return F.objectNode().put("result", "success");
      case "new":
        throw new IllegalArgumentException("storage=new cannot be used with a SQLite file");
      default:
        throw new IllegalArgumentException("unknown storage: " + args.path("storage"));
    }
  }

  private static Connection holdWriteLock(Path file) {
    try {
      Connection c = raw(file);
      try (Statement s = c.createStatement()) {
        s.execute("BEGIN IMMEDIATE");
      } catch (SQLException failure) {
        c.close();
        throw failure;
      }
      return c;
    } catch (SQLException failure) {
      throw new IllegalStateException("failed to hold the write lock", failure);
    }
  }

  private static void releaseWriteLock(Connection c) {
    try (Connection closing = c;
        Statement s = closing.createStatement()) {
      s.execute("ROLLBACK");
    } catch (SQLException failure) {
      throw new IllegalStateException("failed to release the write lock", failure);
    }
  }

  private static List<Long> history(Path file, AggregateId id) {
    List<Long> active = new ArrayList<>();
    try (Connection c = raw(file);
        PreparedStatement p =
            c.prepareStatement(
                "SELECT seq_nr FROM snapshot_history WHERE aid = ? ORDER BY seq_nr")) {
      p.setString(1, id.asString());
      try (ResultSet rs = p.executeQuery()) {
        while (rs.next()) {
          active.add(rs.getLong(1));
        }
      }
    } catch (SQLException failure) {
      throw new IllegalStateException("failed to observe the snapshot history", failure);
    }
    return active;
  }

  /** 実ファイルで実行した証拠。同梱 SQLite の版とファイル名だけを結果に残す。 */
  private static void evidence(ObjectNode result, Path file) {
    if (!java.nio.file.Files.exists(file)) {
      return;
    }
    try (Connection c = raw(file);
        Statement s = c.createStatement();
        ResultSet rs = s.executeQuery("SELECT sqlite_version()")) {
      rs.next();
      result.put("sqlite_version", rs.getString(1));
      result.put("storage_file", file.getFileName().toString());
    } catch (SQLException failure) {
      throw new IllegalStateException("failed to read the SQLite version", failure);
    }
  }

  private static void closeAll(Map<String, OpenStore> stores, Connection lockHolder) {
    for (OpenStore store : stores.values()) {
      store.storage.close();
    }
    if (lockHolder != null) {
      try {
        lockHolder.close();
      } catch (SQLException ignored) {
        // 解放されなかったロックは、結果の fault_failure で既に失敗として報告している。
      }
    }
  }

  private static Connection raw(Path file) throws SQLException {
    return DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath());
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
