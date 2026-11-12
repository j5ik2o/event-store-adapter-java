package com.github.j5ik2o.event.store.adapter.java.core;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Builds a {@link BlockingExecution} whose first worker starts normally but whose replacement
 * cannot start, so a fatal error on the worker makes the execution unrecoverable.
 */
public final class UnrecoverableExecution {
  private UnrecoverableExecution() {}

  /**
   * The replacement worker fails to start with {@code recoveryFailure}. The first worker ignores
   * its own uncaught error, so the error reaches only the result of the operation that threw it.
   */
  public static BlockingExecution withoutReplacement(int queueCapacity, Error recoveryFailure) {
    AtomicInteger starts = new AtomicInteger();
    return new BlockingExecution(
        queueCapacity,
        action -> {
          if (starts.incrementAndGet() > 1) {
            throw recoveryFailure;
          }
          Thread worker = new Thread(action, "unrecoverable-worker");
          worker.setUncaughtExceptionHandler((thread, failure) -> {});
          return worker;
        });
  }
}
