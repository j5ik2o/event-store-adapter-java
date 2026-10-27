package com.github.j5ik2o.event.store.adapter.java.spanner;

import com.github.j5ik2o.event.store.adapter.java.core.ConfigurationException;
import com.github.j5ik2o.event.store.adapter.java.core.RetentionMode;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import javax.annotation.Nonnull;

/**
 * Retention policy of the Spanner storage. The TTL grace is counted in whole days because the row
 * deletion policy of Spanner is declared in days. / Spanner 保存先の保持方式。行削除ポリシーが日数で宣言される ため、TTL
 * の猶予は日数で受け取ります。
 */
public final class SpannerRetentionPolicy {
  private static final SpannerRetentionPolicy NONE = new SpannerRetentionPolicy(null, 0, 0);

  private final RetentionMode mode;
  private final int keepCount;
  private final long graceDays;

  private SpannerRetentionPolicy(RetentionMode mode, int keepCount, long graceDays) {
    this.mode = mode;
    this.keepCount = keepCount;
    this.graceDays = graceDays;
  }

  /**
   * No history is kept. / 履歴を持ちません。
   *
   * @return policy / 方針
   */
  @Nonnull
  public static SpannerRetentionPolicy none() {
    return NONE;
  }

  /**
   * Deletes old snapshot history. / 古いスナップショット履歴を削除します。
   *
   * @param keepCount number to keep, 1 or greater / 保持件数（1 以上）
   * @return policy / 方針
   */
  @Nonnull
  public static SpannerRetentionPolicy delete(int keepCount) {
    checkKeepCount(keepCount);
    return new SpannerRetentionPolicy(RetentionMode.DELETE, keepCount, 0);
  }

  /**
   * Marks old snapshot history so that Spanner TTL removes it later. / 古いスナップショット履歴に印を付け、 後で
   * Spanner の TTL に削除させます。
   *
   * @param keepCount number to keep, 1 or greater / 保持件数（1 以上）
   * @param graceDays grace in days, 1 or greater / 猶予（日、1 以上）
   * @return policy / 方針
   */
  @Nonnull
  public static SpannerRetentionPolicy ttl(int keepCount, long graceDays) {
    checkKeepCount(keepCount);
    if (graceDays < 1) {
      throw new ConfigurationException("grace days must be 1 or greater: " + graceDays);
    }
    return new SpannerRetentionPolicy(RetentionMode.TTL, keepCount, graceDays);
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

  /**
   * Returns the number of history rows to keep. / 保持する履歴の件数を返します。
   *
   * @return count, empty for none / 件数。none なら空
   */
  @Nonnull
  public OptionalInt keepCount() {
    return mode == null ? OptionalInt.empty() : OptionalInt.of(keepCount);
  }

  /**
   * Returns the TTL grace in days. / TTL の猶予（日）を返します。
   *
   * @return days, empty unless the mode is TTL / 日数。TTL でなければ空
   */
  @Nonnull
  public OptionalLong graceDays() {
    return mode == RetentionMode.TTL ? OptionalLong.of(graceDays) : OptionalLong.empty();
  }
}
