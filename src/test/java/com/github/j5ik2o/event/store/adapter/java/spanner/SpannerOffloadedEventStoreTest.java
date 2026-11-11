package com.github.j5ik2o.event.store.adapter.java.spanner;

import static com.github.j5ik2o.event.store.adapter.java.spanner.SpannerTestFixtures.AID;
import static com.github.j5ik2o.event.store.adapter.java.spanner.SpannerTestFixtures.EMULATOR;
import static com.github.j5ik2o.event.store.adapter.java.spanner.SpannerTestFixtures.deleteStore;
import static com.github.j5ik2o.event.store.adapter.java.spanner.SpannerTestFixtures.event;
import static com.github.j5ik2o.event.store.adapter.java.spanner.SpannerTestFixtures.noneStore;
import static com.github.j5ik2o.event.store.adapter.java.spanner.SpannerTestFixtures.open;
import static com.github.j5ik2o.event.store.adapter.java.spanner.SpannerTestFixtures.settings;
import static com.github.j5ik2o.event.store.adapter.java.spanner.SpannerTestFixtures.snapshot;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

import com.github.j5ik2o.event.store.adapter.java.core.AggregateId;
import com.github.j5ik2o.event.store.adapter.java.core.AsyncEventStore;
import com.github.j5ik2o.event.store.adapter.java.core.BlockingExecution;
import com.github.j5ik2o.event.store.adapter.java.core.ConfigurationException;
import com.github.j5ik2o.event.store.adapter.java.core.ContractViolationException;
import com.github.j5ik2o.event.store.adapter.java.core.EventEnvelope;
import com.github.j5ik2o.event.store.adapter.java.core.EventStoreConfig;
import com.github.j5ik2o.event.store.adapter.java.core.JsonPayloadSerializer;
import com.github.j5ik2o.event.store.adapter.java.core.OptimisticLockException;
import com.github.j5ik2o.event.store.adapter.java.core.PayloadSerializer;
import com.github.j5ik2o.event.store.adapter.java.core.SerializationException;
import com.github.j5ik2o.event.store.adapter.java.core.SnapshotReadResult;
import com.github.j5ik2o.event.store.adapter.java.core.StorageException;
import com.google.api.gax.retrying.RetrySettings;
import com.google.cloud.spanner.ErrorCode;
import com.google.cloud.spanner.Spanner;
import com.google.cloud.spanner.SpannerException;
import com.google.cloud.spanner.SpannerExceptionFactory;
import io.grpc.Context;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.Timeout;

/** Offloaded operations against the real Spanner SDK talking to the emulator. */
@Timeout(60)
class SpannerOffloadedEventStoreTest {
  private static final class Gate implements Consumer<String> {
    private final String point;
    private final Throwable failure;
    private final AtomicBoolean armed = new AtomicBoolean(true);
    private final CountDownLatch entered = new CountDownLatch(1);
    private final CountDownLatch release = new CountDownLatch(1);
    private volatile Thread thread;
    private volatile boolean interrupted;

    Gate(String point) {
      this(point, null);
    }

    Gate(String point, Throwable failure) {
      this.point = point;
      this.failure = failure;
    }

    @Override
    public void accept(String name) {
      if (!name.equals(point) || !armed.compareAndSet(true, false)) {
        return;
      }
      thread = Thread.currentThread();
      entered.countDown();
      try {
        if (!release.await(20, TimeUnit.SECONDS)) {
          throw new IllegalStateException("release deadline");
        }
      } catch (InterruptedException e) {
        interrupted = true;
        Thread.currentThread().interrupt();
      }
      interrupted |= Thread.currentThread().isInterrupted();
      if (failure instanceof RuntimeException) {
        throw (RuntimeException) failure;
      }
      if (failure instanceof Error) {
        throw (Error) failure;
      }
    }

    Thread awaitEntered() throws InterruptedException {
      assertTrue(entered.await(10, TimeUnit.SECONDS), "worker reached " + point);
      return thread;
    }

    void release() {
      release.countDown();
    }
  }

  private static SpannerStorageHooks hooks(Consumer<String> at) {
    return hooks(at, false);
  }

  private static SpannerStorageHooks hooks(Consumer<String> at, boolean failRetention) {
    return new SpannerStorageHooks() {
      @Override
      public void beforeCommit(AggregateId aggregateId) {
        at.accept("beforeCommit");
      }

      @Override
      public void afterCommit(AggregateId aggregateId) {
        at.accept("afterCommit");
      }

      @Override
      public void beforeReadEvents(AggregateId aggregateId) {
        at.accept("beforeReadEvents");
      }

      @Override
      public void beforeReadSnapshot(AggregateId aggregateId) {
        at.accept("beforeReadSnapshot");
      }

      @Override
      public void beforeRetentionDelete(AggregateId aggregateId, List<Long> seqNrs) {
        at.accept("beforeRetentionDelete");
        if (failRetention) {
          throw new IllegalStateException("injected retention failure");
        }
      }
    };
  }

  private static PayloadSerializer<String> serializer(Consumer<String> at, String kind) {
    PayloadSerializer<String> json = JsonPayloadSerializer.of(String.class);
    return new PayloadSerializer<String>() {
      @Override
      public byte[] serialize(String value) {
        at.accept(kind + ".serialize");
        return json.serialize(value);
      }

      @Override
      public String deserialize(byte[] bytes) {
        at.accept(kind + ".deserialize");
        return json.deserialize(bytes);
      }
    };
  }

