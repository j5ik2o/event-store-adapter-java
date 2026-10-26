package com.github.j5ik2o.event.store.adapter.java.sqlite;

import com.github.j5ik2o.event.store.adapter.java.core.StorageException;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import org.sqlite.SQLiteErrorCode;
import org.sqlite.SQLiteException;

/** 自動確定の接続上で、明示的に BEGIN / COMMIT / ROLLBACK を発行する。 */
final class SqliteTransactions {
  private SqliteTransactions() {}

  static void begin(Connection c, boolean immediate) throws SQLException {
    execute(c, immediate ? "BEGIN IMMEDIATE" : "BEGIN");
  }

  static void commit(Connection c) throws SQLException {
    execute(c, "COMMIT");
  }

  static void rollbackQuietly(Connection c, Throwable primary) {
    try {
      execute(c, "ROLLBACK");
    } catch (SQLException failure) {
      primary.addSuppressed(failure);
    }
  }

  static boolean isPrimaryKeyViolation(SQLException e) {
    return e instanceof SQLiteException
        && ((SQLiteException) e).getResultCode() == SQLiteErrorCode.SQLITE_CONSTRAINT_PRIMARYKEY;
  }

  static StorageException storage(String message, Throwable cause) {
    return new StorageException(message, cause);
  }

  private static void execute(Connection c, String sql) throws SQLException {
    try (Statement statement = c.createStatement()) {
      statement.execute(sql);
    }
  }
}
