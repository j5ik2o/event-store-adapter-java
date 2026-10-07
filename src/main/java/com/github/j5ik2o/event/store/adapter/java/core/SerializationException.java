package com.github.j5ik2o.event.store.adapter.java.core;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** A serialization or deserialization failure. / 直列化・復元の失敗。 */
public final class SerializationException extends EventStoreException {

  /**
   * Creates an exception. / 例外を作ります。
   *
   * @param message message / メッセージ
   */
  public SerializationException(@Nonnull String message) {
    super(message, null);
  }

  /**
   * Creates an exception with a cause. / 原因つきで例外を作ります。
   *
   * @param message message / メッセージ
   * @param cause cause / 原因
   */
  public SerializationException(@Nonnull String message, @Nullable Throwable cause) {
    super(message, cause);
  }

  @Override
  @Nonnull
  public ErrorCategory category() {
    return ErrorCategory.SERIALIZATION;
  }
}