  private static EventStoreConfig<String, String> config(Consumer<String> at) {
    return EventStoreConfig.<String, String>builder()
        .payloadSerializer(serializer(at, "payload"))
        .snapshotSerializer(serializer(at, "snapshot"))
        .retentionFailureListener(failure -> at.accept("listener"))
        .build();
  }

  private static <T> T get(CompletableFuture<T> future) throws Exception {
    return future.get(10, TimeUnit.SECONDS);
  }

  private static Throwable failureOf(CompletableFuture<?> future) {
    return assertThrows(ExecutionException.class, () -> get(future)).getCause();
  }

  private static void drain(BlockingExecution execution) throws Exception {
    execution.close();
    get(execution.drained().toCompletableFuture());
  }

  private static List<Long> seqNrs(List<EventEnvelope<String>> events) {
    return events.stream().map(EventEnvelope::seqNr).collect(Collectors.toList());
  }

  private static List<Long> journal(String prefix) {
    return EMULATOR.longs("SELECT seq_nr FROM " + prefix + "_journal ORDER BY seq_nr");
  }

  private static List<Long> history(String prefix) {
    return EMULATOR.longs("SELECT seq_nr FROM " + prefix + "_snapshot_history ORDER BY seq_nr");
  }

  private static SpannerException abort() {
    return SpannerExceptionFactory.newSpannerException(ErrorCode.ABORTED, "injected abort");
  }

  private static final class Point {
    final String name;
    final String at;
    final SpannerRetentionPolicy policy;
    final Consumer<AsyncEventStore<String, String>> setup;
    final Function<AsyncEventStore<String, String>, CompletableFuture<?>> operation;
    final List<Long> journal;

    Point(
        String name,
        String at,
        SpannerRetentionPolicy policy,
        Consumer<AsyncEventStore<String, String>> setup,
        Function<AsyncEventStore<String, String>, CompletableFuture<?>> operation,
        List<Long> journal) {
      this.name = name;
      this.at = at;
      this.policy = policy;
      this.setup = setup;
      this.operation = operation;
      this.journal = journal;
    }
  }

  private static Stream<Point> points() {
    Consumer<AsyncEventStore<String, String>> none = store -> {};
    Consumer<AsyncEventStore<String, String>> oneEvent =
        store -> store.persistEvent(event(1)).join();
    Consumer<AsyncEventStore<String, String>> oneSnapshot =
        store -> store.persistEventAndSnapshot(event(1), snapshot(1)).join();
    Function<AsyncEventStore<String, String>, CompletableFuture<?>> append =
        store -> store.persistEvent(event(1));
    Function<AsyncEventStore<String, String>, CompletableFuture<?>> appendSnapshot =
        store -> store.persistEventAndSnapshot(event(1), snapshot(1));
    Function<AsyncEventStore<String, String>, CompletableFuture<?>> secondSnapshot =
        store -> store.persistEventAndSnapshot(event(2), snapshot(2));
    Function<AsyncEventStore<String, String>, CompletableFuture<?>> readEvents =
        store -> store.getEventsByIdSinceSeqNr(AID, 0);
    Function<AsyncEventStore<String, String>, CompletableFuture<?>> readSnapshot =
        store -> store.getLatestSnapshotById(AID);
    SpannerRetentionPolicy none0 = SpannerRetentionPolicy.none();
    SpannerRetentionPolicy keepOne = SpannerRetentionPolicy.delete(1);
    return Stream.of(
        new Point("append payload.serialize", "payload.serialize", none0, none, append, list(1)),
        new Point("append beforeCommit", "beforeCommit", none0, none, append, list(1)),
        new Point("append afterCommit", "afterCommit", none0, none, append, list(1)),
        new Point(
            "snapshot append snapshot.serialize",
            "snapshot.serialize",
            none0,
            none,
            appendSnapshot,
            list(1)),
        new Point(
            "snapshot append beforeCommit", "beforeCommit", none0, none, appendSnapshot, list(1)),
        new Point(
            "retention beforeRetentionDelete",
            "beforeRetentionDelete",
            keepOne,
            oneSnapshot,
            secondSnapshot,
            list(1, 2)),
        new Point(
            "retention listener", "listener", keepOne, oneSnapshot, secondSnapshot, list(1, 2)),
        new Point(
            "events beforeReadEvents", "beforeReadEvents", none0, oneEvent, readEvents, list(1)),
        new Point(
            "events payload.deserialize",
            "payload.deserialize",
            none0,
            oneEvent,
            readEvents,
            list(1)),
        new Point(
            "snapshot beforeReadSnapshot",
            "beforeReadSnapshot",
            none0,
            oneSnapshot,
            readSnapshot,
            list(1)),
        new Point(
            "snapshot snapshot.deserialize",
            "snapshot.deserialize",
            none0,
            oneSnapshot,
            readSnapshot,
            list(1)));
  }

  private static List<Long> list(long... values) {
    List<Long> result = new ArrayList<>();
    for (long value : values) {
      result.add(value);
    }
    return result;
  }

