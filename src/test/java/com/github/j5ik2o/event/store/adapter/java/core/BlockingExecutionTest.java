package com.github.j5ik2o.event.store.adapter.java.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(15)
class BlockingExecutionTest {
  private static void await(CountDownLatch latch) throws InterruptedException {
    assertTrue(latch.await(5, TimeUnit.SECONDS), "latch deadline");
  }

  private static <T> T get(CompletableFuture<T> future) throws Exception {
    return future.get(5, TimeUnit.SECONDS);
  }

  private static void drain(BlockingExecution execution) throws Exception {
    execution.close();
    get(execution.drained().toCompletableFuture());
  }

  @Test
  void positiveCapacityAndEmptyClose() throws Exception {
    assertThrows(IllegalArgumentException.class, () -> BlockingExecution.singleWorker(0));
    assertThrows(IllegalArgumentException.class, () -> BlockingExecution.singleWorker(-1));
    BlockingExecution execution = BlockingExecution.singleWorker(1);
    execution.close();
    execution.close();
    drain(execution);
    assertInstanceOf(
        RejectedExecutionException.class,
        assertThrows(ExecutionException.class, () -> get(execution.submit(() -> fail("closed"))))
            .getCause());
  }

  @Test
  void callerReturnsWhileWorkerBlockedAndSaturationNeverInvokesRejectedWork() throws Exception {
    BlockingExecution execution = BlockingExecution.singleWorker(2);
    CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
    Thread caller = Thread.currentThread();
    List<Integer> order = Collections.synchronizedList(new ArrayList<>());
    try {
      CompletableFuture<Integer> first =
          execution.submit(() -> {
            assertNotSame(caller, Thread.currentThread());
            assertFalse(Thread.currentThread().isDaemon());
            entered.countDown();
            await(release);
            order.add(1);
            return 1;
          });
      await(entered);
      assertFalse(first.isDone());
      CompletableFuture<Integer> second =
          execution.submit(() -> {
            order.add(2);
            return 2;
          });
      CompletableFuture<Integer> third =
          execution.submit(() -> {
            order.add(3);
            return 3;
          });
      CompletableFuture<Object> rejected = execution.submit(() -> fail("full task invoked"));
      assertInstanceOf(
          RejectedExecutionException.class,
          assertThrows(ExecutionException.class, () -> get(rejected)).getCause());
      execution.close();
      assertFalse(execution.drained().toCompletableFuture().isDone());
      release.countDown();
      assertEquals(1, get(first));
      assertEquals(2, get(second));
      assertEquals(3, get(third));
      drain(execution);
      assertEquals(Arrays.asList(1, 2, 3), order);
    } finally {
      release.countDown();
      drain(execution);
    }
  }

  @Test
  void queuedCancelSkipsInvocationButRetainsCapacity() throws Exception {
    BlockingExecution execution = BlockingExecution.singleWorker(1);
    CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
    AtomicInteger invoked = new AtomicInteger(), cleaned = new AtomicInteger();
    try {
      execution.submit(() -> {
        entered.countDown();
        await(release);
        return null;
      });
      await(entered);
      CompletableFuture<?> queued =
          execution.submit(() -> {
            try {
              return invoked.incrementAndGet();
            } finally {
              cleaned.incrementAndGet();
            }
          });
      assertTrue(queued.cancel(true));
      assertInstanceOf(
          RejectedExecutionException.class,
          assertThrows(
                  ExecutionException.class,
                  () -> get(execution.submit(() -> fail("slot released early"))))
              .getCause());
      execution.close();
      assertFalse(execution.drained().toCompletableFuture().isDone());
      release.countDown();
      drain(execution);
      assertTrue(queued.isCancelled());
      assertEquals(0, invoked.get());
      assertEquals(0, cleaned.get());
    } finally {
      release.countDown();
      drain(execution);
    }
  }

