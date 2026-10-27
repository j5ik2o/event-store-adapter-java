package com.github.j5ik2o.event.store.adapter.java.bigtable;

import com.github.j5ik2o.event.store.adapter.java.core.ConfigurationException;
import com.github.j5ik2o.event.store.adapter.java.core.StorageException;
import com.google.cloud.bigtable.admin.v2.BigtableTableAdminClient;
import com.google.cloud.bigtable.data.v2.BigtableDataClient;
import com.google.protobuf.ByteString;
import java.nio.charset.StandardCharsets;
import javax.annotation.Nonnull;

/**
 * A verified pair of Bigtable tables. The caller owns both clients, including the credentials, the
 * app profile pinned to one cluster (BT-1) and the metrics settings; this class never closes them
 * and never creates a table. / 照合済みの Bigtable の表の組。2 つのクライアント（認証情報、1 つのクラスタに固定した app
 * profile、指標の設定を含む）は呼び出し側が所有し、このクラスは閉じず、表も作りません。
 */
public final class BigtableStorage {
  final BigtableDataClient data;
  final BigtableStorageConfig config;
  final BigtableStorageHooks hooks;

  private BigtableStorage(
      BigtableDataClient data, BigtableStorageConfig config, BigtableStorageHooks hooks) {
    this.data = data;
    this.config = config;
    this.hooks = hooks;
  }

  /**
   * Verifies the layout and returns the storage. / 配置を照合して保存先を返します。
   *
   * @param data data client owned by the caller / 呼び出し側が所有するデータのクライアント
   * @param admin admin client owned by the caller / 呼び出し側が所有する管理のクライアント
   * @param config settings / 設定
   * @return storage / 保存先
   * @throws ConfigurationException when the tables or the configuration rows do not match / 表や
   *     設定行が一致しないとき
   * @throws StorageException when Bigtable cannot be reached / Bigtable に届かないとき
   */
  @Nonnull
  public static BigtableStorage open(
      @Nonnull BigtableDataClient data,
      @Nonnull BigtableTableAdminClient admin,
      @Nonnull BigtableStorageConfig config) {
    return open(data, admin, config, BigtableStorageHooks.NONE);
  }

  static BigtableStorage open(
      BigtableDataClient data,
      BigtableTableAdminClient admin,
      BigtableStorageConfig config,
      BigtableStorageHooks hooks) {
    if (data == null) {
      throw new ConfigurationException("data client is required");
    }
    if (admin == null) {
      throw new ConfigurationException("admin client is required");
    }
    if (config == null) {
      throw new ConfigurationException("bigtable storage config is required");
    }
    BigtableLayout.verify(data, admin, config);
    return new BigtableStorage(data, config, hooks);
  }

  /**
   * Writes the configuration row into both tables, only when neither has one. The tables must
   * already exist. / 両方の表に設定行を書きます。どちらにも設定行がないときだけで、表は先に作ってある必要があります。
   *
   * @param data data client owned by the caller / 呼び出し側が所有するデータのクライアント
   * @param config settings / 設定
   * @throws ConfigurationException when a configuration row already exists / 設定行が既にあるとき
   */
  public static void initialize(
      @Nonnull BigtableDataClient data, @Nonnull BigtableStorageConfig config) {
    if (data == null) {
      throw new ConfigurationException("data client is required");
    }
    if (config == null) {
      throw new ConfigurationException("bigtable storage config is required");
    }
    BigtableLayout.initialize(data, config);
  }

  /** The row key prefix of an aggregate. The hash can be replaced only through the hooks. */
  ByteString prefix(String aid) {
    long hash =
        hooks
            .aggregateHash(aid)
            .orElseGet(() -> BigtableKey.fnv1a64(aid.getBytes(StandardCharsets.UTF_8)));
    return BigtableKey.prefix(hash, aid);
  }
}
