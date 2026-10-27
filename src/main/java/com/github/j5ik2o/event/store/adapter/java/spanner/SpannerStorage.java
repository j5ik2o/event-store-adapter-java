package com.github.j5ik2o.event.store.adapter.java.spanner;

import com.github.j5ik2o.event.store.adapter.java.core.ConfigurationException;
import com.github.j5ik2o.event.store.adapter.java.core.StorageException;
import com.google.cloud.spanner.DatabaseClient;
import com.google.cloud.spanner.SpannerException;
import javax.annotation.Nonnull;

/**
 * A verified Spanner database. The caller owns the {@link DatabaseClient}; this class never closes
 * it and never runs DDL. / 照合済みの Spanner データベース。{@link DatabaseClient} は呼び出し側が所有し、 このクラスは閉じず、DDL
 * も実行しません。
 */
public final class SpannerStorage {
  final DatabaseClient client;
  final SpannerStorageConfig config;
  final SpannerStorageHooks hooks;

  private SpannerStorage(
      DatabaseClient client, SpannerStorageConfig config, SpannerStorageHooks hooks) {
    this.client = client;
    this.config = config;
    this.hooks = hooks;
  }

  /**
   * Verifies the layout and returns the storage. / 配置を照合して保存先を返します。
   *
   * @param client database client owned by the caller / 呼び出し側が所有するクライアント
   * @param config settings / 設定
   * @return storage / 保存先
   * @throws ConfigurationException when the tables or the configuration row do not match / 表や
   *     設定行が一致しないとき
   * @throws StorageException when the database cannot be read / データベースを読めないとき
   */
  @Nonnull
  public static SpannerStorage open(
      @Nonnull DatabaseClient client, @Nonnull SpannerStorageConfig config) {
    return open(client, config, SpannerStorageHooks.NONE);
  }

  static SpannerStorage open(
      DatabaseClient client, SpannerStorageConfig config, SpannerStorageHooks hooks) {
    if (client == null) {
      throw new ConfigurationException("database client is required");
    }
    if (config == null) {
      throw new ConfigurationException("spanner storage config is required");
    }
    try {
      SpannerLayout.verify(client, config);
    } catch (SpannerException failure) {
      throw new StorageException("failed to verify the spanner layout", failure);
    }
    return new SpannerStorage(client, config, hooks);
  }
}
