package com.github.j5ik2o.event.store.adapter.java.sqlite;

import com.github.j5ik2o.event.store.adapter.java.core.ConfigurationException;
import com.github.j5ik2o.event.store.adapter.java.core.StorageException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteOpenMode;

/** 接続を開き、プロファイルが求める接続設定を適用して読み直す。 */
final class SqliteConnections {
  private static final long[] MINIMUM_VERSION = {3, 51, 3};

  private SqliteConnections() {}

  static boolean meetsMinimumVersion(String version) {
    String[] parts = version.split("\\.", -1);
    if (parts.length != 3) {
      return false;
    }
    long[] numbers = new long[3];
    for (int i = 0; i < 3; i++) {
      try {
        numbers[i] = Long.parseLong(parts[i]);
      } catch (NumberFormatException notNumeric) {
        return false;
      }
    }
    for (int i = 0; i < 3; i++) {
      if (numbers[i] != MINIMUM_VERSION[i]) {
        return numbers[i] > MINIMUM_VERSION[i];
      }
    }
    return true;
  }

  static String fileName(Path file) {
    String name = file.toAbsolutePath().normalize().toString();
    if (name.indexOf('?') >= 0) {
      throw new ConfigurationException("sqlite file name must not contain '?'");
    }
    return name;
  }

  static Connection open(Path file, Duration lockWait, boolean create) {
    String name = fileName(file);
    if (!create && !Files.isRegularFile(file)) {
      throw new StorageException("sqlite file does not exist");
    }
    SQLiteConfig config = new SQLiteConfig();
    config.setSharedCache(false);
    config.setOpenMode(SQLiteOpenMode.PRIVATECACHE);
    if (!create) {
      config.resetOpenMode(SQLiteOpenMode.CREATE);
    }
    config.setBusyTimeout((int) lockWait.toMillis());
    Connection connection;
    try {
      connection = config.createConnection("jdbc:sqlite:" + name);
    } catch (SQLException failure) {
      throw new StorageException("failed to open sqlite file", failure);
    }
    try {
      applySettings(connection, lockWait);
      return connection;
    } catch (SQLException failure) {
      closeQuietly(connection, failure);
      throw new StorageException("failed to configure sqlite connection", failure);
    } catch (RuntimeException failure) {
      closeQuietly(connection, failure);
      throw failure;
    }
  }

  private static void applySettings(Connection connection, Duration lockWait) throws SQLException {
    try (Statement s = connection.createStatement()) {
      String version = readString(s, "SELECT sqlite_version()");
      if (!meetsMinimumVersion(version)) {
        throw new ConfigurationException("sqlite version " + version + " is older than 3.51.3");
      }
      s.execute("PRAGMA locking_mode = NORMAL");
      s.execute("PRAGMA journal_mode = WAL");
      s.execute("PRAGMA synchronous = FULL");
      s.execute("PRAGMA read_uncommitted = OFF");
      verifySettings(
          readString(s, "PRAGMA locking_mode"),
          readString(s, "PRAGMA journal_mode"),
          readLong(s, "PRAGMA synchronous"),
          readLong(s, "PRAGMA read_uncommitted"),
          readLong(s, "PRAGMA busy_timeout"),
          lockWait);
    }
  }

  static void verifySettings(
      String lockingMode,
      String journalMode,
      long synchronous,
      long readUncommitted,
      long busyTimeoutMillis,
      Duration lockWait) {
    if (!"normal".equalsIgnoreCase(lockingMode)) {
      throw new ConfigurationException("sqlite locking_mode must be normal: " + lockingMode);
    }
    if (!"wal".equalsIgnoreCase(journalMode)) {
      throw new ConfigurationException("sqlite journal_mode must be wal: " + journalMode);
    }
    if (synchronous != 2) {
      throw new ConfigurationException("sqlite synchronous must be FULL: " + synchronous);
    }
    if (readUncommitted != 0) {
      throw new ConfigurationException("sqlite read_uncommitted must be OFF: " + readUncommitted);
    }
    if (busyTimeoutMillis != lockWait.toMillis()) {
      throw new ConfigurationException(
          "sqlite busy_timeout must equal the lock wait: " + busyTimeoutMillis);
    }
  }

  static void closeQuietly(Connection connection, Throwable primary) {
    try {
      connection.close();
    } catch (SQLException failure) {
      primary.addSuppressed(failure);
    }
  }

  private static String readString(Statement s, String sql) throws SQLException {
    try (ResultSet rs = s.executeQuery(sql)) {
      if (!rs.next()) {
        throw new SQLException("no row for " + sql);
      }
      return rs.getString(1);
    }
  }

  private static long readLong(Statement s, String sql) throws SQLException {
    try (ResultSet rs = s.executeQuery(sql)) {
      if (!rs.next()) {
        throw new SQLException("no row for " + sql);
      }
      return rs.getLong(1);
    }
  }
}
