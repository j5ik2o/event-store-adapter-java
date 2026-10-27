package com.github.j5ik2o.event.store.adapter.java.spanner;

import com.github.j5ik2o.event.store.adapter.java.core.ConfigurationException;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Spanner storage settings. / Spanner の保存先の設定。 */
public final class SpannerStorageConfig {
  private static final int DEFAULT_RETRY_LIMIT = 5;

  private final String tablePrefix;
  private final SpannerRetentionPolicy retentionPolicy;
  private final int transactionRetryLimit;
  final SpannerTableNames tableNames;

  private SpannerStorageConfig(
      String tablePrefix, SpannerRetentionPolicy retentionPolicy, int transactionRetryLimit) {
    this.tablePrefix = tablePrefix;
    this.retentionPolicy = retentionPolicy;
    this.transactionRetryLimit = transactionRetryLimit;
    this.tableNames = SpannerTableNames.of(tablePrefix);
  }

  /**
   * Creates a builder. / ビルダーを作ります。
   *
   * @return builder / ビルダー
   */
  @Nonnull
  public static Builder builder() {
    return new Builder();
  }

  /**
   * Returns the table prefix. / 表の接頭辞を返します。
   *
   * @return prefix / 接頭辞
   */
  @Nonnull
  public String tablePrefix() {
    return tablePrefix;
  }

  /**
   * Returns the retention policy. / 保持方式を返します。
   *
   * @return retention policy / 保持方式
   */
  @Nonnull
  public SpannerRetentionPolicy retentionPolicy() {
    return retentionPolicy;
  }

  /**
   * Returns how many times an aborted transaction is retried. The first attempt is not counted. /
   * 中断されたトランザクションを再試行する回数の上限を返します。初回は数えません。
   *
   * @return retry limit / 再試行の上限
   */
  public int transactionRetryLimit() {
    return transactionRetryLimit;
  }

  /** Builder. / ビルダー。 */
  public static final class Builder {
    private String tablePrefix;
    private SpannerRetentionPolicy retentionPolicy = SpannerRetentionPolicy.none();
    private int transactionRetryLimit = DEFAULT_RETRY_LIMIT;
    private boolean changeFeed;

    private Builder() {}

    /**
     * Sets the table prefix. / 表の接頭辞を設定します。
     *
     * @param v prefix / 接頭辞
     * @return this builder / このビルダー
     */
    @Nonnull
    public Builder tablePrefix(@Nullable String v) {
      this.tablePrefix = v;
      return this;
    }

    /**
     * Sets the retention policy. / 保持方式を設定します。
     *
     * @param v retention policy / 保持方式
     * @return this builder / このビルダー
     */
    @Nonnull
    public Builder retentionPolicy(@Nullable SpannerRetentionPolicy v) {
      this.retentionPolicy = v;
      return this;
    }

    /**
     * Sets the retry limit. / 再試行の上限を設定します。
     *
     * @param v retry limit, 0 or greater / 再試行の上限（0 以上）
     * @return this builder / このビルダー
     */
    @Nonnull
    public Builder transactionRetryLimit(int v) {
      this.transactionRetryLimit = v;
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
     * @throws ConfigurationException when a value is invalid or a change feed is requested / 値が不正、
     *     または変更フィードを求めたとき
     */
    @Nonnull
    public SpannerStorageConfig build() {
      if (changeFeed) {
        throw new ConfigurationException("spanner storage does not support a change feed");
      }
      if (retentionPolicy == null) {
        throw new ConfigurationException("retention policy is required");
      }
      if (transactionRetryLimit < 0) {
        throw new ConfigurationException("transaction retry limit must not be negative");
      }
      return new SpannerStorageConfig(tablePrefix, retentionPolicy, transactionRetryLimit);
    }
  }
}
