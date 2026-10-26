package com.github.j5ik2o.event.store.adapter.java.sqlite;

import static com.github.j5ik2o.event.store.adapter.java.sqlite.SqliteTestFixtures.count;
import static com.github.j5ik2o.event.store.adapter.java.sqlite.SqliteTestFixtures.execute;
import static com.github.j5ik2o.event.store.adapter.java.sqlite.SqliteTestFixtures.initialized;
import static com.github.j5ik2o.event.store.adapter.java.sqlite.SqliteTestFixtures.newFile;
import static com.github.j5ik2o.event.store.adapter.java.sqlite.SqliteTestFixtures.raw;
import static com.github.j5ik2o.event.store.adapter.java.sqlite.SqliteTestFixtures.settings;
import static com.github.j5ik2o.event.store.adapter.java.sqlite.SqliteTestFixtures.string;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.j5ik2o.event.store.adapter.java.core.ConfigurationException;
import com.github.j5ik2o.event.store.adapter.java.core.RetentionPolicy;
import com.github.j5ik2o.event.store.adapter.java.core.StorageException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SqliteInitializeTest {
  private static final List<String> TABLES =
      Arrays.asList("journal", "head", "snapshot_current", "snapshot_history", "config");

  @TempDir Path directory;

  @Test
  void newFileGetsFiveStrictTablesAndNoChangeFeedTable() throws SQLException {
    Path file = initialized(directory, RetentionPolicy.none());

    for (String table : TABLES) {
      assertEquals(
          "1",
          string(
              file,
              "SELECT \"strict\" FROM pragma_table_list WHERE schema = 'main' AND name = ?",
              table),
          table + " must be STRICT");
    }
    assertEquals(
        "0", string(file, "SELECT count(*) FROM sqlite_schema WHERE name = 'head_transition'"));
    assertEquals(
        "5",
        string(
            file,
            "SELECT count(*) FROM sqlite_schema WHERE type = 'table' AND name NOT GLOB 'sqlite_*'"));
  }

  @Test
  void configRowHoldsLowerCaseUuidLayoutOneRetentionAndNoChangeFeed() throws SQLException {
    Path file = newFile(directory);
    SqliteStorage.initialize(file, settings(RetentionPolicy.delete(4)));

    try (Connection c = raw(file);
        Statement s = c.createStatement();
        ResultSet rs =
            s.executeQuery(
                "SELECT singleton, store_id, layout_version, keep_snapshot_count, retention_mode, change_feed_enabled FROM config")) {
      assertTrue(rs.next());
      assertEquals(1, rs.getInt(1));
      String storeId = rs.getString(2);
      assertEquals(UUID.fromString(storeId).toString(), storeId);
      assertEquals(1, rs.getInt(3));
      assertEquals(4, rs.getInt(4));
      assertEquals("delete", rs.getString(5));
      assertEquals(0, rs.getInt(6));
      assertFalse(rs.next());
    }
  }

  @Test
  void noRetentionIsStoredAsNullKeepCount() throws SQLException {
    Path file = initialized(directory, RetentionPolicy.none());

    try (Connection c = raw(file);
        Statement s = c.createStatement();
        ResultSet rs = s.executeQuery("SELECT keep_snapshot_count FROM config")) {
      assertTrue(rs.next());
      assertNull(rs.getObject(1));
    }
  }

  @Test
  void strictTablesRejectATextValueInAnIntegerColumn() {
    Path file = initialized(directory, RetentionPolicy.none());

    assertThrows(
        IllegalStateException.class,
        () ->
            execute(
                file,
                "INSERT INTO journal (aid, seq_nr, occurred_at_ns, manifest, payload) VALUES ('Order-1', 'one', 0, 'm', x'00')"));
    assertEquals(0, count(file, "journal"));
  }

  @Test
  void seqNrCheckRejectsZeroAndAboveTwoToThe53MinusOne() {
    Path file = initialized(directory, RetentionPolicy.none());
    String prefix =
        "INSERT INTO journal (aid, seq_nr, occurred_at_ns, manifest, payload) VALUES ('Order-1', ";
    String suffix = ", 0, 'm', x'00')";

    assertThrows(IllegalStateException.class, () -> execute(file, prefix + "0" + suffix));
    assertThrows(
        IllegalStateException.class, () -> execute(file, prefix + "9007199254740992" + suffix));
    execute(file, prefix + "9007199254740991" + suffix);
    assertEquals(1, count(file, "journal"));
  }

  @Test
  void notNullColumnsRejectNull() {
    Path file = initialized(directory, RetentionPolicy.none());

    assertThrows(
        IllegalStateException.class,
        () ->
            execute(
                file,
                "INSERT INTO journal (aid, seq_nr, occurred_at_ns, manifest, payload) VALUES ('Order-1', 1, 0, NULL, x'00')"));
    assertThrows(
        IllegalStateException.class,
        () ->
            execute(
                file,
                "INSERT INTO snapshot_current (aid, seq_nr, manifest, payload) VALUES ('Order-1', 1, 'm', NULL)"));
  }

  @Test
  void initializingAnInitializedFileIsAConfigurationErrorAndLeavesItUnchanged() {
    Path file = initialized(directory, RetentionPolicy.delete(2));
    String storeId = string(file, "SELECT store_id FROM config");

    assertThrows(
        ConfigurationException.class,
        () -> SqliteStorage.initialize(file, settings(RetentionPolicy.none())));

    assertEquals(storeId, string(file, "SELECT store_id FROM config"));
    assertEquals("2", string(file, "SELECT keep_snapshot_count FROM config"));
    assertEquals(1, count(file, "config"));
  }

  @Test
  void initializingAFileWithAnotherApplicationTableIsRejectedAndLeavesItUnchanged() {
    Path file = newFile(directory);
    execute(file, "CREATE TABLE other (x INTEGER)");

    assertThrows(
        ConfigurationException.class,
        () -> SqliteStorage.initialize(file, settings(RetentionPolicy.none())));

    assertEquals(
        "1", string(file, "SELECT count(*) FROM sqlite_schema WHERE name NOT GLOB 'sqlite_*'"));
    assertEquals("other", string(file, "SELECT name FROM sqlite_schema WHERE type = 'table'"));
  }

  @Test
  void initializingWithoutTheParentDirectoryIsAStorageError() {
    Path file = directory.resolve("missing-dir").resolve("store.db");

    assertThrows(
        StorageException.class,
        () -> SqliteStorage.initialize(file, settings(RetentionPolicy.none())));

    assertFalse(Files.exists(directory.resolve("missing-dir")));
  }

  @Test
  void openingNeverInitializesAnEmptyExistingFile() throws Exception {
    Path file = newFile(directory);
    execute(file, "CREATE TABLE tmp (a INTEGER)");
    execute(file, "DROP TABLE tmp");

    assertThrows(
        ConfigurationException.class,
        () -> SqliteStorage.open(file, settings(RetentionPolicy.none())));

    assertEquals(
        "0", string(file, "SELECT count(*) FROM sqlite_schema WHERE name NOT GLOB 'sqlite_*'"));
    assertNotNull(file);
  }

  @Test
  void aidKeysUseBinaryCollation() {
    Path file = initialized(directory, RetentionPolicy.none());
    execute(
        file,
        "INSERT INTO snapshot_current (aid, seq_nr, manifest, payload) VALUES ('Order-A', 1, 'm', x'00')");

    assertEquals("0", string(file, "SELECT count(*) FROM snapshot_current WHERE aid = 'order-a'"));
    execute(
        file,
        "INSERT INTO snapshot_current (aid, seq_nr, manifest, payload) VALUES ('order-a', 1, 'm', x'00')");
    assertEquals(2, count(file, "snapshot_current"));
  }
}
