package com.github.j5ik2o.event.store.adapter.java.dynamodb;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** Internal wait boundary; clients remain owned by the caller. */
interface Sleeper {
  Sleeper SYSTEM = new Sleeper() {};

  default void sleep(long millis) throws InterruptedException {
    Thread.sleep(millis);
  }

  default CompletableFuture<Void> sleepAsync(long millis) {
    return CompletableFuture.runAsync(
        () -> {}, CompletableFuture.delayedExecutor(millis, TimeUnit.MILLISECONDS));
  }
}
