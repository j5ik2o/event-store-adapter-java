package com.github.j5ik2o.event.store.adapter.java.examples;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.j5ik2o.event.store.adapter.java.core.AggregateId;
import com.github.j5ik2o.event.store.adapter.java.core.AsyncEventStore;
import com.github.j5ik2o.event.store.adapter.java.core.EventStore;
import com.github.j5ik2o.event.store.adapter.java.core.EventStoreConfig;
import com.github.j5ik2o.event.store.adapter.java.core.RetentionPolicy;
import com.github.j5ik2o.event.store.adapter.java.core.SnapshotReadResult;
import com.github.j5ik2o.event.store.adapter.java.sqlite.SqliteEventStore;
import com.github.j5ik2o.event.store.adapter.java.sqlite.SqliteStorage;
import com.github.j5ik2o.event.store.adapter.java.sqlite.SqliteStorageConfig;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The README SQLite example, run from outside the library package through public types only. */
class SqliteUserAccountExampleTest {
  @TempDir Path directory;

  @Test
  void readmeSqliteExampleWorksThroughPublicTypesOnly() throws Exception {
    EventStoreConfig<String, String> config = UserAccountExample.config();
    Path file = directory.resolve("example-events.db");
    SqliteStorageConfig settings =
        SqliteStorageConfig.builder()
            .retentionPolicy(RetentionPolicy.none())
            .lockWait(Duration.ofSeconds(5))
            .build();
    SqliteStorage.initialize(file, settings);
    AggregateId sqliteId = AggregateId.of("UserAccount", "sqlite-example");

    try (SqliteStorage sqlite = SqliteStorage.open(file, settings)) {
      EventStore<String, String> sqliteStore = SqliteEventStore.create(sqlite, config);
      AsyncEventStore<String, String> asyncSqlite = SqliteEventStore.createAsync(sqlite, config);

      sqliteStore.persistEvent(UserAccountExample.event(sqliteId, 1, "Alice"));
      asyncSqlite
          .persistEventAndSnapshot(
              UserAccountExample.event(sqliteId, 2, "Bob"), UserAccountExample.snapshot(2, "Bob"))
          .join();

      Optional<String> sqliteName = UserAccountExample.restore(sqliteStore, sqliteId);
      assertEquals(Optional.of("Bob"), sqliteName);
      assertEquals(
          Optional.of("Bob"), UserAccountExample.restoreAsync(asyncSqlite, sqliteId).join());
      SnapshotReadResult<String> result = sqliteStore.getLatestSnapshotById(sqliteId).orElseThrow();
      assertEquals(2, result.headSeqNr());
      assertEquals(2, result.snapshot().orElseThrow().seqNr());
    }

    // The file survives close and reopening.
    try (SqliteStorage reopened = SqliteStorage.open(file, settings)) {
      EventStore<String, String> store = SqliteEventStore.create(reopened, config);
      assertEquals(Optional.of("Bob"), UserAccountExample.restore(store, sqliteId));
    }
    writeEvidence(file);
  }

  private static void writeEvidence(Path file) throws Exception {
    ObjectNode evidence = new ObjectMapper().createObjectNode();
    try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath());
        Statement s = c.createStatement()) {
      try (ResultSet rs = s.executeQuery("SELECT sqlite_version()")) {
        rs.next();
        evidence.put("sqlite_version", rs.getString(1));
      }
      try (ResultSet rs = s.executeQuery("PRAGMA journal_mode")) {
        rs.next();
        evidence.put("journal_mode", rs.getString(1));
        assertEquals("wal", rs.getString(1));
      }
      for (String table :
          new String[] {"journal", "head", "snapshot_current", "snapshot_history"}) {
        try (ResultSet rs = s.executeQuery("SELECT count(*) FROM " + table)) {
          rs.next();
          evidence.put(table + "_rows", rs.getLong(1));
        }
      }
    }
    assertEquals(2, evidence.get("journal_rows").asLong());
    assertTrue(evidence.get("snapshot_current_rows").asLong() == 1);
    Path out = Path.of("build/reports/public-api-usage");
    Files.createDirectories(out);
    new ObjectMapper()
        .writerWithDefaultPrettyPrinter()
        .writeValue(out.resolve("sqlite.json").toFile(), evidence);
  }
}
