package com.github.j5ik2o.event.store.adapter.java.sqlite;

import static com.github.j5ik2o.event.store.adapter.java.sqlite.SqliteTestFixtures.raw;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteErrorCode;
import org.sqlite.SQLiteException;

class SqliteDriverSmokeTest {
  @TempDir Path directory;

  @Test
  void bundledSqliteIsAtLeastTheProfileMinimumVersion() throws SQLException {
    try (Connection c = raw(directory.resolve("a.db"));
        Statement s = c.createStatement();
        ResultSet rs = s.executeQuery("SELECT sqlite_version()")) {
      rs.next();
      String[] parts = rs.getString(1).split("\\.");
      long[] v = {Long.parseLong(parts[0]), Long.parseLong(parts[1]), Long.parseLong(parts[2])};
      boolean atLeast = v[0] > 3 || (v[0] == 3 && (v[1] > 51 || (v[1] == 51 && v[2] >= 3)));
      assertTrue(atLeast, "bundled SQLite is too old: " + rs.getString(1));
    }
  }

  @Test
  void explicitBeginImmediateAndCommitWorkWithAutoCommitEnabled() throws SQLException {
    Path file = directory.resolve("b.db");
    try (Connection c = raw(file);
        Statement s = c.createStatement()) {
      s.execute("CREATE TABLE t (x INTEGER)");
      s.execute("BEGIN IMMEDIATE");
      s.execute("INSERT INTO t VALUES (1)");
      s.execute("COMMIT");
    }
    assertEquals(1, SqliteTestFixtures.count(file, "t"));
  }

  @Test
  void beginImmediateOnASecondConnectionFailsWithBusyWhileTheFirstHoldsTheLock()
      throws SQLException {
    Path file = directory.resolve("c.db");
    try (Connection holder = raw(file);
        Statement hs = holder.createStatement()) {
      hs.execute("CREATE TABLE t (x INTEGER)");
      hs.execute("BEGIN IMMEDIATE");
      try (Connection waiter = raw(file);
          Statement ws = waiter.createStatement()) {
        SQLiteException e =
            assertThrows(SQLiteException.class, () -> ws.execute("BEGIN IMMEDIATE"));
        assertEquals(SQLiteErrorCode.SQLITE_BUSY, e.getResultCode());
      }
      hs.execute("ROLLBACK");
    }
  }

  @Test
  void duplicatePrimaryKeyReportsThePrimaryKeyConstraintCode() throws SQLException {
    try (Connection c = raw(directory.resolve("d.db"));
        Statement s = c.createStatement()) {
      s.execute("CREATE TABLE t (x INTEGER PRIMARY KEY)");
      s.execute("INSERT INTO t VALUES (1)");
      SQLiteException e =
          assertThrows(SQLiteException.class, () -> s.execute("INSERT INTO t VALUES (1)"));
      assertEquals(SQLiteErrorCode.SQLITE_CONSTRAINT_PRIMARYKEY, e.getResultCode());
    }
  }

  @Test
  void pragmaTableFunctionsUsedForLayoutVerificationAreQueryable() throws SQLException {
    try (Connection c = raw(directory.resolve("e.db"));
        Statement s = c.createStatement()) {
      s.execute("CREATE TABLE t (a TEXT COLLATE BINARY NOT NULL PRIMARY KEY, b INTEGER) STRICT");
      try (ResultSet rs =
          s.executeQuery(
              "SELECT \"strict\" FROM pragma_table_list WHERE schema = 'main' AND name = 't'")) {
        assertTrue(rs.next());
        assertEquals(1, rs.getInt(1));
      }
      try (ResultSet rs = s.executeQuery("SELECT name FROM pragma_table_info('t') ORDER BY cid")) {
        assertTrue(rs.next());
        assertEquals("a", rs.getString(1));
      }
      try (ResultSet rs =
          s.executeQuery(
              "SELECT x.coll FROM pragma_index_list('t', 'main') AS l, pragma_index_xinfo(l.name, 'main') AS x"
                  + " WHERE l.origin = 'pk' AND x.key = 1 AND x.name = 'a'")) {
        assertTrue(rs.next());
        assertEquals("BINARY", rs.getString(1).toUpperCase());
      }
    }
  }
}
