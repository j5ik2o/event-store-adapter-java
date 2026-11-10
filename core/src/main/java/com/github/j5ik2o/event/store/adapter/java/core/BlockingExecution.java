package com.github.j5ik2o.event.store.adapter.java.core;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;

/**
 * An explicitly owned, bounded FIFO lane for complete blocking operations.
 *
 * <p>The application must close this non-daemon resource and await {@link #drained()} before
 * releasing storage. Result cancellation never interrupts an operation or proves rollback.
 */
public final class BlockingExecution implements AutoCloseable {
  private enum Lifecycle {
    OPEN,
    DRAINING,
    TERMINATED
  }

  private enum CommandState {
    QUEUED,
    CANCELLING,
    RUNNING,
    FINISHED,
    SKIPPED
  }

  private final Object lock = new Object();
  private final int queueCapacity;
  private final ThreadFactory threads;
  private final ArrayDeque<Command<?>> pending = new ArrayDeque<>();
  private final CompletableFuture<Void> termination = new CompletableFuture<>();
  private final CompletionStage<Void> drainView = termination.minimalCompletionStage();
  private Lifecycle lifecycle = Lifecycle.OPEN;
  private Command<?> active;

  private BlockingExecution(int queueCapacity) {
    this(queueCapacity, runnable -> new Thread(null, runnable, "event-store-blocking", 0, false));
  }

  BlockingExecution(int queueCapacity, ThreadFactory threads) {
    if (queueCapacity <= 0) {
      throw new IllegalArgumentException("queueCapacity must be positive");
    }
    this.queueCapacity = queueCapacity;
    this.threads = Objects.requireNonNull(threads, "threads");
    startWorker();
  }

  /**
   * Creates one non-daemon worker with room for Q pending operations plus one active operation.
   *
   * @param queueCapacity positive pending queue capacity
   * @return a resource owned by the caller
   */
  public static BlockingExecution singleWorker(int queueCapacity) {
    return new BlockingExecution(queueCapacity);
  }

  /**
   * Submits an entire synchronous operation for backend integration. Only admission bookkeeping
   * runs on the caller. Full or closed admission returns a failed future without invoking the
   * operation. Input references must remain unchanged until actual operation termination.
   *
   * <p>Cancellation before the atomic running transition skips invocation, retaining its slot until
   * removed by the worker. Cancellation after that transition abandons only the result, even with
   * {@code mayInterruptIfRunning=true}. Completion callbacks may run on the worker; never wait for
   * another operation or drain on this lane from a callback.
   *
   * @param operation whole operation, including cleanup and post-commit work
   * @param <T> result type
   * @return operation result, independently of actual execution termination
   */
  public <T> CompletableFuture<T> submit(Callable<T> operation) {
    Command<T> command = new Command<>(Objects.requireNonNull(operation, "operation"));
    synchronized (lock) {
      if (lifecycle != Lifecycle.OPEN || (active != null && pending.size() == queueCapacity)) {
        return CompletableFuture.failedFuture(
            new RejectedExecutionException("Execution full or closed"));
      }
      if (active == null) {
        active = command;
      } else {
        pending.addLast(command);
      }
      lock.notifyAll();
    }
    return command.result;
  }

  /** Closes admission idempotently, without waiting for or interrupting accepted operations. */
  @Override
  public void close() {
    synchronized (lock) {
      if (lifecycle == Lifecycle.OPEN) {
        lifecycle = Lifecycle.DRAINING;
      }
      lock.notifyAll();
    }
  }

  /**
   * Returns a read-only termination stage. Consumer-derived futures cannot modify library drain. It
   * includes operation cleanup and worker-run result callbacks, and fails if recovery fails.
   *
   * @return actual termination, available only after admission closes or recovery fails
   */
  public CompletionStage<Void> drained() {
    return drainView;
  }

  private void startWorker() {
    Thread worker = Objects.requireNonNull(threads.newThread(this::runWorker), "worker");
    worker.setDaemon(false);
    Thread.UncaughtExceptionHandler handler = worker.getUncaughtExceptionHandler();
    worker.setUncaughtExceptionHandler((thread, failure) -> {
      try {
        handler.uncaughtException(thread, failure);
      } finally {
        try {
          startWorker();
        } catch (Throwable recoveryFailure) {
          if (recoveryFailure != failure) {
            recoveryFailure.addSuppressed(failure);
          }
          failTermination(recoveryFailure);
        }
      }
    });
    worker.start();
  }

  private void runWorker() {
    while (true) {
      Command<?> command;
      boolean cancelling;
      boolean invoke;
      synchronized (lock) {
        while (active == null && lifecycle == Lifecycle.OPEN) {
          try {
            lock.wait();
          } catch (InterruptedException ignored) {
            // This resource has no interrupt-based cancellation policy.
          }
        }
        command = active;
        if (command == null) {
          lifecycle = Lifecycle.TERMINATED;
        } else if (command.state == CommandState.QUEUED) {
          command.state = CommandState.RUNNING;
        }
        cancelling = command != null && command.state == CommandState.CANCELLING;
        invoke = command != null && command.state == CommandState.RUNNING;
      }
      if (command == null) {
        termination.complete(null);
        return;
      }
      try {
        while (cancelling) {
          // Help the reserved cancellation without waiting for the cancelling caller's callbacks.
          command.result.cancel(false);
          synchronized (lock) {
            if (command.state == CommandState.QUEUED) {
              command.state = CommandState.RUNNING;
            }
            cancelling = command.state == CommandState.CANCELLING;
            invoke = command.state == CommandState.RUNNING;
          }
        }
        if (invoke) {
          command.invoke();
        }
      } finally {
        synchronized (lock) {
          command.operation = null;
          command.state = CommandState.FINISHED;
          active = pending.pollFirst();
        }
      }
    }
  }

  private void failTermination(Throwable failure) {
    List<Command<?>> stranded = new ArrayList<>();
    synchronized (lock) {
      lifecycle = Lifecycle.TERMINATED;
      if (active != null) {
        stranded.add(active);
        active = null;
      }
      stranded.addAll(pending);
      pending.clear();
      for (Command<?> command : stranded) {
        command.operation = null;
        command.state = CommandState.FINISHED;
      }
    }
    for (Command<?> command : stranded) {
      command.result.completeExceptionally(failure);
    }
    termination.completeExceptionally(failure);
  }

  private final class Command<T> {
    private Callable<T> operation;
    private CommandState state = CommandState.QUEUED;
    private final CompletableFuture<T> result =
        new CompletableFuture<T>() {
          @Override
          public boolean cancel(boolean mayInterruptIfRunning) {
            synchronized (lock) {
              if (state == CommandState.QUEUED) {
                state = CommandState.CANCELLING;
              }
            }
            // Completion may invoke user callbacks: never do it while holding the lane monitor.
            boolean cancelled = super.cancel(false);
            synchronized (lock) {
              if (state == CommandState.CANCELLING) {
                if (cancelled) {
                  state = CommandState.SKIPPED;
                } else {
                  state = CommandState.QUEUED;
                }
              }
            }
            return cancelled;
          }
        };

    Command(Callable<T> operation) {
      this.operation = operation;
    }

    void invoke() {
      try {
        T value;
        try {
          value = operation.call();
        } finally {
          operation = null;
        }
        result.complete(value);
      } catch (Exception failure) {
        result.completeExceptionally(failure);
      } catch (Error fatal) {
        result.completeExceptionally(fatal);
        throw fatal;
      }
    }
  }
}
