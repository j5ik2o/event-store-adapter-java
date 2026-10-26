package com.github.j5ik2o.event.store.adapter.java.sqlite;

import com.github.j5ik2o.event.store.adapter.java.core.ConfigurationException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.OptionalInt;
import java.util.UUID;

/** プロファイルの 5 つの表の作成と照合。照合は DDL 文字列でなく pragma の意味で比べる。 */
final class SqliteLayout {
  private static final String[] DDL = {
    "CREATE TABLE journal (aid TEXT COLLATE BINARY NOT NULL, seq_nr INTEGER NOT NULL CHECK (seq_nr"
        + " BETWEEN 1 AND 9007199254740991), occurred_at_ns INTEGER NOT NULL, manifest TEXT NOT"
        + " NULL, payload BLOB NOT NULL, PRIMARY KEY (aid, seq_nr)) STRICT",
    "CREATE TABLE head (aid TEXT COLLATE BINARY NOT NULL PRIMARY KEY, type_name TEXT NOT NULL,"
        + " seq_nr INTEGER NOT NULL CHECK (seq_nr BETWEEN 1 AND 9007199254740991),"
        + " event_occurred_at_ns INTEGER NOT NULL, event_manifest TEXT NOT NULL, event_payload"
        + " BLOB NOT NULL) STRICT",
    "CREATE TABLE snapshot_current (aid TEXT COLLATE BINARY NOT NULL PRIMARY KEY, seq_nr INTEGER"
        + " NOT NULL CHECK (seq_nr BETWEEN 1 AND 9007199254740991), manifest TEXT NOT NULL,"
        + " payload BLOB NOT NULL) STRICT",
    "CREATE TABLE snapshot_history (aid TEXT COLLATE BINARY NOT NULL, seq_nr INTEGER NOT NULL"
        + " CHECK (seq_nr BETWEEN 1 AND 9007199254740991), manifest TEXT NOT NULL, payload BLOB"
        + " NOT NULL, PRIMARY KEY (aid, seq_nr)) STRICT",
    "CREATE TABLE config (singleton INTEGER NOT NULL PRIMARY KEY CHECK (singleton = 1), store_id"
        + " TEXT NOT NULL, layout_version INTEGER NOT NULL, keep_snapshot_count INTEGER CHECK"
        + " (keep_snapshot_count IS NULL OR keep_snapshot_count >= 1), retention_mode TEXT NOT"
        + " NULL CHECK (retention_mode = 'delete'), change_feed_enabled INTEGER NOT NULL CHECK"
        + " (change_feed_enabled IN (0, 1))) STRICT"
  };

  private static final String INSERT_CONFIG =
      "INSERT INTO config (singleton, store_id, layout_version, keep_snapshot_count,"
          + " retention_mode, change_feed_enabled) VALUES (1, ?, 1, ?, 'delete', 0)";

  /** 列の期待値。名前、型、NOT NULL、主キー内の位置（0 は主キーでない）。 */
  private static final class Column {
    final String name;
    final String type;
    final int notNull;
    final int pk;

    Column(String name, String type, int notNull, int pk) {
      this.name = name;
      this.type = type;
      this.notNull = notNull;
      this.pk = pk;
    }

    String describe() {
      return name + ":" + type + ":" + notNull + ":" + pk;
    }
  }

  private static final class Table {
    final String name;
    final boolean hasAid;
    final List<Column> columns;

    Table(String name, boolean hasAid, Column... columns) {
      this.name = name;
      this.hasAid = hasAid;
      this.columns = Arrays.asList(columns);
    }
  }

  private static final Table[] TABLES = {
    new Table(
        "journal",
        true,
        new Column("aid", "TEXT", 1, 1),
        new Column("seq_nr", "INTEGER", 1, 2),
        new Column("occurred_at_ns", "INTEGER", 1, 0),
        new Column("manifest", "TEXT", 1, 0),
        new Column("payload", "BLOB", 1, 0)),
    new Table(
        "head",
        true,
        new Column("aid", "TEXT", 1, 1),
        new Column("type_name", "TEXT", 1, 0),
        new Column("seq_nr", "INTEGER", 1, 0),
        new Column("event_occurred_at_ns", "INTEGER", 1, 0),
        new Column("event_manifest", "TEXT", 1, 0),
        new Column("event_payload", "BLOB", 1, 0)),
    new Table(
        "snapshot_current",
        true,
        new Column("aid", "TEXT", 1, 1),
        new Column("seq_nr", "INTEGER", 1, 0),
        new Column("manifest", "TEXT", 1, 0),
        new Column("payload", "BLOB", 1, 0)),
    new Table(
        "snapshot_history",
        true,
        new Column("aid", "TEXT", 1, 1),
        new Column("seq_nr", "INTEGER", 1, 2),
        new Column("manifest", "TEXT", 1, 0),
        new Column("payload", "BLOB", 1, 0)),
    new Table(
        "config",
        false,
        new Column("singleton", "INTEGER", 1, 1),
        new Column("store_id", "TEXT", 1, 0),
        new Column("layout_version", "INTEGER", 1, 0),
        new Column("keep_snapshot_count", "INTEGER", 0, 0),
        new Column("retention_mode", "TEXT", 1, 0),
        new Column("change_feed_enabled", "INTEGER", 1, 0))
  };

  private SqliteLayout() {}