  private static String prefixFor(SpannerRetentionPolicy policy) {
    return policy.keepCount().isPresent()
        ? deleteStore(policy.keepCount().getAsInt())
        : noneStore();
  }

  @TestFactory
  Stream<DynamicTest> everyOperationPartRunsOnTheWorkerAndDrainWaitsForItsExit() {
    return points()
        .map(point ->
            dynamicTest(
                point.name,
                () -> {
                  String prefix = prefixFor(point.policy);
                  Gate gate = new Gate(point.at);
                  BlockingExecution execution = BlockingExecution.singleWorker(4);
                  try {
                    SpannerStorage storage =
                        open(prefix, point.policy, hooks(gate, point.at.equals("listener")));
                    AsyncEventStore<String, String> store =
                        SpannerEventStore.createOffloaded(storage, config(gate), execution);
                    point.setup.accept(store);
                    CompletableFuture<?> result = point.operation.apply(store);

                    Thread worker = gate.awaitEntered();
                    assertNotSame(Thread.currentThread(), worker);
                    assertFalse(result.isDone(), "caller got the future before release");
                    execution.close();
                    assertFalse(
                        execution.drained().toCompletableFuture().isDone(), "drain waits for exit");
                    gate.release();

                    get(result);
                    get(execution.drained().toCompletableFuture());
                  } finally {
                    gate.release();
                    drain(execution);
                  }
                  assertEquals(point.journal, journal(prefix));
                }));
  }

  @Test
  void factoryChecksArgumentsWithoutInvokingAnyOperationCode() throws Exception {
    String prefix = noneStore();
    List<String> reached = Collections.synchronizedList(new ArrayList<>());
    BlockingExecution execution = BlockingExecution.singleWorker(1);
    try {
      SpannerStorage storage = open(prefix, SpannerRetentionPolicy.none(), hooks(reached::add));
      EventStoreConfig<String, String> config = config(reached::add);
      assertThrows(
          ConfigurationException.class,
          () -> SpannerEventStore.createOffloaded(null, config, execution));
      assertThrows(
          ConfigurationException.class,
          () -> SpannerEventStore.<String, String>createOffloaded(storage, null, execution));
      assertThrows(
          ConfigurationException.class,
          () -> SpannerEventStore.createOffloaded(storage, config, null));
      SpannerEventStore.createOffloaded(storage, config, execution);
      assertEquals(List.of(), reached);
    } finally {
      drain(execution);
    }
  }

  @Test
  void validationRunsOnTheWorkerSoInvalidInputFailsTheFutureInOrder() throws Exception {
    String prefix = noneStore();
    Gate gate = new Gate("beforeCommit");
    BlockingExecution execution = BlockingExecution.singleWorker(2);
    try {
      AsyncEventStore<String, String> store =
          SpannerEventStore.createOffloaded(
              open(prefix, SpannerRetentionPolicy.none(), hooks(gate)), config(gate), execution);
      CompletableFuture<Void> first = store.persistEvent(event(1));
      gate.awaitEntered();

      CompletableFuture<Void> mismatched = store.persistEventAndSnapshot(event(2), snapshot(1));
      assertFalse(mismatched.isDone(), "validation waits for the worker");
      gate.release();

      get(first);
      assertInstanceOf(ContractViolationException.class, failureOf(mismatched));
    } finally {
      gate.release();
      drain(execution);
    }
    assertEquals(list(1), journal(prefix));
  }

  @Test
  void aFullQueueRejectsWithoutRunningOrFallingBackToTheCaller() throws Exception {
    String prefix = noneStore();
    Gate gate = new Gate("beforeCommit");
    List<String> reached = Collections.synchronizedList(new ArrayList<>());
    Consumer<String> at =
        name -> {
          reached.add(name);
          gate.accept(name);
        };
    BlockingExecution execution = BlockingExecution.singleWorker(1);
    try {
      AsyncEventStore<String, String> store =
          SpannerEventStore.createOffloaded(
              open(prefix, SpannerRetentionPolicy.none(), hooks(at)), config(at), execution);
      CompletableFuture<Void> active = store.persistEvent(event(1));
      gate.awaitEntered();
      CompletableFuture<Void> queued = store.persistEvent(event(2));

      CompletableFuture<Void> rejected = store.persistEvent(event(3));
      assertTrue(rejected.isCompletedExceptionally(), "rejected before returning");
      assertInstanceOf(RejectedExecutionException.class, failureOf(rejected));
      gate.release();

      get(active);
      get(queued);
    } finally {
      gate.release();
      drain(execution);
    }
    assertEquals(list(1, 2), journal(prefix));
    assertEquals(2, reached.stream().filter("payload.serialize"::equals).count());
  }

