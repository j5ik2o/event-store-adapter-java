package com.github.j5ik2o.event.store.adapter.java.dynamodb;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Delivers retention-failure callbacks for one asynchronous store on its own daemon thread.
 *
 * <p>The thread is created on demand, ends after an idle timeout, and is never a caller or SDK
 * thread. The executor is never shut down because the public API has no close operation; the idle
 * timeout returns the thread when the store stops reporting failures. The queue is unbounded: a
 * listener that blocks delays later notifications for this store and the public futures that wait
 * for one, but never blocks a caller or an SDK completion thread. Tasks capture only the
 * notification data, never the SDK client, so a pending task does not keep the store alive.
 */
final class DynamoDbRetentionNotifier {
  static final String THREAD_NAME_PREFIX = "eswa-retention-notify-";
  private static final long IDLE_TIMEOUT_SECONDS = 60;
  private static final AtomicInteger SEQUENCE = new AtomicInteger();
  private final ThreadPoolExecutor executor;

  DynamoDbRetentionNotifier() {
    executor =
        new ThreadPoolExecutor(
            1,
            1,
            IDLE_TIMEOUT_SECONDS,
            TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(),
            task -> {
              Thread thread = new Thread(task, THREAD_NAME_PREFIX + SEQUENCE.incrementAndGet());
              thread.setDaemon(true);
              return thread;
            });
    executor.allowCoreThreadTimeOut(true);
  }

  /**
   * Runs {@code notification} on this store's notifier thread and completes the result afterwards.
   * The result always completes once the task is accepted, so a committed write never hangs because
   * of the notifier.
   */
  CompletableFuture<Void> deliver(Runnable notification) {
    CompletableFuture<Void> done = new CompletableFuture<>();
    // The executor is never shut down and its queue is unbounded, so execute does not reject in
    // any reachable state. A rejection would be an invariant violation, not a notification to
    // drop, so it is deliberately not caught here.
    executor.execute(() -> {
      try {
        notification.run();
      } catch (Throwable listenerFailure) {
        // The callback cannot change the success of a committed write.
      } finally {
        done.complete(null);
      }
    });
    return done;
  }
}