  static boolean isUninitialized(Connection c) throws SQLException {
    try (Statement s = c.createStatement();
        ResultSet rs =
            s.executeQuery(
                "SELECT count(*) FROM sqlite_schema WHERE lower(name) NOT GLOB 'sqlite_*'")) {
      rs.next();
      return rs.getLong(1) == 0;
    }
  }

  static void create(Connection c, OptionalInt keepCount) throws SQLException {
    for (String ddl : DDL) {
      try (Statement s = c.createStatement()) {
        s.execute(ddl);
      }
    }
    try (PreparedStatement p = c.prepareStatement(INSERT_CONFIG)) {
      p.setString(1, UUID.randomUUID().toString());
      if (keepCount.isPresent()) {
        p.setInt(2, keepCount.getAsInt());
      } else {
        p.setNull(2, Types.INTEGER);
      }
      p.executeUpdate();
    }
  }

  static void verify(Connection c, OptionalInt keepCount) throws SQLException {
    for (Table table : TABLES) {
      verifyTable(c, table);
    }
    verifyConfig(c, keepCount);
  }

  private static void verifyTable(Connection c, Table table) throws SQLException {
    try (PreparedStatement p =
        c.prepareStatement(
            "SELECT \"strict\" FROM pragma_table_list WHERE schema = 'main' AND name = ?")) {
      p.setString(1, table.name);
      try (ResultSet rs = p.executeQuery()) {
        if (!rs.next()) {
          throw mismatch("table " + table.name + " is missing");
        }
        if (rs.getInt(1) != 1) {
          throw mismatch("table " + table.name + " is not STRICT");
        }
      }
    }
    List<String> actual = new ArrayList<>();
    try (PreparedStatement p =
        c.prepareStatement(
            "SELECT name, type, \"notnull\", pk FROM pragma_table_info(?) ORDER BY cid")) {
      p.setString(1, table.name);
      try (ResultSet rs = p.executeQuery()) {
        while (rs.next()) {
          actual.add(
              rs.getString(1)
                  + ":"
                  + rs.getString(2).toUpperCase()
                  + ":"
                  + rs.getInt(3)
                  + ":"
                  + rs.getInt(4));
        }
      }
    }
    List<String> expected = new ArrayList<>();
    for (Column column : table.columns) {
      expected.add(column.describe());
    }
    if (!expected.equals(actual)) {
      throw mismatch("columns of " + table.name + " differ: " + actual);
    }
    if (table.hasAid) {
      verifyAidCollation(c, table.name);
    }
  }

  private static void verifyAidCollation(Connection c, String table) throws SQLException {
    try (PreparedStatement p =
        c.prepareStatement(
            "SELECT x.coll FROM pragma_index_list(?, 'main') AS l,"
                + " pragma_index_xinfo(l.name, 'main') AS x"
                + " WHERE l.origin = 'pk' AND x.key = 1 AND x.name = 'aid'")) {
      p.setString(1, table);
      try (ResultSet rs = p.executeQuery()) {
        if (!rs.next()) {
          throw mismatch("primary key of " + table + " does not include aid");
        }
        String collation = rs.getString(1);
        if (rs.next() || !"BINARY".equalsIgnoreCase(collation)) {
          throw mismatch("aid of " + table + " must use BINARY collation");
        }
      }
    }
  }

  private static void verifyConfig(Connection c, OptionalInt keepCount) throws SQLException {
    try (Statement s = c.createStatement();
        ResultSet rs =
            s.executeQuery(
                "SELECT store_id, layout_version, keep_snapshot_count, retention_mode,"
                    + " change_feed_enabled FROM config")) {
      if (!rs.next()) {
        throw mismatch("config row is missing");
      }
      String storeId = rs.getString(1);
      long layoutVersion = rs.getLong(2);
      Object keep = rs.getObject(3);
      Long keepStored = keep == null ? null : rs.getLong(3);
      String retentionMode = rs.getString(4);
      long changeFeed = rs.getLong(5);
      if (rs.next()) {
        throw mismatch("config has more than one row");
      }
      if (layoutVersion != 1) {
        throw mismatch("layout_version must be 1: " + layoutVersion);
      }
      if (!isLowerCaseUuid(storeId)) {
        throw mismatch("store_id must be a lower-case UUID");
      }
      if (!"delete".equals(retentionMode)) {
        throw mismatch("retention_mode must be delete: " + retentionMode);
      }
      if (changeFeed == 1) {
        throw mismatch("the store has a change feed, which this client does not provide");
      }
      if (changeFeed != 0) {
        throw mismatch("change_feed_enabled must be 0: " + changeFeed);
      }
      boolean sameKeep =
          keepCount.isPresent()
              ? keepStored != null && keepStored == keepCount.getAsInt()
              : keepStored == null;
      if (!sameKeep) {
        throw mismatch(
            "keep_snapshot_count differs: stored="
                + keepStored
                + ", client="
                + (keepCount.isPresent() ? String.valueOf(keepCount.getAsInt()) : "none"));
      }
    }
  }

  private static boolean isLowerCaseUuid(String value) {
    if (value == null) {
      return false;
    }
    try {
      return UUID.fromString(value).toString().equals(value);
    } catch (IllegalArgumentException notUuid) {
      return false;
    }
  }

  private static ConfigurationException mismatch(String detail) {
    return new ConfigurationException("sqlite layout or configuration mismatch: " + detail);
  }
}