  @Test
  void readsAndWritesFromOneCallerRunInAdmissionOrder() throws Exception {
    String prefix = noneStore();
    Gate gate = new Gate("beforeCommit");
    BlockingExecution execution = BlockingExecution.singleWorker(8);
    try {
      AsyncEventStore<String, String> store =
          SpannerEventStore.createOffloaded(
              open(prefix, SpannerRetentionPolicy.none(), hooks(gate)), config(gate), execution);
      CompletableFuture<Void> first = store.persistEvent(event(1));
      gate.awaitEntered();
      CompletableFuture<List<EventEnvelope<String>>> afterFirst =
          store.getEventsByIdSinceSeqNr(AID, 0);
      CompletableFuture<Void> second = store.persistEventAndSnapshot(event(2), snapshot(2));
      CompletableFuture<Optional<SnapshotReadResult<String>>> afterSecond =
          store.getLatestSnapshotById(AID);
      CompletableFuture<Void> third = store.persistEvent(event(3));
      CompletableFuture<List<EventEnvelope<String>>> afterThird =
          store.getEventsByIdSinceSeqNr(AID, 0);
      gate.release();

      get(first);
      get(second);
      get(third);
      assertEquals(list(1), seqNrs(get(afterFirst)));
      SnapshotReadResult<String> snapshot = get(afterSecond).get();
      assertEquals(2, snapshot.headSeqNr());
      assertEquals("snapshot-2", snapshot.snapshot().get().aggregate());
      assertEquals(list(1, 2, 3), seqNrs(get(afterThird)));
    } finally {
      gate.release();
      drain(execution);
    }
  }

  @Test
  void storesOnSeveralStoragesOfOneDatabaseShareTheOrderOfOneExecution() throws Exception {
    String prefix = noneStore();
    Gate gate = new Gate("beforeCommit");
    BlockingExecution execution = BlockingExecution.singleWorker(4);
    try {
      AsyncEventStore<String, String> writer =
          SpannerEventStore.createOffloaded(
              open(prefix, SpannerRetentionPolicy.none(), hooks(gate)), config(gate), execution);
      AsyncEventStore<String, String> other =
          SpannerEventStore.createOffloaded(
              open(prefix, SpannerRetentionPolicy.none()), config(name -> {}), execution);
      CompletableFuture<Void> first = writer.persistEvent(event(1));
      gate.awaitEntered();
      CompletableFuture<Void> second = other.persistEvent(event(2));
      CompletableFuture<List<EventEnvelope<String>>> read = writer.getEventsByIdSinceSeqNr(AID, 0);
      gate.release();

      get(first);
      get(second);
      assertEquals(list(1, 2), seqNrs(get(read)));
    } finally {
      gate.release();
      drain(execution);
    }
  }

  @Test
  void anotherExecutionOnTheSameDatabaseIsNotOrderedWithIt() throws Exception {
    String prefix = noneStore();
    Gate gate = new Gate("payload.serialize");
    BlockingExecution first = BlockingExecution.singleWorker(1);
    BlockingExecution second = BlockingExecution.singleWorker(1);
    try {
      SpannerStorage storage = open(prefix, SpannerRetentionPolicy.none());
      AsyncEventStore<String, String> early =
          SpannerEventStore.createOffloaded(storage, config(gate), first);
      AsyncEventStore<String, String> late =
          SpannerEventStore.createOffloaded(storage, config(name -> {}), second);
      CompletableFuture<Void> earlyWrite = early.persistEvent(event(1));
      gate.awaitEntered();

      get(late.persistEvent(event(1)));
      gate.release();

      assertInstanceOf(OptimisticLockException.class, failureOf(earlyWrite));
    } finally {
      gate.release();
      drain(first);
      drain(second);
    }
    assertEquals(list(1), journal(prefix));
  }

  @Test
  void closeDrainsAFullQueueAndRejectsLaterAdmission() throws Exception {
    String prefix = noneStore();
    Gate gate = new Gate("beforeCommit");
    BlockingExecution execution = BlockingExecution.singleWorker(2);
    try {
      AsyncEventStore<String, String> store =
          SpannerEventStore.createOffloaded(
              open(prefix, SpannerRetentionPolicy.none(), hooks(gate)), config(gate), execution);
      List<CompletableFuture<Void>> accepted = new ArrayList<>();
      accepted.add(store.persistEvent(event(1)));
      gate.awaitEntered();
      accepted.add(store.persistEvent(event(2)));
      accepted.add(store.persistEvent(event(3)));

      execution.close();
      execution.close();
      assertInstanceOf(RejectedExecutionException.class, failureOf(store.persistEvent(event(4))));
      assertFalse(execution.drained().toCompletableFuture().isDone());
      gate.release();

      get(execution.drained().toCompletableFuture());
      for (CompletableFuture<Void> write : accepted) {
        assertTrue(write.isDone() && !write.isCompletedExceptionally());
      }
    } finally {
      gate.release();
      drain(execution);
    }
    assertEquals(list(1, 2, 3), journal(prefix));
  }

  @Test
  void cancellingAQueuedWriteSkipsItWithoutRenumberingLaterWrites() throws Exception {
    String prefix = noneStore();
    Gate gate = new Gate("beforeCommit");
    BlockingExecution execution = BlockingExecution.singleWorker(2);
    try {
      AsyncEventStore<String, String> store =
          SpannerEventStore.createOffloaded(
              open(prefix, SpannerRetentionPolicy.none(), hooks(gate)), config(gate), execution);
      CompletableFuture<Void> first = store.persistEvent(event(1));
      gate.awaitEntered();
      CompletableFuture<Void> skipped = store.persistEvent(event(2));
      CompletableFuture<Void> after = store.persistEvent(event(3));

      assertTrue(skipped.cancel(true));
      gate.release();

      get(first);
      assertTrue(skipped.isCancelled());
      assertInstanceOf(ContractViolationException.class, failureOf(after));
    } finally {
      gate.release();
      drain(execution);
    }
    assertEquals(list(1), journal(prefix));
  }

