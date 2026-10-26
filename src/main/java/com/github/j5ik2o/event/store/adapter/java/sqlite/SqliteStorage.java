package com.github.j5ik2o.event.store.adapter.java.sqlite;

import com.github.j5ik2o.event.store.adapter.java.core.ConfigurationException;
import com.github.j5ik2o.event.store.adapter.java.core.RetentionPolicy;
import com.github.j5ik2o.event.store.adapter.java.core.StorageException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.locks.ReentrantLock;
import javax.annotation.Nonnull;

/**
 * One local SQLite file opened with one owned JDBC connection. / 所有する JDBC 接続 1 本で開いた、 ローカルの SQLite
 * ファイル 1 つ。
 */
public final class SqliteStorage implements AutoCloseable {
  final ReentrantLock lock = new ReentrantLock();
  final RetentionPolicy policy;
  final SqliteStorageHooks hooks;
  private final Connection connection;
  private boolean closed;

  private SqliteStorage(Connection connection, RetentionPolicy policy, SqliteStorageHooks hooks) {
    this.connection = connection;
    this.policy = policy;
    this.hooks = hooks;
  }

  /**
   * Initializes a new or empty file once. / 新規または空のファイルを一度だけ初期化します。
   *
   * @param file SQLite file / SQLite ファイル
   * @param config settings / 設定
   * @throws ConfigurationException when the file is already initialized or holds other tables /
   *     初期化済み、または他の表があるとき
   */
  public static void initialize(@Nonnull Path file, @Nonnull SqliteStorageConfig config) {
    requireArguments(file, config);
    Connection c = SqliteConnections.open(file, config.lockWait(), true);
    try {
      SqliteTransactions.begin(c, true);
      if (!SqliteLayout.isUninitialized(c)) {
        throw new ConfigurationException(
            "sqlite file is already initialized or holds other tables");
      }
      SqliteLayout.create(c, config.retentionPolicy().keepCount());
      SqliteTransactions.commit(c);
    } catch (SQLException failure) {
      SqliteTransactions.rollbackQuietly(c, failure);
      throw SqliteTransactions.storage("failed to initialize sqlite file", failure);
    } catch (RuntimeException failure) {
      SqliteTransactions.rollbackQuietly(c, failure);
      throw failure;
    } finally {
      closeAfterUse(c);
    }
  }

  /**
   * Opens an existing initialized file and verifies it. / 初期化済みの既存ファイルを開き、照合します。
   *
   * @param file SQLite file / SQLite ファイル
   * @param config settings / 設定
   * @return opened storage / 開いた保存先
   * @throws StorageException when the file does not exist / ファイルがないとき
   * @throws ConfigurationException when the file is uninitialized or does not match / 未初期化または
   *     不一致のとき
   */
  @Nonnull
  public static SqliteStorage open(@Nonnull Path file, @Nonnull SqliteStorageConfig config) {
    return open(file, config, SqliteStorageHooks.NONE);
  }

  static SqliteStorage open(Path file, SqliteStorageConfig config, SqliteStorageHooks hooks) {
    requireArguments(file, config);
    Connection c = SqliteConnections.open(file, config.lockWait(), false);
    try {
      SqliteTransactions.begin(c, false);
      if (SqliteLayout.isUninitialized(c)) {
        throw new ConfigurationException("sqlite file is not initialized");
      }
      SqliteLayout.verify(c, config.retentionPolicy().keepCount());
      SqliteTransactions.commit(c);
      return new SqliteStorage(c, config.retentionPolicy(), hooks);
    } catch (SQLException failure) {
      SqliteTransactions.rollbackQuietly(c, failure);
      SqliteConnections.closeQuietly(c, failure);
      throw SqliteTransactions.storage("failed to open sqlite file", failure);
    } catch (RuntimeException failure) {
      SqliteTransactions.rollbackQuietly(c, failure);
      SqliteConnections.closeQuietly(c, failure);
      throw failure;
    }
  }

  /** Closes the connection. Idempotent. / 接続を閉じます。何度呼んでもよい。 */
  @Override
  public void close() {
    lock.lock();
    try {
      if (closed) {
        return;
      }
      closed = true;
      try {
        connection.close();
      } catch (SQLException failure) {
        throw SqliteTransactions.storage("failed to close sqlite connection", failure);
      }
    } finally {
      lock.unlock();
    }
  }

  /** ロックを保持している間に呼ぶ。 */
  Connection connectionForOperation() {
    if (closed) {
      throw new StorageException("sqlite storage is closed");
    }
    return connection;
  }

  private static void requireArguments(Path file, SqliteStorageConfig config) {
    if (file == null) {
      throw new ConfigurationException("sqlite file is required");
    }
    if (config == null) {
      throw new ConfigurationException("sqlite storage config is required");
    }
  }

  private static void closeAfterUse(Connection c) {
    try {
      c.close();
    } catch (SQLException failure) {
      throw SqliteTransactions.storage("failed to close sqlite connection", failure);
    }
  }
}