  @Test
  void inFlightCancellationDoesNotInterruptReleaseOrSkipCleanup() throws Exception {
    for (boolean interrupt : new boolean[] {false, true}) {
      BlockingExecution execution = BlockingExecution.singleWorker(1);
      CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
      AtomicBoolean cleaned = new AtomicBoolean();
      try {
        CompletableFuture<?> running =
            execution.submit(() -> {
              entered.countDown();
              try {
                await(release);
                assertFalse(Thread.currentThread().isInterrupted());
              } finally {
                cleaned.set(true);
              }
              return null;
            });
        await(entered);
        CompletableFuture<Boolean> next = execution.submit(cleaned::get);
        assertTrue(running.cancel(interrupt));
        execution.close();
        assertFalse(cleaned.get());
        assertFalse(next.isDone());
        assertFalse(execution.drained().toCompletableFuture().isDone());
        release.countDown();
        assertTrue(get(next));
        drain(execution);
      } finally {
        release.countDown();
        drain(execution);
      }
    }
  }

  @Test
  void consumerCannotCompleteOrCancelLibraryDrain() throws Exception {
    BlockingExecution execution = BlockingExecution.singleWorker(1);
    CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
    try {
      execution.submit(() -> {
        entered.countDown();
        await(release);
        return null;
      });
      await(entered);
      CompletableFuture<Void> cancelled = execution.drained().toCompletableFuture();
      assertTrue(cancelled.cancel(true));
      assertTrue(execution.drained().toCompletableFuture().complete(null));
      assertThrows(
          UnsupportedOperationException.class,
          () -> ((CompletableFuture<Void>) execution.drained()).complete(null));
      execution.close();
      assertFalse(execution.drained().toCompletableFuture().isDone());
      release.countDown();
      drain(execution);
    } finally {
      release.countDown();
      drain(execution);
    }
  }

  @Test
  void completionCallbackMayReenterAndStallsDrainUntilItReturns() throws Exception {
    BlockingExecution execution = BlockingExecution.singleWorker(1);
    CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
    CountDownLatch callback = new CountDownLatch(1), callbackRelease = new CountDownLatch(1);
    AtomicReference<CompletableFuture<Integer>> reentrant = new AtomicReference<>();
    try {
      CompletableFuture<Integer> first =
          execution.submit(() -> {
            entered.countDown();
            await(release);
            return 1;
          });
      await(entered);
      CompletableFuture<?> listener =
          first.thenRun(() -> {
            reentrant.set(execution.submit(() -> 2));
            execution.close();
            callback.countDown();
            try {
              await(callbackRelease);
            } catch (InterruptedException e) {
              throw new AssertionError(e);
            }
          });
      release.countDown();
      await(callback);
      assertFalse(reentrant.get().isDone());
      assertFalse(execution.drained().toCompletableFuture().isDone());
      callbackRelease.countDown();
      get(listener);
      assertEquals(2, get(reentrant.get()));
      drain(execution);
    } finally {
      release.countDown();
      callbackRelease.countDown();
      drain(execution);
    }
  }

  @Test
  void ordinaryFailurePreservesCauseAndContinues() throws Exception {
    BlockingExecution execution = BlockingExecution.singleWorker(1);
    RuntimeException cause = new RuntimeException("operation");
    try {
      assertSame(
          cause,
          assertThrows(
                  ExecutionException.class,
                  () ->
                      get(
                          execution.submit(() -> {
                            throw cause;
                          })))
              .getCause());
      assertEquals(7, get(execution.submit(() -> 7)));
    } finally {
      drain(execution);
    }
  }