  @TestFactory
  Stream<DynamicTest> cancellingAWriteInsideAnAbortRetryNeitherInterruptsNorStopsTheRetry() {
    return Stream.of(true, false)
        .map(mayInterrupt ->
            dynamicTest(
                "mayInterruptIfRunning=" + mayInterrupt,
                () -> {
                  String prefix = noneStore();
                  Gate gate = new Gate("beforeCommit", abort());
                  AtomicInteger attempts = new AtomicInteger();
                  Consumer<String> at =
                      name -> {
                        if (name.equals("beforeCommit")) {
                          attempts.incrementAndGet();
                        }
                        gate.accept(name);
                      };
                  BlockingExecution execution = BlockingExecution.singleWorker(1);
                  try {
                    AsyncEventStore<String, String> store =
                        SpannerEventStore.createOffloaded(
                            open(prefix, SpannerRetentionPolicy.none(), hooks(at)),
                            config(at),
                            execution);
                    CompletableFuture<Void> write = store.persistEvent(event(1));
                    gate.awaitEntered();

                    assertTrue(write.cancel(mayInterrupt));
                    execution.close();
                    assertFalse(execution.drained().toCompletableFuture().isDone());
                    gate.release();

                    get(execution.drained().toCompletableFuture());
                    assertTrue(write.isCancelled());
                    assertFalse(gate.interrupted, "worker was not interrupted");
                  } finally {
                    gate.release();
                    drain(execution);
                  }
                  assertEquals(2, attempts.get(), "the aborted attempt was retried");
                  assertEquals(list(1), journal(prefix));
                }));
  }

  @Test
  void aCallbackMayAdmitAnotherOperationWithinCapacity() throws Exception {
    String prefix = noneStore();
    BlockingExecution execution = BlockingExecution.singleWorker(1);
    try {
      AsyncEventStore<String, String> store =
          SpannerEventStore.createOffloaded(
              open(prefix, SpannerRetentionPolicy.none()), config(name -> {}), execution);
      CompletableFuture<List<EventEnvelope<String>>> chained =
          store
              .persistEvent(event(1))
              .thenCompose(ignored -> store.getEventsByIdSinceSeqNr(AID, 0));

      assertEquals(list(1), seqNrs(get(chained)));
    } finally {
      drain(execution);
    }
  }

  @Test
  void abortRetriesAndTheServerRetryDelayOccupyTheWorkerBeforeTheNextOperation() throws Exception {
    String prefix = noneStore();
    long delayNanos = TimeUnit.MILLISECONDS.toNanos(300);
    List<Thread> attemptThreads = Collections.synchronizedList(new ArrayList<>());
    AtomicInteger commits = new AtomicInteger();
    long[] abortedAt = new long[1];
    long[] readStartedAt = new long[1];
    SpannerStorageHooks hooks =
        new SpannerStorageHooks() {
          @Override
          public void beforeCommit(AggregateId aggregateId) {
            attemptThreads.add(Thread.currentThread());
            if (attemptThreads.size() == 1) {
              abortedAt[0] = System.nanoTime();
              throw SpannerRetryWaitTest.structured(SpannerRetryWaitTest.hint(0, (int) delayNanos));
            }
          }

          @Override
          public void afterCommit(AggregateId aggregateId) {
            commits.incrementAndGet();
          }

          @Override
          public void beforeReadEvents(AggregateId aggregateId) {
            readStartedAt[0] = System.nanoTime();
          }
        };
    BlockingExecution execution = BlockingExecution.singleWorker(2);
    try {
      AsyncEventStore<String, String> store =
          SpannerEventStore.createOffloaded(
              open(prefix, SpannerRetentionPolicy.none(), hooks), config(name -> {}), execution);
      CompletableFuture<Void> write = store.persistEvent(event(1));
      CompletableFuture<List<EventEnvelope<String>>> read = store.getEventsByIdSinceSeqNr(AID, 0);

      get(write);
      assertEquals(list(1), seqNrs(get(read)));
    } finally {
      drain(execution);
    }
    assertEquals(2, attemptThreads.size());
    assertNotSame(Thread.currentThread(), attemptThreads.get(0));
    assertTrue(attemptThreads.get(0) == attemptThreads.get(1), "every attempt on one worker");
    assertEquals(1, commits.get());
    assertTrue(
        readStartedAt[0] - abortedAt[0] >= delayNanos,
        "the next operation waited for the retry delay");
    assertEquals(list(1), journal(prefix));
  }

  @Test
  void abortsBeyondTheLimitFailTheFutureWithAStorageErrorAndLaterWorkRuns() throws Exception {
    String prefix = noneStore();
    AtomicInteger attempts = new AtomicInteger();
    SpannerStorageHooks hooks =
        new SpannerStorageHooks() {
          @Override
          public void beforeCommit(AggregateId aggregateId) {
            if (attempts.incrementAndGet() <= 3) {
              throw abort();
            }
          }
        };
    SpannerStorage storage =
        SpannerStorage.open(
            EMULATOR.client(),
            SpannerStorageConfig.builder().tablePrefix(prefix).transactionRetryLimit(2).build(),
            hooks);
    BlockingExecution execution = BlockingExecution.singleWorker(2);
    try {
      AsyncEventStore<String, String> store =
          SpannerEventStore.createOffloaded(storage, config(name -> {}), execution);
      CompletableFuture<Void> failing = store.persistEvent(event(1));
      CompletableFuture<Void> next = store.persistEvent(event(1));

      assertInstanceOf(StorageException.class, failureOf(failing));
      get(next);
    } finally {
      drain(execution);
    }
    assertEquals(4, attempts.get());
    assertEquals(list(1), journal(prefix));
  }

