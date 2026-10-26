package com.github.j5ik2o.event.store.adapter.java.sqlite;

import static com.github.j5ik2o.event.store.adapter.java.sqlite.SqliteTestFixtures.AID;
import static com.github.j5ik2o.event.store.adapter.java.sqlite.SqliteTestFixtures.event;
import static com.github.j5ik2o.event.store.adapter.java.sqlite.SqliteTestFixtures.raw;
import static com.github.j5ik2o.event.store.adapter.java.sqlite.SqliteTestFixtures.settings;
import static com.github.j5ik2o.event.store.adapter.java.sqlite.SqliteTestFixtures.snapshot;
import static com.github.j5ik2o.event.store.adapter.java.sqlite.SqliteTestFixtures.store;
import static com.github.j5ik2o.event.store.adapter.java.sqlite.SqliteTestFixtures.string;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.github.j5ik2o.event.store.adapter.java.core.EventEnvelope;
import com.github.j5ik2o.event.store.adapter.java.core.EventStore;
import com.github.j5ik2o.event.store.adapter.java.core.RetentionPolicy;
import com.github.j5ik2o.event.store.adapter.java.core.SnapshotReadResult;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Reads and writes the storage format of the Rust implementation. The DDL and INSERT shapes below
 * are independent constants copied from the Rust layout, not produced by the Java code under test.
 */
class SqliteRustFormatTest {
  private static final String[] RUST_DDL = {
    "CREATE TABLE journal (aid TEXT COLLATE BINARY NOT NULL, seq_nr INTEGER NOT NULL CHECK (seq_nr BETWEEN 1 AND 9007199254740991), occurred_at_ns INTEGER NOT NULL, manifest TEXT NOT NULL, payload BLOB NOT NULL, PRIMARY KEY (aid, seq_nr)) STRICT",
    "CREATE TABLE head (aid TEXT COLLATE BINARY NOT NULL PRIMARY KEY, type_name TEXT NOT NULL, seq_nr INTEGER NOT NULL CHECK (seq_nr BETWEEN 1 AND 9007199254740991), event_occurred_at_ns INTEGER NOT NULL, event_manifest TEXT NOT NULL, event_payload BLOB NOT NULL) STRICT",
    "CREATE TABLE snapshot_current (aid TEXT COLLATE BINARY NOT NULL PRIMARY KEY, seq_nr INTEGER NOT NULL CHECK (seq_nr BETWEEN 1 AND 9007199254740991), manifest TEXT NOT NULL, payload BLOB NOT NULL) STRICT",
    "CREATE TABLE snapshot_history (aid TEXT COLLATE BINARY NOT NULL, seq_nr INTEGER NOT NULL CHECK (seq_nr BETWEEN 1 AND 9007199254740991), manifest TEXT NOT NULL, payload BLOB NOT NULL, PRIMARY KEY (aid, seq_nr)) STRICT",
    "CREATE TABLE config (singleton INTEGER NOT NULL PRIMARY KEY CHECK (singleton = 1), store_id TEXT NOT NULL, layout_version INTEGER NOT NULL, keep_snapshot_count INTEGER CHECK (keep_snapshot_count IS NULL OR keep_snapshot_count >= 1), retention_mode TEXT NOT NULL CHECK (retention_mode = 'delete'), change_feed_enabled INTEGER NOT NULL CHECK (change_feed_enabled IN (0, 1))) STRICT"
  };

  @TempDir Path directory;

  private static void createRustFile(Path file, Integer keep) throws SQLException {
    try (Connection c = raw(file);
        Statement s = c.createStatement()) {
      for (String ddl : RUST_DDL) {
        s.execute(ddl);
      }
      try (PreparedStatement p =
          c.prepareStatement(
              "INSERT INTO config (singleton, store_id, layout_version, keep_snapshot_count, retention_mode, change_feed_enabled) VALUES (1, ?, 1, ?, 'delete', 0)")) {
        p.setString(1, "123e4567-e89b-42d3-a456-426614174000");
        p.setObject(2, keep);
        p.executeUpdate();
      }
    }
  }