  @Test
  void interruptLeftByOneCommandNeverFailsLaterQueuedCommands() throws Exception {
    for (String source : new String[] {"operation", "failing operation", "worker callback"}) {
      BlockingExecution execution = BlockingExecution.singleWorker(2);
      CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
      try {
        CompletableFuture<Integer> first =
            execution.submit(() -> {
              entered.countDown();
              await(release);
              if (!source.equals("worker callback")) {
                Thread.currentThread().interrupt();
              }
              if (source.equals("failing operation") && Thread.currentThread().isInterrupted()) {
                throw new IllegalStateException("interrupted");
              }
              return 1;
            });
        await(entered);
        CompletableFuture<?> callback =
            first.handle((value, failure) -> {
              if (source.equals("worker callback")) {
                Thread.currentThread().interrupt();
              }
              return null;
            });
        CompletableFuture<Integer> second =
            execution.submit(() -> {
              Thread.sleep(1);
              return 2;
            });
        CompletableFuture<Integer> third =
            execution.submit(() -> {
              Thread.sleep(1);
              return 3;
            });
        release.countDown();
        get(callback);
        if (source.equals("failing operation")) {
          assertInstanceOf(
              IllegalStateException.class,
              assertThrows(ExecutionException.class, () -> get(first)).getCause(),
              source);
        } else {
          assertEquals(1, get(first), source);
        }
        assertEquals(2, get(second), source);
        assertEquals(3, get(third), source);
      } finally {
        release.countDown();
        drain(execution);
      }
    }
  }

  @Test
  void fatalRethrowsAfterCleanupAndReplacesWorkerInFifoOrder() throws Exception {
    CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
    CountDownLatch handlerEntered = new CountDownLatch(1), handlerRelease = new CountDownLatch(1);
    AssertionError fatal = new AssertionError("controlled");
    AtomicBoolean cleaned = new AtomicBoolean();
    AtomicReference<Throwable> uncaught = new AtomicReference<>();
    AtomicReference<Thread> oldWorker = new AtomicReference<>();
    ThreadFactory factory =
        action -> {
          Thread thread = new Thread(action);
          thread.setUncaughtExceptionHandler((t, failure) -> {
            uncaught.set(failure);
            handlerEntered.countDown();
            try {
              await(handlerRelease);
            } catch (InterruptedException e) {
              throw new AssertionError(e);
            }
          });
          return thread;
        };
    BlockingExecution execution = new BlockingExecution(2, factory);
    List<Integer> order = Collections.synchronizedList(new ArrayList<>());
    try {
      CompletableFuture<?> failed =
          execution.submit(() -> {
            oldWorker.set(Thread.currentThread());
            entered.countDown();
            try {
              await(release);
              throw fatal;
            } finally {
              cleaned.set(true);
            }
          });
      await(entered);
      CompletableFuture<?> next =
          execution.submit(() -> {
            assertTrue(cleaned.get());
            assertNotSame(oldWorker.get(), Thread.currentThread());
            order.add(2);
            return null;
          });
      CompletableFuture<?> last =
          execution.submit(() -> {
            order.add(3);
            return null;
          });
      execution.close();
      release.countDown();
      await(handlerEntered);
      assertSame(fatal, assertThrows(ExecutionException.class, () -> get(failed)).getCause());
      assertSame(fatal, uncaught.get());
      assertTrue(cleaned.get());
      assertFalse(next.isDone());
      assertFalse(execution.drained().toCompletableFuture().isDone());
      handlerRelease.countDown();
      get(next);
      get(last);
      drain(execution);
      assertEquals(Arrays.asList(2, 3), order);
    } finally {
      release.countDown();
      handlerRelease.countDown();
      drain(execution);
    }
  }

  @Test
  void cancelledFatalOperationStillCleansUpAndRecovers() throws Exception {
    CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
    AtomicBoolean cleaned = new AtomicBoolean();
    BlockingExecution execution =
        new BlockingExecution(
            1,
            action -> {
              Thread worker = new Thread(action);
              worker.setUncaughtExceptionHandler(
                  (thread, failure) -> assertInstanceOf(AssertionError.class, failure));
              return worker;
            });
    try {
      CompletableFuture<?> failed =
          execution.submit(() -> {
            entered.countDown();
            try {
              await(release);
              throw new AssertionError("cancelled fatal");
            } finally {
              cleaned.set(true);
            }
          });
      await(entered);
      CompletableFuture<Boolean> next = execution.submit(cleaned::get);
      assertTrue(failed.cancel(true));
      execution.close();
      release.countDown();
      assertTrue(get(next));
      drain(execution);
      assertTrue(failed.isCancelled());
    } finally {
      release.countDown();
      drain(execution);
    }
  }