  @Test
  void aLostCommitResponseFailsTheFutureAsUnknownAndTheQueuedResendConflicts() throws Exception {
    String prefix = noneStore();
    AtomicInteger commits = new AtomicInteger();
    SpannerStorageHooks hooks =
        new SpannerStorageHooks() {
          @Override
          public void afterCommit(AggregateId aggregateId) {
            if (commits.incrementAndGet() == 1) {
              throw SpannerExceptionFactory.newSpannerException(
                  ErrorCode.UNAVAILABLE, "injected response loss");
            }
          }
        };
    BlockingExecution execution = BlockingExecution.singleWorker(2);
    try {
      AsyncEventStore<String, String> store =
          SpannerEventStore.createOffloaded(
              open(prefix, SpannerRetentionPolicy.none(), hooks), config(name -> {}), execution);
      CompletableFuture<Void> lost = store.persistEvent(event(1));
      CompletableFuture<Void> resend = store.persistEvent(event(1));

      Throwable unknown = failureOf(lost);
      assertInstanceOf(StorageException.class, unknown);
      assertTrue(unknown.getMessage().contains("unknown"), unknown.getMessage());
      assertInstanceOf(OptimisticLockException.class, failureOf(resend));
    } finally {
      drain(execution);
    }
    assertEquals(1, commits.get());
    assertEquals(list(1), journal(prefix));
  }

  @Test
  void anSdkDeadlineFailsTheFutureWithAStorageErrorAndLaterWorkRuns() throws Exception {
    String prefix = noneStore();
    Duration expired = Duration.ofNanos(1);
    Spanner service =
        EMULATOR.newService(options ->
            options
                .getSpannerStubSettingsBuilder()
                .executeSqlSettings()
                .setRetrySettings(
                    RetrySettings.newBuilder()
                        .setInitialRpcTimeoutDuration(expired)
                        .setMaxRpcTimeoutDuration(expired)
                        .setTotalTimeoutDuration(expired)
                        .setMaxAttempts(1)
                        .build()));
    BlockingExecution execution = BlockingExecution.singleWorker(2);
    try {
      SpannerStorage storage =
          SpannerStorage.open(
              SpannerEmulator.client(service), settings(prefix, SpannerRetentionPolicy.none()));
      AsyncEventStore<String, String> store =
          SpannerEventStore.createOffloaded(storage, config(name -> {}), execution);
      CompletableFuture<Void> timedOut = store.persistEvent(event(1));
      CompletableFuture<List<EventEnvelope<String>>> read = store.getEventsByIdSinceSeqNr(AID, 0);

      Throwable failure = failureOf(timedOut);
      assertInstanceOf(StorageException.class, failure);
      SpannerException cause = assertInstanceOf(SpannerException.class, failure.getCause());
      assertEquals(ErrorCode.DEADLINE_EXCEEDED, cause.getErrorCode());
      assertEquals(List.of(), get(read));
    } finally {
      drain(execution);
      service.close();
    }
    assertEquals(List.of(), journal(prefix));
  }

  @Test
  void aCancelledGrpcContextOfTheCallerDoesNotReachTheWorker() throws Exception {
    String prefix = noneStore();
    SpannerStorage storage = open(prefix, SpannerRetentionPolicy.none());
    BlockingExecution execution = BlockingExecution.singleWorker(2);
    AtomicReference<CompletableFuture<Void>> offloaded = new AtomicReference<>();
    AtomicReference<CompletableFuture<Void>> onCaller = new AtomicReference<>();
    Context.CancellableContext cancelled = Context.current().withCancellation();
    cancelled.cancel(null);
    try {
      AsyncEventStore<String, String> store =
          SpannerEventStore.createOffloaded(storage, config(name -> {}), execution);
      AsyncEventStore<String, String> callerThread =
          SpannerEventStore.createAsync(storage, config(name -> {}));
      cancelled.run(() -> {
        onCaller.set(callerThread.persistEvent(event(1)));
        offloaded.set(store.persistEvent(event(1)));
      });

      assertInstanceOf(StorageException.class, failureOf(onCaller.get()));
      get(offloaded.get());
    } finally {
      drain(execution);
    }
    assertEquals(list(1), journal(prefix));
  }

  @Test
  void theStoreLeavesTheClientOpenAfterDrain() throws Exception {
    String prefix = noneStore();
    Spanner service = EMULATOR.newService(options -> {});
    try {
      SpannerStorage storage =
          SpannerStorage.open(
              SpannerEmulator.client(service), settings(prefix, SpannerRetentionPolicy.none()));
      BlockingExecution execution = BlockingExecution.singleWorker(2);
      try {
        AsyncEventStore<String, String> store =
            SpannerEventStore.createOffloaded(storage, config(name -> {}), execution);
        get(store.persistEvent(event(1)));
      } finally {
        drain(execution);
      }

      assertFalse(service.isClosed());
      assertEquals(
          list(1),
          seqNrs(
              SpannerEventStore.create(storage, config(name -> {}))
                  .getEventsByIdSinceSeqNr(AID, 0)));
    } finally {
      service.close();
    }
  }

