package com.github.j5ik2o.event.store.adapter.java.core;

import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import javax.annotation.Nonnull;

/** Helpers for event store errors. / イベントストアのエラーの補助。 */
public final class EventStoreExceptions {

  private EventStoreExceptions() {}

  /**
   * Removes CompletionException and ExecutionException wrappers. / CompletionException と
   * ExecutionException の包みを外します。
   *
   * @param t throwable / 例外
   * @return unwrapped throwable / 外した例外
   */
  @Nonnull
  public static Throwable unwrap(@Nonnull Throwable t) {
    Throwable current = t;
    while ((current instanceof CompletionException || current instanceof ExecutionException)
        && current.getCause() != null) {
      current = current.getCause();
    }
    return current;
  }
}