  @Test
  void failedReplacementFailsAcceptedWorkAndTermination() throws Exception {
    AtomicInteger starts = new AtomicInteger();
    AssertionError recovery = new AssertionError("start failed");
    CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
    BlockingExecution execution =
        new BlockingExecution(
            2,
            action -> {
              if (starts.incrementAndGet() > 1) throw recovery;
              Thread t = new Thread(action);
              t.setUncaughtExceptionHandler((thread, failure) -> {});
              return t;
            });
    try {
      CompletableFuture<?> failed =
          execution.submit(() -> {
            entered.countDown();
            await(release);
            throw new AssertionError("operation");
          });
      await(entered);
      CompletableFuture<?> a = execution.submit(() -> fail("stranded a invoked"));
      CompletableFuture<?> b = execution.submit(() -> fail("stranded b invoked"));
      execution.close();
      release.countDown();
      assertThrows(ExecutionException.class, () -> get(failed));
      assertSame(recovery, assertThrows(ExecutionException.class, () -> get(a)).getCause());
      assertSame(recovery, assertThrows(ExecutionException.class, () -> get(b)).getCause());
      assertSame(
          recovery,
          assertThrows(
                  ExecutionException.class, () -> get(execution.drained().toCompletableFuture()))
              .getCause());
      assertInstanceOf(
          RejectedExecutionException.class,
          assertThrows(ExecutionException.class, () -> get(execution.submit(() -> null)))
              .getCause());
    } finally {
      release.countDown();
      execution.close();
    }
  }

  @Test
  void closeAdmissionCancellationRaceNeverStrandsAnAcceptedCommand() throws Exception {
    for (int i = 0; i < 100; i++) {
      BlockingExecution execution = BlockingExecution.singleWorker(1);
      CountDownLatch entered = new CountDownLatch(1),
          release = new CountDownLatch(1),
          race = new CountDownLatch(1);
      AtomicInteger invocations = new AtomicInteger();
      AtomicReference<CompletableFuture<Integer>> result = new AtomicReference<>();
      Thread producer =
          new Thread(() -> {
            try {
              await(race);
              result.set(execution.submit(invocations::incrementAndGet));
              result.get().cancel(true);
            } catch (InterruptedException e) {
              throw new AssertionError(e);
            }
          });
      Thread closer =
          new Thread(() -> {
            try {
              await(race);
              execution.close();
            } catch (InterruptedException e) {
              throw new AssertionError(e);
            }
          });
      try {
        execution.submit(() -> {
          entered.countDown();
          await(release);
          return null;
        });
        await(entered);
        producer.start();
        closer.start();
        race.countDown();
        release.countDown();
        producer.join(5000);
        closer.join(5000);
        assertFalse(producer.isAlive());
        assertFalse(closer.isAlive());
        drain(execution);
        assertNotNull(result.get());
        assertTrue(result.get().isDone());
        assertTrue(invocations.get() <= 1);
        if (!result.get().isCancelled()) {
          try {
            assertEquals(1, get(result.get()));
          } catch (ExecutionException rejected) {
            assertInstanceOf(RejectedExecutionException.class, rejected.getCause());
            assertEquals(0, invocations.get());
          }
        }
      } finally {
        race.countDown();
        release.countDown();
        drain(execution);
      }
    }
  }

  @Test
  void failedCancelAfterCompleteMustNotSkipAcceptedWork() throws Exception {
    failedCancelMustNotSkipAcceptedWork(0);
  }

  @Test
  void failedCancelAfterExceptionalCompletionMustNotSkipAcceptedWork() throws Exception {
    failedCancelMustNotSkipAcceptedWork(1);
  }

  @Test
  void failedCancelAfterTimeoutMustNotSkipAcceptedWork() throws Exception {
    failedCancelMustNotSkipAcceptedWork(2);
  }