  @Test
  void closingTheClientBeforeDrainFailsAcceptedWorkAndDrainStillCompletes() throws Exception {
    String prefix = noneStore();
    Spanner service = EMULATOR.newService(options -> {});
    Gate gate = new Gate("payload.serialize");
    BlockingExecution execution = BlockingExecution.singleWorker(2);
    try {
      SpannerStorage storage =
          SpannerStorage.open(
              SpannerEmulator.client(service), settings(prefix, SpannerRetentionPolicy.none()));
      AsyncEventStore<String, String> store =
          SpannerEventStore.createOffloaded(storage, config(gate), execution);
      CompletableFuture<Void> accepted = store.persistEvent(event(1));
      CompletableFuture<List<EventEnvelope<String>>> queued = store.getEventsByIdSinceSeqNr(AID, 0);
      gate.awaitEntered();

      service.close();
      gate.release();

      assertInstanceOf(IllegalStateException.class, failureOf(accepted));
      assertInstanceOf(IllegalStateException.class, failureOf(queued));
      execution.close();
      get(execution.drained().toCompletableFuture());
    } finally {
      gate.release();
      drain(execution);
      if (!service.isClosed()) {
        service.close();
      }
    }
    assertEquals(List.of(), journal(prefix));
  }

  private static final class Failure {
    final String point;
    final Throwable thrown;
    final Class<? extends Throwable> reported;

    Failure(String point, Throwable thrown, Class<? extends Throwable> reported) {
      this.point = point;
      this.thrown = thrown;
      this.reported = reported;
    }
  }

  @TestFactory
  Stream<DynamicTest> failuresBeforeCommitKeepTheirClassificationAndLaterAcceptedWorkStillRuns() {
    return Stream.of(
            new Failure(
                "payload.serialize",
                new IllegalStateException("injected"),
                SerializationException.class),
            new Failure("payload.serialize", new AssertionError("injected"), AssertionError.class),
            new Failure(
                "beforeCommit", new IllegalStateException("injected"), IllegalStateException.class),
            new Failure("beforeCommit", new AssertionError("injected"), AssertionError.class))
        .map(failure ->
            dynamicTest(
                failure.point + " " + failure.thrown.getClass().getSimpleName(),
                () -> {
                  String prefix = noneStore();
                  Gate gate = new Gate(failure.point, failure.thrown);
                  BlockingExecution execution = BlockingExecution.singleWorker(2);
                  try {
                    AsyncEventStore<String, String> store =
                        SpannerEventStore.createOffloaded(
                            open(prefix, SpannerRetentionPolicy.none(), hooks(gate)),
                            config(gate),
                            execution);
                    CompletableFuture<Void> failing = store.persistEvent(event(1));
                    gate.awaitEntered();
                    CompletableFuture<Void> retried = store.persistEvent(event(1));
                    CompletableFuture<Void> next = store.persistEvent(event(2));
                    gate.release();

                    assertInstanceOf(failure.reported, failureOf(failing));
                    get(retried);
                    get(next);
                  } finally {
                    gate.release();
                    drain(execution);
                  }
                  assertEquals(list(1, 2), journal(prefix));
                }));
  }

  @Test
  void anErrorAfterCommitReachesTheCallerAndKeepsTheCommit() throws Exception {
    String prefix = noneStore();
    Gate gate = new Gate("afterCommit", new AssertionError("injected"));
    BlockingExecution execution = BlockingExecution.singleWorker(2);
    try {
      AsyncEventStore<String, String> store =
          SpannerEventStore.createOffloaded(
              open(prefix, SpannerRetentionPolicy.none(), hooks(gate)), config(gate), execution);
      CompletableFuture<Void> failing = store.persistEvent(event(1));
      gate.awaitEntered();
      CompletableFuture<Void> resend = store.persistEvent(event(1));
      CompletableFuture<Void> next = store.persistEvent(event(2));
      gate.release();

      assertInstanceOf(AssertionError.class, failureOf(failing));
      assertInstanceOf(OptimisticLockException.class, failureOf(resend));
      get(next);
    } finally {
      gate.release();
      drain(execution);
    }
    assertEquals(list(1, 2), journal(prefix));
  }

