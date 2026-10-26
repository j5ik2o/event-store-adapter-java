package com.github.j5ik2o.event.store.adapter.java.sqlite;

import static com.github.j5ik2o.event.store.adapter.java.sqlite.SqliteTestFixtures.execute;
import static com.github.j5ik2o.event.store.adapter.java.sqlite.SqliteTestFixtures.initialized;
import static com.github.j5ik2o.event.store.adapter.java.sqlite.SqliteTestFixtures.settings;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.github.j5ik2o.event.store.adapter.java.core.ConfigurationException;
import com.github.j5ik2o.event.store.adapter.java.core.RetentionPolicy;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** aid 列そのものの照合と主キー索引の照合を、open がどちらも BINARY として検証すること。 */
class SqliteAidCollationTest {
  @TempDir Path directory;

  private static final List<String> TABLES =
      List.of("journal", "head", "snapshot_current", "snapshot_history");

  /** 表ごとの DDL。aidColumn は aid 列定義の照合部分、aidKey は主キー内での aid の書き方。 */
  private static String ddl(String table, String aidColumn, String aidKey) {
    String seq = "seq_nr INTEGER NOT NULL CHECK (seq_nr BETWEEN 1 AND 9007199254740991)";
    String result;
    switch (table) {
      case "journal":
        result =
            "CREATE TABLE journal (aid TEXT "
                + aidColumn
                + " NOT NULL, "
                + seq
                + ", occurred_at_ns INTEGER NOT NULL, manifest TEXT NOT NULL, payload BLOB NOT NULL,"
                + " PRIMARY KEY ("
                + aidKey
                + ", seq_nr)) STRICT";
        break;
      case "head":
        result =
            "CREATE TABLE head (aid TEXT "
                + aidColumn
                + " NOT NULL, type_name TEXT NOT NULL, "
                + seq
                + ", event_occurred_at_ns INTEGER NOT NULL, event_manifest TEXT NOT NULL,"
                + " event_payload BLOB NOT NULL, PRIMARY KEY ("
                + aidKey
                + ")) STRICT";
        break;
      case "snapshot_current":
        result =
            "CREATE TABLE snapshot_current (aid TEXT "
                + aidColumn
                + " NOT NULL, "
                + seq
                + ", manifest TEXT NOT NULL, payload BLOB NOT NULL, PRIMARY KEY ("
                + aidKey
                + ")) STRICT";
        break;
      case "snapshot_history":
        result =
            "CREATE TABLE snapshot_history (aid TEXT "
                + aidColumn
                + " NOT NULL, "
                + seq
                + ", manifest TEXT NOT NULL, payload BLOB NOT NULL, PRIMARY KEY ("
                + aidKey
                + ", seq_nr)) STRICT";
        break;
      default:
        throw new IllegalArgumentException(table);
    }
    return result;
  }

  @Test
  void nocaseColumnWithBinaryPrimaryKeyIsRejectedForEveryTable() {
    for (String table : TABLES) {
      Path dir = directory.resolve("nocase-" + table);
      dir.toFile().mkdirs();
      assertRejectedIn(dir, table, "COLLATE NOCASE");
    }
  }

  @Test
  void rtrimColumnWithBinaryPrimaryKeyIsRejectedForEveryTable() {
    for (String table : TABLES) {
      Path dir = directory.resolve("rtrim-" + table);
      dir.toFile().mkdirs();
      assertRejectedIn(dir, table, "COLLATE RTRIM");
    }
  }

  private void assertRejectedIn(Path dir, String table, String aidColumn) {
    Path file = initialized(dir, RetentionPolicy.none());
    execute(file, "DROP TABLE " + table);
    execute(file, ddl(table, aidColumn, "aid COLLATE BINARY"));
    assertThrows(
        ConfigurationException.class,
        () -> SqliteStorage.open(file, settings(RetentionPolicy.none())),
        table + " " + aidColumn);
  }

  @Test
  void defaultAndExplicitBinaryColumnsAreAcceptedForEveryTable() {
    for (String table : TABLES) {
      Path dir = directory.resolve("binary-" + table);
      dir.toFile().mkdirs();
      for (String column : List.of("", "COLLATE BINARY")) {
        Path file = initialized(dir, RetentionPolicy.none());
        execute(file, "DROP TABLE " + table);
        execute(file, ddl(table, column, "aid"));
        assertDoesNotThrow(
            () -> SqliteStorage.open(file, settings(RetentionPolicy.none())).close(),
            table + " [" + column + "]");
        file.toFile().delete();
      }
    }
  }

  @Test
  void checkLeavesStoredRowsUntouched() {
    Path file = initialized(directory, RetentionPolicy.none());
    execute(
        file,
        "INSERT INTO journal (aid, seq_nr, occurred_at_ns, manifest, payload) VALUES ('Order-1',"
            + " 1, 0, 'm', x'00')");
    assertDoesNotThrow(() -> SqliteStorage.open(file, settings(RetentionPolicy.none())).close());
    assertDoesNotThrow(() -> SqliteStorage.open(file, settings(RetentionPolicy.none())).close());
    assertEquals(1L, SqliteTestFixtures.count(file, "journal"));
  }
}