  private void failedCancelMustNotSkipAcceptedWork(int mode) throws Exception {
    BlockingExecution e = BlockingExecution.singleWorker(1);
    CountDownLatch in = new CountDownLatch(1), out = new CountDownLatch(1);
    AtomicInteger calls = new AtomicInteger();
    try {
      e.submit(() -> {
        in.countDown();
        await(out);
        return null;
      });
      await(in);
      CompletableFuture<Integer> f = e.submit(calls::incrementAndGet);
      if (mode == 0) f.complete(99);
      else if (mode == 1) f.completeExceptionally(new RuntimeException("consumer"));
      else {
        f.orTimeout(1, TimeUnit.MILLISECONDS);
        assertThrows(ExecutionException.class, () -> f.get(3, TimeUnit.SECONDS));
      }
      assertFalse(f.cancel(false));
      out.countDown();
      drain(e);
      assertEquals(
          1, calls.get(), "failed cancel must not suppress accepted operation mode=" + mode);
    } finally {
      out.countDown();
      drain(e);
    }
  }

  private static Object retainedOperation(CompletableFuture<?> future) throws Exception {
    java.lang.reflect.Field enclosing =
        java.util.Arrays.stream(future.getClass().getDeclaredFields())
            .filter(field -> field.getName().startsWith("this$"))
            .findFirst()
            .orElseThrow();
    enclosing.setAccessible(true);
    Object command = enclosing.get(future);
    java.lang.reflect.Field operation = command.getClass().getDeclaredField("operation");
    operation.setAccessible(true);
    return operation.get(command);
  }

  @Test
  void terminatedCommandsReleaseInputsWhileRunningCancellationRetainsThem() throws Exception {
    for (int mode = 0; mode < 6; mode++) {
      int outcome = mode;
      AtomicInteger workers = new AtomicInteger(), calls = new AtomicInteger();
      CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
      BlockingExecution execution =
          new BlockingExecution(
              1,
              action -> {
                if (workers.incrementAndGet() > 1 && outcome == 4) {
                  throw new IllegalStateException("replacement denied");
                }
                Thread worker = new Thread(action);
                worker.setUncaughtExceptionHandler((thread, failure) -> {});
                return worker;
              });
      java.util.concurrent.Callable<Integer> operation =
          new java.util.concurrent.Callable<Integer>() {
            final byte[] input = new byte[1024 * 1024];

            public Integer call() throws Exception {
              calls.incrementAndGet();
              entered.countDown();
              await(release);
              if (outcome == 1) throw new IllegalArgumentException("ordinary");
              if (outcome == 2 || outcome == 4) throw new AssertionError("fatal");
              return input.length;
            }
          };
      CompletableFuture<Integer> result;
      try {
        if (mode == 3 || mode == 4) {
          execution.submit(() -> {
            entered.countDown();
            await(release);
            if (outcome == 4) throw new AssertionError("fatal leader");
            return null;
          });
          await(entered);
          result = execution.submit(operation);
          assertSame(operation, retainedOperation(result));
          if (mode == 3) assertTrue(result.cancel(false));
        } else {
          result = execution.submit(operation);
          await(entered);
          assertSame(operation, retainedOperation(result));
          if (mode == 0) {
            assertTrue(result.cancel(true));
            assertSame(operation, retainedOperation(result));
          }
        }
        release.countDown();
        execution.close();
        if (mode == 4) {
          assertThrows(
              ExecutionException.class, () -> get(execution.drained().toCompletableFuture()));
          assertThrows(ExecutionException.class, () -> get(result));
        } else {
          drain(execution);
        }
        org.junit.jupiter.api.Assertions.assertNull(
            retainedOperation(result), "owned input after mode " + mode);
        assertEquals(mode == 3 || mode == 4 ? 0 : 1, calls.get());
      } finally {
        release.countDown();
        execution.close();
        try {
          get(execution.drained().toCompletableFuture());
        } catch (ExecutionException failure) {
          if (mode != 4) throw failure;
        }
      }
    }
  }

