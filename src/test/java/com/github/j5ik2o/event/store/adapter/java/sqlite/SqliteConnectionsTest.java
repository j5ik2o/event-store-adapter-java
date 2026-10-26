package com.github.j5ik2o.event.store.adapter.java.sqlite;

import static com.github.j5ik2o.event.store.adapter.java.sqlite.SqliteTestFixtures.initialized;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SqliteConnectionsTest {
  @TempDir Path directory;

  @Test
  void openedConnectionAppliesAndReadsBackTheProfileSettings() throws SQLException {
    Path file = initialized(directory, RetentionPolicy.none());

    try (Connection c = SqliteConnections.open(file, Duration.ofMillis(1234), false);
        Statement s = c.createStatement()) {
      assertEquals("normal", pragma(s, "locking_mode"));
      assertEquals("wal", pragma(s, "journal_mode"));
      assertEquals("2", pragma(s, "synchronous"));
      assertEquals("0", pragma(s, "read_uncommitted"));
      assertEquals("1234", pragma(s, "busy_timeout"));
    }
  }

  @Test
  void versionComparisonIsAnIntegerComparisonOfThreeParts() {
    assertFalse(SqliteConnections.meetsMinimumVersion("3.51.2"));
    assertTrue(SqliteConnections.meetsMinimumVersion("3.51.3"));
    assertTrue(SqliteConnections.meetsMinimumVersion("3.100.0"));
    assertTrue(SqliteConnections.meetsMinimumVersion("4.0.0"));
    assertFalse(SqliteConnections.meetsMinimumVersion("2.99.99"));
    assertFalse(SqliteConnections.meetsMinimumVersion("3.9.9"));
    assertFalse(SqliteConnections.meetsMinimumVersion("3.51"));
    assertFalse(SqliteConnections.meetsMinimumVersion("3.51.3.1"));
    assertFalse(SqliteConnections.meetsMinimumVersion("3.x.3"));
    assertFalse(SqliteConnections.meetsMinimumVersion(""));
  }

  @Test
  void verifySettingsAcceptsTheExpectedValuesAndRejectsEachMismatch() {
    Duration wait = Duration.ofMillis(500);
    assertDoesNotThrow(() -> SqliteConnections.verifySettings("normal", "wal", 2, 0, 500, wait));
    assertThrows(
        ConfigurationException.class,
        () -> SqliteConnections.verifySettings("exclusive", "wal", 2, 0, 500, wait));
    assertThrows(
        ConfigurationException.class,
        () -> SqliteConnections.verifySettings("normal", "delete", 2, 0, 500, wait));
    assertThrows(
        ConfigurationException.class,
        () -> SqliteConnections.verifySettings("normal", "wal", 1, 0, 500, wait));
    assertThrows(
        ConfigurationException.class,
        () -> SqliteConnections.verifySettings("normal", "wal", 2, 1, 500, wait));
    assertThrows(
        ConfigurationException.class,
        () -> SqliteConnections.verifySettings("normal", "wal", 2, 0, 499, wait));
  }

  @Test
  void ordinaryFileNameOpensThatFile() {
    Path file = initialized(directory, RetentionPolicy.none());

    try (SqliteStorage storage =
        SqliteStorage.open(file, SqliteTestFixtures.settings(RetentionPolicy.none()))) {
      assertTrue(Files.exists(directory.resolve("store.db")));
    }
  }

  @Test
  void fileColonNameIsAnOrdinaryFileNameNotAUri() throws Exception {
    Path file = directory.resolve("file:store.db");

    SqliteStorage.initialize(file, SqliteTestFixtures.settings(RetentionPolicy.none()));

    assertTrue(Files.isRegularFile(file));
    assertTrue(Files.isRegularFile(directory.resolve("file:store.db")));
    assertFalse(Files.exists(directory.resolve("store.db")));
    try (SqliteStorage storage =
        SqliteStorage.open(file, SqliteTestFixtures.settings(RetentionPolicy.none()))) {
      assertTrue(Files.exists(file));
    }
  }

  @Test
  void questionMarkInTheFileNameIsRejectedAndNoFileIsCreated() throws Exception {
    Path file = directory.resolve("a?cache=shared");

    assertThrows(
        ConfigurationException.class,
        () -> SqliteStorage.initialize(file, SqliteTestFixtures.settings(RetentionPolicy.none())));

    assertFalse(Files.exists(directory.resolve("a")));
    assertFalse(Files.exists(file));
    assertEquals(0, Files.list(directory).count());
  }

  @Test
  void openingAMissingFileWithoutCreateIsAStorageErrorAndCreatesNothing() throws Exception {
    Path file = directory.resolve("missing.db");

    assertThrows(
        StorageException.class, () -> SqliteConnections.open(file, Duration.ofSeconds(1), false));

    assertFalse(Files.exists(file));
  }

  private static String pragma(Statement s, String name) throws SQLException {
    try (ResultSet rs = s.executeQuery("PRAGMA " + name)) {
      rs.next();
      return rs.getString(1);
    }
  }
}
