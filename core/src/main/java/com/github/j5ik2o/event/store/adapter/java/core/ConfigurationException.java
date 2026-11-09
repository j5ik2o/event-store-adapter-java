package com.github.j5ik2o.event.store.adapter.java.core;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** An invalid configuration. / 不正な設定。 */
public final class ConfigurationException extends EventStoreException {

  /**
   * Creates an exception. / 例外を作ります。
   *
   * @param message message / メッセージ
   */
  public ConfigurationException(@Nonnull String message) {
    super(message, null);
  }

  /**
   * Creates an exception with a cause. / 原因つきで例外を作ります。
   *
   * @param message message / メッセージ
   * @param cause cause / 原因
   */
  public ConfigurationException(@Nonnull String message, @Nullable Throwable cause) {
    super(message, cause);
  }

  @Override
  @Nonnull
  public ErrorCategory category() {
    return ErrorCategory.CONFIGURATION;
  }
}
