package com.github.j5ik2o.event.store.adapter.java.sqlite;

import com.github.j5ik2o.event.store.adapter.java.core.ConfigurationException;
import com.github.j5ik2o.event.store.adapter.java.core.RetentionMode;
import com.github.j5ik2o.event.store.adapter.java.core.RetentionPolicy;
import java.time.Duration;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** SQLite storage settings. / SQLite の保存先の設定。 */
public final class SqliteStorageConfig {
  private static final Duration DEFAULT_LOCK_WAIT = Duration.ofSeconds(5);

  private final RetentionPolicy retentionPolicy;
  private final Duration lockWait;

  private SqliteStorageConfig(RetentionPolicy retentionPolicy, Duration lockWait) {
    this.retentionPolicy = retentionPolicy;
    this.lockWait = lockWait;
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
   * Returns the retention policy. / 保持方式を返します。
   *
   * @return retention policy / 保持方式
   */
  @Nonnull
  public RetentionPolicy retentionPolicy() {
    return retentionPolicy;
  }

  /**
   * Returns how long a connection waits for the write lock. / 書き込みロックを待つ時間を返します。
   *
   * @return lock wait / ロックの待ち時間
   */
  @Nonnull
  public Duration lockWait() {
    return lockWait;
  }

  /** Builder. / ビルダー。 */
  public static final class Builder {
    private RetentionPolicy retentionPolicy = RetentionPolicy.none();
    private Duration lockWait = DEFAULT_LOCK_WAIT;

    private Builder() {}

    /**
     * Sets the retention policy. / 保持方式を設定します。
     *
     * @param v retention policy / 保持方式
     * @return this builder / このビルダー
     */
    @Nonnull
    public Builder retentionPolicy(@Nullable RetentionPolicy v) {
      this.retentionPolicy = v;
      return this;
    }

    /**
     * Sets the lock wait. / ロックの待ち時間を設定します。
     *
     * @param v lock wait / ロックの待ち時間
     * @return this builder / このビルダー
     */
    @Nonnull
    public Builder lockWait(@Nullable Duration v) {
      this.lockWait = v;
      return this;
    }

    /**
     * Builds the settings. / 設定を作ります。
     *
     * @return settings / 設定
     * @throws ConfigurationException when a value is null, the retention policy is TTL, or the lock
     *     wait is negative or above Integer.MAX_VALUE milliseconds / 値が null、保持方式が TTL、 待ち時間が負または
     *     Integer.MAX_VALUE ミリ秒超のとき
     */
    @Nonnull
    public SqliteStorageConfig build() {
      if (retentionPolicy == null) {
        throw new ConfigurationException("retention policy is required");
      }
      if (retentionPolicy.mode().orElse(null) == RetentionMode.TTL) {
        throw new ConfigurationException("sqlite storage does not support TTL");
      }
      if (lockWait == null) {
        throw new ConfigurationException("lock wait is required");
      }
      if (lockWait.isNegative()) {
        throw new ConfigurationException("lock wait must not be negative");
      }
      if (lockWait.compareTo(Duration.ofMillis(Integer.MAX_VALUE)) > 0) {
        throw new ConfigurationException("lock wait must fit in 32-bit milliseconds");
      }
      return new SqliteStorageConfig(retentionPolicy, lockWait);
    }
  }
}
