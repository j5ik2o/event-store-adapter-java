package com.github.j5ik2o.event.store.adapter.java.bigtable;

import com.github.j5ik2o.event.store.adapter.java.core.ConfigurationException;
import com.github.j5ik2o.event.store.adapter.java.core.RetentionMode;
import com.github.j5ik2o.event.store.adapter.java.core.RetentionPolicy;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Settings for a Bigtable storage. Invalid values fail at build, before any request. / Bigtable
 * 保存先の設定。不正な値は、通信の前に build で失敗します。
 */
public final class BigtableStorageConfig {
  /** The smallest envelope (an empty snapshot) is 17 bytes. */
  static final int MINIMUM_ENVELOPE_LIMIT_BYTES = 17;

  static final int MAXIMUM_ENVELOPE_LIMIT_BYTES = 8 * 1024 * 1024;
  static final int DEFAULT_ENVELOPE_LIMIT_BYTES = 1024 * 1024;

  private final String journalTableId;
  private final String headTableId;
  private final RetentionPolicy retentionPolicy;
  private final int envelopeLimitBytes;

  private BigtableStorageConfig(
      String journalTableId,
      String headTableId,
      RetentionPolicy retentionPolicy,
      int envelopeLimitBytes) {
    this.journalTableId = journalTableId;
    this.headTableId = headTableId;
    this.retentionPolicy = retentionPolicy;
    this.envelopeLimitBytes = envelopeLimitBytes;
  }

  /**
   * Starts a builder. / ビルダーを作ります。
   *
   * @return builder / ビルダー
   */
  @Nonnull
  public static Builder builder() {
    return new Builder();
  }

  /**
   * Returns the journal table ID. / journal 表の ID を返します。
   *
   * @return table ID / 表 ID
   */
  @Nonnull
  public String journalTableId() {
    return journalTableId;
  }

  /**
   * Returns the head table ID. / head 表の ID を返します。
   *
   * @return table ID / 表 ID
   */
  @Nonnull
  public String headTableId() {
    return headTableId;
  }

  /**
   * Returns the retention policy. / 保持方針を返します。
   *
   * @return retention policy / 保持方針
   */
  @Nonnull
  public RetentionPolicy retentionPolicy() {
    return retentionPolicy;
  }

  /**
   * Returns the upper limit in bytes of one serialized envelope. / 直列化した封筒 1 つの上限（バイト）を返します。
   *
   * @return limit in bytes / 上限（バイト）
   */
  public int envelopeLimitBytes() {
    return envelopeLimitBytes;
  }

  /** Builder of {@link BigtableStorageConfig}. / {@link BigtableStorageConfig} のビルダー。 */
  public static final class Builder {
    private String journalTableId;
    private String headTableId;
    private RetentionPolicy retentionPolicy = RetentionPolicy.none();
    private int envelopeLimitBytes = DEFAULT_ENVELOPE_LIMIT_BYTES;
    private boolean changeFeed;

    private Builder() {}

    /**
     * Sets the journal table ID. / journal 表の ID を設定します。
     *
     * @param v table ID / 表 ID
     * @return this builder / このビルダー
     */
    @Nonnull
    public Builder journalTableId(@Nullable String v) {
      this.journalTableId = v;
      return this;
    }

    /**
     * Sets the head table ID. / head 表の ID を設定します。
     *
     * @param v table ID / 表 ID
     * @return this builder / このビルダー
     */
    @Nonnull
    public Builder headTableId(@Nullable String v) {
      this.headTableId = v;
      return this;
    }

    /**
     * Sets the retention policy. The default is none. TTL is not provided and fails at build. /
     * 保持方針を設定します。既定は保持なしです。TTL は提供せず、build で失敗します。
     *
     * @param v retention policy / 保持方針
     * @return this builder / このビルダー
     */
    @Nonnull
    public Builder retentionPolicy(@Nullable RetentionPolicy v) {
      this.retentionPolicy = v;
      return this;
    }

    /**
     * Sets the upper limit of one envelope (17 bytes to 8 MiB, default 1 MiB). / 封筒 1 つの上限を設定します（17
     * バイトから 8 MiB。既定は 1 MiB）。
     *
     * @param v limit in bytes / 上限（バイト）
     * @return this builder / このビルダー
     */
    @Nonnull
    public Builder envelopeLimitBytes(int v) {
      this.envelopeLimitBytes = v;
      return this;
    }

    /**
     * Requests a change feed. This version provides none, so {@code true} fails at build. /
     * 変更フィードを求めます。この版は提供しないため、{@code true} は build で失敗します。
     *
     * @param v whether a change feed is requested / 変更フィードを求めるか
     * @return this builder / このビルダー
     */
    @Nonnull
    public Builder changeFeed(boolean v) {
      this.changeFeed = v;
      return this;
    }

    /**
     * Builds the settings. / 設定を作ります。
     *
     * @return settings / 設定
     * @throws ConfigurationException when a value is invalid / 値が不正なとき
     */
    @Nonnull
    public BigtableStorageConfig build() {
      if (changeFeed) {
        throw new ConfigurationException("bigtable storage does not support a change feed");
      }
      if (retentionPolicy == null) {
        throw new ConfigurationException("retention policy is required");
      }
      if (retentionPolicy.mode().filter(m -> m == RetentionMode.TTL).isPresent()) {
        throw new ConfigurationException("bigtable storage does not support a TTL retention");
      }
      if (journalTableId == null || journalTableId.isEmpty()) {
        throw new ConfigurationException("journal table ID is required");
      }
      if (headTableId == null || headTableId.isEmpty()) {
        throw new ConfigurationException("head table ID is required");
      }
      if (journalTableId.equals(headTableId)) {
        throw new ConfigurationException("journal and head must be different tables");
      }
      if (envelopeLimitBytes < MINIMUM_ENVELOPE_LIMIT_BYTES
          || envelopeLimitBytes > MAXIMUM_ENVELOPE_LIMIT_BYTES) {
        throw new ConfigurationException(
            "envelope limit must be between "
                + MINIMUM_ENVELOPE_LIMIT_BYTES
                + " and "
                + MAXIMUM_ENVELOPE_LIMIT_BYTES
                + " bytes: "
                + envelopeLimitBytes);
      }
      return new BigtableStorageConfig(
          journalTableId, headTableId, retentionPolicy, envelopeLimitBytes);
    }
  }
}
