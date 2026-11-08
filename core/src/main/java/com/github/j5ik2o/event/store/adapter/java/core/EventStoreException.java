package com.github.j5ik2o.event.store.adapter.java.core;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Base of all event store errors. / イベントストアのエラーの基底。 */
public abstract class EventStoreException extends RuntimeException {

  EventStoreException(@Nonnull String message, @Nullable Throwable cause) {
    super(message, cause);
  }

  /**
   * Returns the error category. / エラーの分類を返します。
   *
   * @return category / 分類
   */
  @Nonnull
  public abstract ErrorCategory category();
}
