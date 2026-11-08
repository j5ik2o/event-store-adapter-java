package com.github.j5ik2o.event.store.adapter.java.core;

import java.util.Objects;
import javax.annotation.Nonnull;

/** A failure of the retention work. / 保持処理の失敗。 */
public final class RetentionFailure {

  private final AggregateId aggregateId;
  private final RetentionMode mode;
  private final Throwable cause;

  /**
   * Creates a failure. / 失敗を作ります。
   *
   * @param aggregateId aggregate ID / 集約 ID
   * @param mode retention mode / 保持の方式
   * @param cause cause / 原因
   */
  public RetentionFailure(
      @Nonnull AggregateId aggregateId, @Nonnull RetentionMode mode, @Nonnull Throwable cause) {
    this.aggregateId = Objects.requireNonNull(aggregateId, "aggregateId");
    this.mode = Objects.requireNonNull(mode, "mode");
    this.cause = Objects.requireNonNull(cause, "cause");
  }

  @Nonnull
  public AggregateId aggregateId() {
    return aggregateId;
  }

  @Nonnull
  public RetentionMode mode() {
    return mode;
  }

  @Nonnull
  public Throwable cause() {
    return cause;
  }
}
