package com.github.j5ik2o.event.store.adapter.java.core;

import java.math.BigInteger;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import javax.annotation.Nonnull;

/** Retention policy of a storage. / 保存先の保持の方針。 */
public final class RetentionPolicy {

  private static final RetentionPolicy NONE = new RetentionPolicy(null, 0, 0);

  private final RetentionMode mode;
  private final int keepCount;
  private final long graceSeconds;

  private RetentionPolicy(RetentionMode mode, int keepCount, long graceSeconds) {
    this.mode = mode;
    this.keepCount = keepCount;
    this.graceSeconds = graceSeconds;
  }

  /**
   * No history is kept. / 履歴を持たない。
   *
   * @return policy / 方針
   */
  @Nonnull
  public static RetentionPolicy none() {
    return NONE;
  }

  /**
   * Deletes old snapshot history. / 古いスナップショット履歴を削除する。
   *
   * @param keepCount number to keep, 1 or greater / 保持件数（1 以上）
   * @return policy / 方針
   */
  @Nonnull
  public static RetentionPolicy delete(int keepCount) {
    checkKeepCount(keepCount);
    return new RetentionPolicy(RetentionMode.DELETE, keepCount, 0);
  }

  /**
   * Marks old snapshot history for expiration by TTL. / 古いスナップショット履歴に TTL の期限を付ける。
   *
   * @param keepCount number to keep, 1 or greater / 保持件数（1 以上）
   * @param graceSeconds grace in seconds, 0 or greater / 猶予（秒、0 以上）
   * @return policy / 方針
   */
  @Nonnull
  public static RetentionPolicy ttl(int keepCount, long graceSeconds) {
    checkKeepCount(keepCount);
    if (graceSeconds < 0) {
      throw new ConfigurationException("grace seconds must be 0 or greater: " + graceSeconds);
    }
    return new RetentionPolicy(RetentionMode.TTL, keepCount, graceSeconds);
  }

  private static void checkKeepCount(int keepCount) {
    if (keepCount < 1) {
      throw new ConfigurationException("keep count must be 1 or greater: " + keepCount);
    }
  }

  /**
   * Returns the mode. / 方式を返します。
   *
   * @return mode, empty for none / 方式。none なら空
   */
  @Nonnull
  public Optional<RetentionMode> mode() {
    return Optional.ofNullable(mode);
  }

  @Nonnull
  public OptionalInt keepCount() {
    return mode == null ? OptionalInt.empty() : OptionalInt.of(keepCount);
  }

  @Nonnull
  public OptionalLong graceSeconds() {
    return mode == RetentionMode.TTL ? OptionalLong.of(graceSeconds) : OptionalLong.empty();
  }

  /**
   * Computes the expiry without overflow. / あふれずに期限を計算します。
   *
   * @param markedAtEpochSeconds marked time in epoch seconds / 印付けのエポック秒
   * @return expiry in epoch seconds / 期限のエポック秒
   * @throws IllegalStateException when the mode is not TTL / 方式が TTL でないとき
   */
  @Nonnull
  public BigInteger expiresAtEpochSeconds(long markedAtEpochSeconds) {
    if (mode != RetentionMode.TTL) {
      throw new IllegalStateException("retention mode is not TTL");
    }
    return BigInteger.valueOf(markedAtEpochSeconds).add(BigInteger.valueOf(graceSeconds));
  }
}