  @Test
  void workerHelpsCancellationWhileCancellingCallbackIsStalled() throws Exception {
    BlockingExecution execution = BlockingExecution.singleWorker(2);
    CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
    CountDownLatch callback = new CountDownLatch(1), callbackRelease = new CountDownLatch(1);
    AtomicInteger calls = new AtomicInteger();
    AtomicReference<Boolean> cancelled = new AtomicReference<>();
    Thread canceller = null;
    try {
      execution.submit(() -> {
        entered.countDown();
        await(release);
        return null;
      });
      await(entered);
      CompletableFuture<Integer> queued = execution.submit(calls::incrementAndGet);
      CompletableFuture<Integer> next = execution.submit(() -> 7);
      queued.whenComplete((value, failure) -> {
        callback.countDown();
        try {
          await(callbackRelease);
        } catch (InterruptedException interrupted) {
          throw new AssertionError(interrupted);
        }
      });
      canceller = new Thread(() -> cancelled.set(queued.cancel(false)));
      canceller.start();
      await(callback);
      release.countDown();
      assertEquals(7, get(next));
      drain(execution);
      assertEquals(0, calls.get());
      assertTrue(canceller.isAlive());
      callbackRelease.countDown();
      canceller.join(5000);
      assertFalse(canceller.isAlive());
      assertEquals(Boolean.TRUE, cancelled.get());
    } finally {
      release.countDown();
      callbackRelease.countDown();
      if (canceller != null) {
        canceller.join(5000);
        assertFalse(canceller.isAlive());
      }
      drain(execution);
    }
  }

  @org.junit.jupiter.api.AfterAll
  static void noLaneWorkersRemain() throws Exception {
    for (Thread thread : Thread.getAllStackTraces().keySet()) {
      if (thread.getName().equals("event-store-blocking")
          || thread.getName().matches("Thread-[0-9]+")) {
        thread.join(5000);
        assertFalse(thread.isAlive(), "remaining worker " + thread);
      }
    }
  }

  @Test
  void completionCancellationAndRunningRacePreservesWorkWhenCancelFails() throws Exception {
    for (int i = 0; i < 100; i++) {
      BlockingExecution execution = BlockingExecution.singleWorker(1);
      CountDownLatch entered = new CountDownLatch(1),
          release = new CountDownLatch(1),
          race = new CountDownLatch(1);
      AtomicInteger calls = new AtomicInteger();
      AtomicReference<Boolean> cancelled = new AtomicReference<>();
      AtomicReference<Throwable> threadFailure = new AtomicReference<>();
      Thread completer = null, canceller = null;
      try {
        execution.submit(() -> {
          entered.countDown();
          await(release);
          return null;
        });
        await(entered);
        CompletableFuture<Integer> result = execution.submit(calls::incrementAndGet);
        completer =
            new Thread(() -> {
              try {
                await(race);
                result.complete(99);
              } catch (Throwable failure) {
                threadFailure.set(failure);
              }
            });
        canceller =
            new Thread(() -> {
              try {
                await(race);
                for (int attempt = 0; attempt < 100; attempt++) {
                  cancelled.set(result.cancel(false));
                }
              } catch (Throwable failure) {
                threadFailure.set(failure);
              }
            });
        completer.start();
        canceller.start();
        race.countDown();
        release.countDown();
        completer.join(5000);
        canceller.join(5000);
        assertFalse(completer.isAlive());
        assertFalse(canceller.isAlive());
        org.junit.jupiter.api.Assertions.assertNull(threadFailure.get());
        assertNotNull(cancelled.get());
        drain(execution);
        if (!cancelled.get()) assertEquals(1, calls.get());
        else assertTrue(calls.get() == 0 || calls.get() == 1);
      } finally {
        race.countDown();
        release.countDown();
        if (completer != null) {
          completer.join(5000);
          assertFalse(completer.isAlive());
        }
        if (canceller != null) {
          canceller.join(5000);
          assertFalse(canceller.isAlive());
        }
        drain(execution);
      }
    }
  }
}