  @TestFactory
  Stream<DynamicTest> aReadFailureKeepsItsClassificationAndLaterAcceptedWorkStillRuns() {
    return Stream.of(
            new Failure(
                "payload.deserialize",
                new IllegalStateException("injected"),
                SerializationException.class),
            new Failure(
                "payload.deserialize", new AssertionError("injected"), AssertionError.class),
            new Failure("beforeReadEvents", new AssertionError("injected"), AssertionError.class),
            new Failure("beforeReadSnapshot", new AssertionError("injected"), AssertionError.class))
        .map(failure ->
            dynamicTest(
                failure.point + " " + failure.thrown.getClass().getSimpleName(),
                () -> {
                  String prefix = noneStore();
                  Function<AsyncEventStore<String, String>, CompletableFuture<?>> read =
                      failure.point.equals("beforeReadSnapshot")
                          ? store -> store.getLatestSnapshotById(AID)
                          : store -> store.getEventsByIdSinceSeqNr(AID, 0);
                  Gate gate = new Gate(failure.point, failure.thrown);
                  BlockingExecution execution = BlockingExecution.singleWorker(2);
                  try {
                    AsyncEventStore<String, String> store =
                        SpannerEventStore.createOffloaded(
                            open(prefix, SpannerRetentionPolicy.none(), hooks(gate)),
                            config(gate),
                            execution);
                    get(store.persistEventAndSnapshot(event(1), snapshot(1)));
                    CompletableFuture<?> failing = read.apply(store);
                    gate.awaitEntered();
                    CompletableFuture<?> reread = read.apply(store);
                    CompletableFuture<Void> next = store.persistEvent(event(2));
                    gate.release();

                    assertInstanceOf(failure.reported, failureOf(failing));
                    get(reread);
                    get(next);
                  } finally {
                    gate.release();
                    drain(execution);
                  }
                  assertEquals(list(1, 2), journal(prefix));
                }));
  }

  @Test
  void anErrorDuringRetentionReachesTheCallerAndKeepsTheCommittedAppend() throws Exception {
    String prefix = deleteStore(1);
    Gate gate = new Gate("beforeRetentionDelete", new AssertionError("injected"));
    BlockingExecution execution = BlockingExecution.singleWorker(2);
    try {
      AsyncEventStore<String, String> store =
          SpannerEventStore.createOffloaded(
              open(prefix, SpannerRetentionPolicy.delete(1), hooks(gate)), config(gate), execution);
      get(store.persistEventAndSnapshot(event(1), snapshot(1)));
      CompletableFuture<Void> failing = store.persistEventAndSnapshot(event(2), snapshot(2));
      gate.awaitEntered();
      CompletableFuture<Void> next = store.persistEvent(event(3));
      gate.release();

      assertInstanceOf(AssertionError.class, failureOf(failing));
      get(next);
    } finally {
      gate.release();
      drain(execution);
    }
    assertEquals(list(1, 2, 3), journal(prefix));
    assertEquals(list(1, 2), history(prefix));
  }

  @TestFactory
  Stream<DynamicTest> aFailingRetentionListenerKeepsTheCommittedAppend() {
    return Stream.<Throwable>of(new IllegalStateException("injected"), new AssertionError("x"))
        .map(thrown ->
            dynamicTest(
                thrown.getClass().getSimpleName(),
                () -> {
                  String prefix = deleteStore(1);
                  Gate gate = new Gate("listener", thrown);
                  BlockingExecution execution = BlockingExecution.singleWorker(2);
                  try {
                    AsyncEventStore<String, String> store =
                        SpannerEventStore.createOffloaded(
                            open(prefix, SpannerRetentionPolicy.delete(1), hooks(name -> {}, true)),
                            config(gate),
                            execution);
                    get(store.persistEventAndSnapshot(event(1), snapshot(1)));

                    CompletableFuture<Void> committed =
                        store.persistEventAndSnapshot(event(2), snapshot(2));
                    assertNotSame(Thread.currentThread(), gate.awaitEntered());
                    gate.release();

                    get(committed);
                  } finally {
                    gate.release();
                    drain(execution);
                  }
                  assertEquals(list(1, 2), journal(prefix));
                  assertEquals(list(1, 2), history(prefix));
                }));
  }

  @Test
  void allFourOperationsAgainstTheEmulatorAndCloseAfterDrain() throws Exception {
    String prefix = deleteStore(1);
    Spanner service = EMULATOR.newService(options -> {});
    BlockingExecution execution = BlockingExecution.singleWorker(16);
    SpannerStorage storage =
        SpannerStorage.open(
            SpannerEmulator.client(service), settings(prefix, SpannerRetentionPolicy.delete(1)));
    AsyncEventStore<String, String> store =
        SpannerEventStore.createOffloaded(storage, config(name -> {}), execution);
    List<CompletableFuture<?>> admitted = new ArrayList<>();
    admitted.add(store.persistEvent(event(1)));
    admitted.add(store.persistEventAndSnapshot(event(2), snapshot(2)));
    CompletableFuture<Void> duplicate = store.persistEvent(event(2));
    admitted.add(store.persistEventAndSnapshot(event(3), snapshot(3)));
    admitted.add(store.persistEvent(event(4)));
    CompletableFuture<Optional<SnapshotReadResult<String>>> latest =
        store.getLatestSnapshotById(AID);
    CompletableFuture<List<EventEnvelope<String>>> since = store.getEventsByIdSinceSeqNr(AID, 2);

    execution.close();
    get(execution.drained().toCompletableFuture());
    service.close();

    for (CompletableFuture<?> write : admitted) {
      assertTrue(write.isDone() && !write.isCompletedExceptionally());
    }
    assertInstanceOf(OptimisticLockException.class, failureOf(duplicate));
    assertEquals(4, get(latest).get().headSeqNr());
    assertEquals(3, get(latest).get().snapshot().get().seqNr());
    assertEquals(list(2, 3, 4), seqNrs(get(since)));
    assertEquals(list(1, 2, 3, 4), journal(prefix));
    assertEquals(list(3), history(prefix));
  }
}