  @Test
  void javaOpensARustShapedFileAndReadsItsEventsAndSnapshot() throws SQLException {
    Path file = directory.resolve("rust.db");
    createRustFile(file, null);
    try (Connection c = raw(file)) {
      long nanos = 1_700_000_000_123_456_789L;
      try (PreparedStatement p =
          c.prepareStatement(
              "INSERT INTO journal (aid, seq_nr, occurred_at_ns, manifest, payload) VALUES (?, ?, ?, ?, ?)")) {
        for (long seq = 1; seq <= 2; seq++) {
          p.setString(1, "Order-1");
          p.setLong(2, seq);
          p.setLong(3, nanos);
          p.setString(4, "event");
          p.setBytes(5, ("\"event-" + seq + "\"").getBytes(StandardCharsets.UTF_8));
          p.executeUpdate();
        }
      }
      try (PreparedStatement p =
          c.prepareStatement(
              "INSERT INTO head (aid, type_name, seq_nr, event_occurred_at_ns, event_manifest, event_payload) VALUES (?, ?, ?, ?, ?, ?)")) {
        p.setString(1, "Order-1");
        p.setString(2, "Order");
        p.setLong(3, 2);
        p.setLong(4, nanos);
        p.setString(5, "event");
        p.setBytes(6, "\"event-2\"".getBytes(StandardCharsets.UTF_8));
        p.executeUpdate();
      }
      try (PreparedStatement p =
          c.prepareStatement(
              "INSERT INTO snapshot_current (aid, seq_nr, manifest, payload) VALUES (?, ?, ?, ?)")) {
        p.setString(1, "Order-1");
        p.setLong(2, 2);
        p.setString(3, "snapshot");
        p.setBytes(4, "\"snapshot-2\"".getBytes(StandardCharsets.UTF_8));
        p.executeUpdate();
      }
    }

    try (SqliteStorage storage = SqliteStorage.open(file, settings(RetentionPolicy.none()))) {
      EventStore<String, String> store = store(storage);
      List<EventEnvelope<String>> events = store.getEventsByIdSinceSeqNr(AID, 0);
      SnapshotReadResult<String> latest = store.getLatestSnapshotById(AID).get();

      assertEquals(2, events.size());
      assertEquals("event-1", events.get(0).payload());
      assertEquals(Instant.ofEpochSecond(1_700_000_000L, 123_456_789), events.get(0).occurredAt());
      assertEquals("event", events.get(1).manifest());
      assertEquals(2, latest.headSeqNr());
      assertEquals("snapshot-2", latest.snapshot().get().aggregate());
      assertEquals("snapshot", latest.snapshot().get().manifest());
    }
  }

  @Test
  void javaAppendsToARustShapedFileWithRetention() throws SQLException {
    Path file = directory.resolve("rust.db");
    createRustFile(file, 2);

    try (SqliteStorage storage = SqliteStorage.open(file, settings(RetentionPolicy.delete(2)))) {
      for (long seq = 1; seq <= 3; seq++) {
        store(storage).persistEventAndSnapshot(event(seq), snapshot(seq));
      }
    }

    assertEquals("3", string(file, "SELECT seq_nr FROM head"));
    assertEquals("2", string(file, "SELECT count(*) FROM snapshot_history"));
    assertEquals("2", string(file, "SELECT min(seq_nr) FROM snapshot_history"));
  }

  @Test
  void rowsWrittenByJavaHaveTheRustColumnStorageClasses() {
    Path file = directory.resolve("java.db");
    SqliteStorage.initialize(file, settings(RetentionPolicy.delete(1)));
    try (SqliteStorage storage = SqliteStorage.open(file, settings(RetentionPolicy.delete(1)))) {
      store(storage).persistEventAndSnapshot(event(1), snapshot(1));
    }

    String classes =
        "typeof(aid) || ',' || typeof(seq_nr) || ',' || typeof(manifest) || ',' || typeof(payload)";
    assertEquals(
        "text,integer,text,blob,integer",
        string(file, "SELECT " + classes + " || ',' || typeof(occurred_at_ns) FROM journal"));
    assertEquals(
        "text,integer,text,blob", string(file, "SELECT " + classes + " FROM snapshot_current"));
    assertEquals(
        "text,integer,text,blob", string(file, "SELECT " + classes + " FROM snapshot_history"));
    assertEquals(
        "text,text,integer,integer,text,blob",
        string(
            file,
            "SELECT typeof(aid) || ',' || typeof(type_name) || ',' || typeof(seq_nr) || ',' || typeof(event_occurred_at_ns) || ',' || typeof(event_manifest) || ',' || typeof(event_payload) FROM head"));
    assertEquals("\"event-1\"", string(file, "SELECT CAST(payload AS TEXT) FROM journal"));
  }

  @Test
  void javaInitializedAndRustDdlFilesHaveTheSameTableDefinitions() throws SQLException {
    Path javaFile = directory.resolve("java.db");
    Path rustFile = directory.resolve("rust.db");
    SqliteStorage.initialize(javaFile, settings(RetentionPolicy.none()));
    createRustFile(rustFile, null);

    for (String table :
        List.of("journal", "head", "snapshot_current", "snapshot_history", "config")) {
      assertEquals(definition(rustFile, table), definition(javaFile, table), table);
    }
  }

  private static String definition(Path file, String table) throws SQLException {
    StringBuilder out = new StringBuilder();
    String columns =
        "SELECT group_concat(name || ':' || type || ':' || \"notnull\" || ':' || pk, ';') FROM (SELECT * FROM pragma_table_info('"
            + table
            + "') ORDER BY cid)";
    out.append(string(file, columns));
    out.append("|strict=")
        .append(
            string(file, "SELECT \"strict\" FROM pragma_table_list WHERE name = '" + table + "'"));
    out.append("|pk-collation=")
        .append(
            string(
                file,
                "SELECT group_concat(x.coll, ',') FROM pragma_index_list('"
                    + table
                    + "', 'main') AS l, pragma_index_xinfo(l.name, 'main') AS x WHERE l.origin = 'pk' AND x.key = 1"));
    return out.toString();
  }
}
