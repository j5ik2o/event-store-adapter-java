package com.github.j5ik2o.event.store.adapter.java.bigtable;

import static com.github.j5ik2o.event.store.adapter.java.bigtable.BigtableTestFixtures.AID;
import static com.github.j5ik2o.event.store.adapter.java.bigtable.BigtableTestFixtures.EMULATOR;
import static com.github.j5ik2o.event.store.adapter.java.bigtable.BigtableTestFixtures.deleteStore;
import static com.github.j5ik2o.event.store.adapter.java.bigtable.BigtableTestFixtures.event;
import static com.github.j5ik2o.event.store.adapter.java.bigtable.BigtableTestFixtures.headSeqNr;
import static com.github.j5ik2o.event.store.adapter.java.bigtable.BigtableTestFixtures.journalKey;
import static com.github.j5ik2o.event.store.adapter.java.bigtable.BigtableTestFixtures.noneStore;
import static com.github.j5ik2o.event.store.adapter.java.bigtable.BigtableTestFixtures.open;
import static com.github.j5ik2o.event.store.adapter.java.bigtable.BigtableTestFixtures.prefix;
import static com.github.j5ik2o.event.store.adapter.java.bigtable.BigtableTestFixtures.settings;
import static com.github.j5ik2o.event.store.adapter.java.bigtable.BigtableTestFixtures.snapshot;
import static com.github.j5ik2o.event.store.adapter.java.bigtable.BigtableTestFixtures.store;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import com.github.j5ik2o.event.store.adapter.java.core.AggregateId;
import com.github.j5ik2o.event.store.adapter.java.core.AsyncEventStore;
import com.github.j5ik2o.event.store.adapter.java.core.BlockingExecution;
import com.github.j5ik2o.event.store.adapter.java.core.ConcurrentAdmission;
import com.github.j5ik2o.event.store.adapter.java.core.ConfigurationException;
import com.github.j5ik2o.event.store.adapter.java.core.ContractViolationException;
import com.github.j5ik2o.event.store.adapter.java.core.EventEnvelope;
import com.github.j5ik2o.event.store.adapter.java.core.EventStore;
import com.github.j5ik2o.event.store.adapter.java.core.EventStoreConfig;
import com.github.j5ik2o.event.store.adapter.java.core.JsonPayloadSerializer;
import com.github.j5ik2o.event.store.adapter.java.core.OptimisticLockException;
import com.github.j5ik2o.event.store.adapter.java.core.PayloadSerializer;
import com.github.j5ik2o.event.store.adapter.java.core.RetentionPolicy;
import com.github.j5ik2o.event.store.adapter.java.core.SerializationException;
import com.github.j5ik2o.event.store.adapter.java.core.SnapshotReadResult;
import com.github.j5ik2o.event.store.adapter.java.core.StorageException;
import com.google.api.gax.retrying.RetrySettings;
import com.google.api.gax.rpc.DeadlineExceededException;
import com.google.bigtable.v2.CheckAndMutateRowRequest;
import com.google.cloud.bigtable.admin.v2.BigtableTableAdminClient;
import com.google.cloud.bigtable.data.v2.BigtableDataClient;
import com.google.cloud.bigtable.data.v2.BigtableDataSettings;
import com.google.cloud.bigtable.data.v2.stub.metrics.NoopMetricsProvider;
import java.io.IOException;
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
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.Timeout;
import org.slf4j.LoggerFactory;

/**
 * {@code createOffloaded} against the real emulator through the real Bigtable SDK: every part of an
 * operation runs on the worker of the execution, in accepted order, and the Bigtable commit point,
 * write-out and retention rules stay as they are for the synchronous store.
 */
@Timeout(120)
class BigtableOffloadedEventStoreTest {
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
        if (!release.await(10, TimeUnit.SECONDS)) {
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
      assertTrue(entered.await(5, TimeUnit.SECONDS), "worker reached " + point);
      return thread;
    }

    void release() {
      release.countDown();
    }
  }

  private static BigtableStorageHooks hooks(Consumer<String> at, boolean failRetention) {
    return new BigtableStorageHooks() {
      @Override
      public void journalWritePrevious(AggregateId aggregateId, long seqNr) {
        at.accept("journalWritePrevious");
      }

      @Override
      public void beforeCommit(AggregateId aggregateId, long seqNr) {
        at.accept("beforeCommit");
      }

      @Override
      public void afterCommit(AggregateId aggregateId, long seqNr) {
        at.accept("afterCommit");
      }

      @Override
      public void journalWriteTail(AggregateId aggregateId, long seqNr) {
        at.accept("journalWriteTail");
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
      public List<Long> readHistory(AggregateId aggregateId, List<Long> actualHistory) {
        at.accept("readHistory");
        return actualHistory;
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

  private static BigtableStorageHooks hooks(Consumer<String> at) {
    return hooks(at, false);
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

  private static Consumer<String> nothing() {
    return name -> {};
  }

  private static <T> T get(CompletableFuture<T> future) throws Exception {
    return future.get(5, TimeUnit.SECONDS);
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

  /** The events as an independent synchronous store on the shared clients reads them. */
  private static List<Long> stored(BigtableEmulator.Tables t) {
    return stored(t, RetentionPolicy.none());
  }

  private static List<Long> stored(BigtableEmulator.Tables t, RetentionPolicy policy) {
    return seqNrs(store(open(t, policy)).getEventsByIdSinceSeqNr(AID, 0));
  }

  private static String historyState(BigtableEmulator.Tables t, long seqNr) {
    return EMULATOR.text(t.journal, journalKey("Order-1", seqNr), "history", "state");
  }

  private static boolean hasHistoryEnvelope(BigtableEmulator.Tables t, long seqNr) {
    return EMULATOR.bytes(t.journal, journalKey("Order-1", seqNr), "history", "envelope") != null;
  }

  /** Own clients behind a recording relay, so a test may close them or alter the transport. */
  private static final class Clients implements AutoCloseable {
    final BigtableRecordingProxy proxy;
    final BigtableDataClient data;
    final BigtableTableAdminClient admin;

    Clients() throws IOException {
      this(null);
    }

    /** A non-null timeout bounds each CheckAndMutateRow attempt, which the SDK never retries. */
    Clients(Duration checkAndMutateTimeout) throws IOException {
      proxy = new BigtableRecordingProxy(EMULATOR.host(), EMULATOR.port());
      BigtableDataSettings.Builder builder =
          BigtableDataSettings.newBuilderForEmulator("127.0.0.1", proxy.port())
              .setProjectId(BigtableEmulator.PROJECT)
              .setInstanceId(BigtableEmulator.INSTANCE)
              .setMetricsProvider(NoopMetricsProvider.INSTANCE);
      if (checkAndMutateTimeout != null) {
        builder
            .stubSettings()
            .checkAndMutateRowSettings()
            .setRetrySettings(
                RetrySettings.newBuilder()
                    .setInitialRpcTimeoutDuration(checkAndMutateTimeout)
                    .setMaxRpcTimeoutDuration(checkAndMutateTimeout)
                    .setTotalTimeoutDuration(checkAndMutateTimeout)
                    .setMaxAttempts(1)
                    .build());
      }
      data = BigtableDataClient.create(builder.build());
      admin = BigtableEmulator.newAdminClient("127.0.0.1", proxy.port(), null);
    }

    BigtableStorage open(BigtableEmulator.Tables t, RetentionPolicy policy, Consumer<String> at) {
      return BigtableStorage.open(data, admin, settings(t, policy), hooks(at));
    }

    int calls() {
      return proxy.calls().size();
    }

    @Override
    public void close() {
      data.close();
      admin.close();
      proxy.close();
    }
  }

  private static final class Point {
    final String name;
    final RetentionPolicy policy;
    final Consumer<EventStore<String, String>> setup;
    final Function<AsyncEventStore<String, String>, CompletableFuture<?>> operation;
    final List<Long> events;

    Point(
        String name,
        RetentionPolicy policy,
        Consumer<EventStore<String, String>> setup,
        Function<AsyncEventStore<String, String>, CompletableFuture<?>> operation,
        List<Long> events) {
      this.name = name;
      this.policy = policy;
      this.setup = setup;
      this.operation = operation;
      this.events = events;
    }
  }

  private static Stream<Point> points() {
    Consumer<EventStore<String, String>> none = store -> {};
    Consumer<EventStore<String, String>> oneEvent = store -> store.persistEvent(event(1));
    Consumer<EventStore<String, String>> oneSnapshot =
        store -> store.persistEventAndSnapshot(event(1), snapshot(1));
    Function<AsyncEventStore<String, String>, CompletableFuture<?>> append =
        store -> store.persistEvent(event(1));
    Function<AsyncEventStore<String, String>, CompletableFuture<?>> appendSnapshot =
        store -> store.persistEventAndSnapshot(event(1), snapshot(1));
    Function<AsyncEventStore<String, String>, CompletableFuture<?>> second =
        store -> store.persistEvent(event(2));
    Function<AsyncEventStore<String, String>, CompletableFuture<?>> secondSnapshot =
        store -> store.persistEventAndSnapshot(event(2), snapshot(2));
    Function<AsyncEventStore<String, String>, CompletableFuture<?>> readEvents =
        store -> store.getEventsByIdSinceSeqNr(AID, 0);
    Function<AsyncEventStore<String, String>, CompletableFuture<?>> readSnapshot =
        store -> store.getLatestSnapshotById(AID);
    RetentionPolicy noRetention = RetentionPolicy.none();
    RetentionPolicy keepOne = RetentionPolicy.delete(1);
    return Stream.of(
        new Point("payload.serialize", noRetention, none, append, List.of(1L)),
        new Point("snapshot.serialize", noRetention, none, appendSnapshot, List.of(1L)),
        new Point("journalWritePrevious", noRetention, oneEvent, second, List.of(1L, 2L)),
        new Point("beforeCommit", noRetention, none, appendSnapshot, List.of(1L)),
        new Point("afterCommit", noRetention, none, append, List.of(1L)),
        new Point("journalWriteTail", noRetention, none, append, List.of(1L)),
        new Point("readHistory", keepOne, oneSnapshot, secondSnapshot, List.of(1L, 2L)),
        new Point("beforeRetentionDelete", keepOne, oneSnapshot, secondSnapshot, List.of(1L, 2L)),
        new Point("listener", keepOne, oneSnapshot, secondSnapshot, List.of(1L, 2L)),
        new Point("beforeReadEvents", noRetention, oneEvent, readEvents, List.of(1L)),
        new Point("payload.deserialize", noRetention, oneEvent, readEvents, List.of(1L)),
        new Point("beforeReadSnapshot", noRetention, oneSnapshot, readSnapshot, List.of(1L)),
        new Point("snapshot.deserialize", noRetention, oneSnapshot, readSnapshot, List.of(1L)));
  }

  @TestFactory
  Stream<DynamicTest> everyOperationPartRunsOnTheWorkerAndDrainWaitsForItsExit() {
    return points()
        .map(point ->
            dynamicTest(
                point.name,
                () -> {
                  BigtableEmulator.Tables t =
                      point.policy.keepCount().isPresent() ? deleteStore(1) : noneStore();
                  point.setup.accept(store(open(t, point.policy)));
                  Gate gate = new Gate(point.name);
                  BlockingExecution execution = BlockingExecution.singleWorker(4);
                  try {
                    BigtableStorage storage =
                        open(t, point.policy, hooks(gate, point.name.equals("listener")));
                    AsyncEventStore<String, String> store =
                        BigtableEventStore.createOffloaded(storage, config(gate), execution);
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
                  assertEquals(point.events, stored(t, point.policy));
                }));
  }

  @Test
  void factoryChecksArgumentsWithoutAnyRequestOrOperationCode() throws Exception {
    BigtableEmulator.Tables t = noneStore();
    List<String> reached = Collections.synchronizedList(new ArrayList<>());
    BlockingExecution execution = BlockingExecution.singleWorker(1);
    try (Clients clients = new Clients()) {
      BigtableStorage storage = clients.open(t, RetentionPolicy.none(), reached::add);
      EventStoreConfig<String, String> config = config(reached::add);
      int callsBefore = clients.calls();

      assertThrows(
          ConfigurationException.class,
          () -> BigtableEventStore.createOffloaded(null, config, execution));
      assertThrows(
          ConfigurationException.class,
          () -> BigtableEventStore.<String, String>createOffloaded(storage, null, execution));
      assertThrows(
          ConfigurationException.class,
          () -> BigtableEventStore.createOffloaded(storage, config, null));
      BigtableEventStore.createOffloaded(storage, config, execution);

      assertEquals(callsBefore, clients.calls(), "no request was sent");
      assertEquals(List.of(), reached);
    } finally {
      drain(execution);
    }
  }

  @Test
  void validationRunsOnTheWorkerSoInvalidInputFailsTheFutureInOrder() throws Exception {
    BigtableEmulator.Tables t = noneStore();
    Gate gate = new Gate("beforeCommit");
    BlockingExecution execution = BlockingExecution.singleWorker(2);
    try {
      AsyncEventStore<String, String> store =
          BigtableEventStore.createOffloaded(
              open(t, RetentionPolicy.none(), hooks(gate)), config(gate), execution);
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
    assertEquals(List.of(1L), stored(t));
  }

  @Test
  void aFullQueueRejectsWithoutRunningOrFallingBackToTheCaller() throws Exception {
    BigtableEmulator.Tables t = noneStore();
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
          BigtableEventStore.createOffloaded(
              open(t, RetentionPolicy.none(), hooks(at)), config(at), execution);
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
    assertEquals(List.of(1L, 2L), stored(t));
    assertEquals(2, reached.stream().filter("payload.serialize"::equals).count());
  }

  @Test
  void readsAndWritesFromOneCallerRunInAdmissionOrder() throws Exception {
    BigtableEmulator.Tables t = noneStore();
    Gate gate = new Gate("beforeCommit");
    BlockingExecution execution = BlockingExecution.singleWorker(8);
    try {
      AsyncEventStore<String, String> store =
          BigtableEventStore.createOffloaded(
              open(t, RetentionPolicy.none(), hooks(gate)), config(gate), execution);
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
      assertEquals(List.of(1L), seqNrs(get(afterFirst)));
      SnapshotReadResult<String> snapshot = get(afterSecond).get();
      assertEquals(2, snapshot.headSeqNr());
      assertEquals("snapshot-2", snapshot.snapshot().get().aggregate());
      assertEquals(List.of(1L, 2L, 3L), seqNrs(get(afterThird)));
    } finally {
      gate.release();
      drain(execution);
    }
  }

  @Test
  void storesOnTwoStoragesSharingOneExecutionShareOneOrder() throws Exception {
    BigtableEmulator.Tables t = noneStore();
    Gate gate = new Gate("beforeCommit");
    BlockingExecution execution = BlockingExecution.singleWorker(4);
    try {
      AsyncEventStore<String, String> writer =
          BigtableEventStore.createOffloaded(
              open(t, RetentionPolicy.none(), hooks(gate)), config(gate), execution);
      AsyncEventStore<String, String> other =
          BigtableEventStore.createOffloaded(
              open(t, RetentionPolicy.none()), config(nothing()), execution);
      CompletableFuture<Void> first = writer.persistEvent(event(1));
      gate.awaitEntered();
      CompletableFuture<Void> second = other.persistEvent(event(2));
      CompletableFuture<List<EventEnvelope<String>>> read = writer.getEventsByIdSinceSeqNr(AID, 0);
      gate.release();

      get(first);
      get(second);
      assertEquals(List.of(1L, 2L), seqNrs(get(read)));
    } finally {
      gate.release();
      drain(execution);
    }
  }

  @Test
  void aWriterOnAnotherExecutionIsNotOrderedAndTheHeadUpdateDecides() throws Exception {
    BigtableEmulator.Tables t = noneStore();
    Gate gate = new Gate("beforeCommit");
    BlockingExecution first = BlockingExecution.singleWorker(1);
    BlockingExecution second = BlockingExecution.singleWorker(1);
    try {
      AsyncEventStore<String, String> early =
          BigtableEventStore.createOffloaded(
              open(t, RetentionPolicy.none(), hooks(gate)), config(gate), first);
      AsyncEventStore<String, String> late =
          BigtableEventStore.createOffloaded(
              open(t, RetentionPolicy.none()), config(nothing()), second);
      get(late.persistEvent(event(1)));
      CompletableFuture<Void> earlyWrite = early.persistEvent(event(2));
      gate.awaitEntered();

      get(late.persistEvent(event(2)));
      gate.release();

      assertInstanceOf(OptimisticLockException.class, failureOf(earlyWrite));
    } finally {
      gate.release();
      drain(first);
      drain(second);
    }
    assertEquals(List.of(1L, 2L), stored(t));
  }

  @Test
  void aReadKeepsTheHeadItFetchedWhileAnotherExecutionAppends() throws Exception {
    BigtableEmulator.Tables t = noneStore();
    EventStore<String, String> setup = store(open(t, RetentionPolicy.none()));
    setup.persistEvent(event(1));
    setup.persistEvent(event(2));
    Gate gate = new Gate("beforeReadEvents");
    BlockingExecution reader = BlockingExecution.singleWorker(1);
    BlockingExecution writer = BlockingExecution.singleWorker(1);
    try {
      AsyncEventStore<String, String> reading =
          BigtableEventStore.createOffloaded(
              open(t, RetentionPolicy.none(), hooks(gate)), config(gate), reader);
      AsyncEventStore<String, String> writing =
          BigtableEventStore.createOffloaded(
              open(t, RetentionPolicy.none()), config(nothing()), writer);
      CompletableFuture<List<EventEnvelope<String>>> read = reading.getEventsByIdSinceSeqNr(AID, 0);
      gate.awaitEntered();

      get(writing.persistEvent(event(3)));
      gate.release();

      assertEquals(List.of(1L, 2L), seqNrs(get(read)));
    } finally {
      gate.release();
      drain(reader);
      drain(writer);
    }
    assertEquals(List.of(1L, 2L, 3L), stored(t));
  }

  @Test
  void closeDrainsAFullQueueAndRejectsLaterAdmission() throws Exception {
    BigtableEmulator.Tables t = noneStore();
    Gate gate = new Gate("beforeCommit");
    BlockingExecution execution = BlockingExecution.singleWorker(2);
    try {
      AsyncEventStore<String, String> store =
          BigtableEventStore.createOffloaded(
              open(t, RetentionPolicy.none(), hooks(gate)), config(gate), execution);
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
    assertEquals(List.of(1L, 2L, 3L), stored(t));
  }

  @Test
  void cancellingAQueuedWriteSkipsItWithoutRenumberingLaterWrites() throws Exception {
    BigtableEmulator.Tables t = noneStore();
    Gate gate = new Gate("beforeCommit");
    BlockingExecution execution = BlockingExecution.singleWorker(2);
    try {
      AsyncEventStore<String, String> store =
          BigtableEventStore.createOffloaded(
              open(t, RetentionPolicy.none(), hooks(gate)), config(gate), execution);
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
    assertEquals(List.of(1L), stored(t));
  }

  @TestFactory
  Stream<DynamicTest> cancellingAnInFlightWriteNeitherInterruptsNorStopsItsCommit() {
    return Stream.of(true, false)
        .map(mayInterrupt ->
            dynamicTest(
                "mayInterruptIfRunning=" + mayInterrupt,
                () -> {
                  BigtableEmulator.Tables t = noneStore();
                  Gate gate = new Gate("beforeCommit");
                  BlockingExecution execution = BlockingExecution.singleWorker(1);
                  try {
                    AsyncEventStore<String, String> store =
                        BigtableEventStore.createOffloaded(
                            open(t, RetentionPolicy.none(), hooks(gate)), config(gate), execution);
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
                  assertEquals(List.of(1L), stored(t));
                  assertJournalRowExists(t, 1);
                }));
  }

  private static void assertJournalRowExists(BigtableEmulator.Tables t, long seqNr) {
    assertTrue(
        EMULATOR.row(t.journal, journalKey("Order-1", seqNr)) != null,
        "the tail write-out after the commit ran");
  }

  @Test
  void aCallbackOnTheWorkerMayAdmitAnotherOperationWithinCapacity() throws Exception {
    BigtableEmulator.Tables t = noneStore();
    Gate gate = new Gate("beforeCommit");
    BlockingExecution execution = BlockingExecution.singleWorker(1);
    try {
      AsyncEventStore<String, String> store =
          BigtableEventStore.createOffloaded(
              open(t, RetentionPolicy.none(), hooks(gate)), config(gate), execution);
      CompletableFuture<Void> write = store.persistEvent(event(1));
      Thread worker = gate.awaitEntered();
      List<Thread> callbackThreads = Collections.synchronizedList(new ArrayList<>());
      CompletableFuture<List<EventEnvelope<String>>> chained =
          write.thenCompose(ignored -> {
            callbackThreads.add(Thread.currentThread());
            return store.getEventsByIdSinceSeqNr(AID, 0);
          });
      gate.release();

      assertEquals(List.of(1L), seqNrs(get(chained)));
      assertEquals(List.of(worker), callbackThreads, "the callback admitted from the worker");
    } finally {
      gate.release();
      drain(execution);
    }
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

    String name() {
      return point + " " + thrown.getClass().getSimpleName();
    }
  }

  @TestFactory
  Stream<DynamicTest> aFailureBeforeTheCommitKeepsItsClassificationAndLaterWorkStillRuns() {
    return Stream.of(
            new Failure(
                "payload.serialize",
                new IllegalStateException("injected"),
                SerializationException.class),
            new Failure("payload.serialize", new AssertionError("injected"), AssertionError.class),
            new Failure(
                "journalWritePrevious",
                new StorageException("injected write-out failure"),
                StorageException.class),
            new Failure(
                "journalWritePrevious", new AssertionError("injected"), AssertionError.class),
            new Failure(
                "beforeCommit", new IllegalStateException("injected"), IllegalStateException.class),
            new Failure("beforeCommit", new AssertionError("injected"), AssertionError.class))
        .map(failure ->
            dynamicTest(
                failure.name(),
                () -> {
                  BigtableEmulator.Tables t = noneStore();
                  store(open(t, RetentionPolicy.none())).persistEvent(event(1));
                  Gate gate = new Gate(failure.point, failure.thrown);
                  BlockingExecution execution = BlockingExecution.singleWorker(2);
                  try {
                    AsyncEventStore<String, String> store =
                        BigtableEventStore.createOffloaded(
                            open(t, RetentionPolicy.none(), hooks(gate)), config(gate), execution);
                    CompletableFuture<Void> failing = store.persistEvent(event(2));
                    gate.awaitEntered();
                    CompletableFuture<Void> retried = store.persistEvent(event(2));
                    CompletableFuture<Void> next = store.persistEvent(event(3));
                    gate.release();

                    assertInstanceOf(failure.reported, failureOf(failing));
                    get(retried);
                    get(next);
                  } finally {
                    gate.release();
                    drain(execution);
                  }
                  assertEquals(List.of(1L, 2L, 3L), stored(t));
                }));
  }

  @TestFactory
  Stream<DynamicTest> aFailureAfterTheCommitLeavesTheAppendCommitted() {
    return Stream.of(
            new Failure(
                "afterCommit", new StorageException("injected unknown"), StorageException.class),
            new Failure("afterCommit", new AssertionError("injected"), AssertionError.class),
            new Failure("journalWriteTail", new AssertionError("injected"), AssertionError.class))
        .map(failure ->
            dynamicTest(
                failure.name(),
                () -> {
                  BigtableEmulator.Tables t = noneStore();
                  Gate gate = new Gate(failure.point, failure.thrown);
                  BlockingExecution execution = BlockingExecution.singleWorker(2);
                  try {
                    AsyncEventStore<String, String> store =
                        BigtableEventStore.createOffloaded(
                            open(t, RetentionPolicy.none(), hooks(gate)), config(gate), execution);
                    CompletableFuture<Void> failing = store.persistEvent(event(1));
                    gate.awaitEntered();
                    CompletableFuture<Void> resent = store.persistEvent(event(1));
                    CompletableFuture<Void> next = store.persistEvent(event(2));
                    gate.release();

                    assertInstanceOf(failure.reported, failureOf(failing));
                    assertInstanceOf(OptimisticLockException.class, failureOf(resent));
                    get(next);
                  } finally {
                    gate.release();
                    drain(execution);
                  }
                  assertEquals(List.of(1L, 2L), stored(t));
                }));
  }

  @TestFactory
  Stream<DynamicTest> aReadFailureKeepsItsClassificationAndLaterWorkStillRuns() {
    return Stream.of(
            new Failure(
                "payload.deserialize",
                new IllegalStateException("injected"),
                SerializationException.class),
            new Failure(
                "payload.deserialize", new AssertionError("injected"), AssertionError.class),
            new Failure("beforeReadEvents", new AssertionError("injected"), AssertionError.class),
            new Failure(
                "snapshot.deserialize",
                new IllegalStateException("injected"),
                SerializationException.class),
            new Failure("beforeReadSnapshot", new AssertionError("injected"), AssertionError.class))
        .map(failure ->
            dynamicTest(
                failure.name(),
                () -> {
                  BigtableEmulator.Tables t = noneStore();
                  store(open(t, RetentionPolicy.none()))
                      .persistEventAndSnapshot(event(1), snapshot(1));
                  Function<AsyncEventStore<String, String>, CompletableFuture<?>> read =
                      failure.point.equals("beforeReadSnapshot")
                              || failure.point.startsWith("snapshot.")
                          ? store -> store.getLatestSnapshotById(AID)
                          : store -> store.getEventsByIdSinceSeqNr(AID, 0);
                  Gate gate = new Gate(failure.point, failure.thrown);
                  BlockingExecution execution = BlockingExecution.singleWorker(2);
                  try {
                    AsyncEventStore<String, String> store =
                        BigtableEventStore.createOffloaded(
                            open(t, RetentionPolicy.none(), hooks(gate)), config(gate), execution);
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
                  assertEquals(List.of(1L, 2L), stored(t));
                }));
  }

  /** Records the thread of each WARN on the journal logger. */
  private static final class RecordingAppender extends AppenderBase<ILoggingEvent> {
    final List<String> warnThreads = Collections.synchronizedList(new ArrayList<>());

    @Override
    protected void append(ILoggingEvent loggingEvent) {
      if (loggingEvent.getLevel() == Level.WARN) {
        warnThreads.add(loggingEvent.getThreadName());
      }
    }
  }

  @Test
  void aTailWriteOutFailureIsLoggedOnTheWorkerAndTheAppendSucceeds() throws Exception {
    BigtableEmulator.Tables t = noneStore();
    Gate gate = new Gate("journalWriteTail", new StorageException("injected tail failure"));
    Logger logger =
        (Logger)
            LoggerFactory.getLogger("com.github.j5ik2o.event.store.adapter.java.bigtable.journal");
    RecordingAppender appender = new RecordingAppender();
    appender.setContext(logger.getLoggerContext());
    appender.start();
    logger.addAppender(appender);
    BlockingExecution execution = BlockingExecution.singleWorker(2);
    try {
      AsyncEventStore<String, String> store =
          BigtableEventStore.createOffloaded(
              open(t, RetentionPolicy.none(), hooks(gate)), config(gate), execution);
      CompletableFuture<Void> committed = store.persistEvent(event(1));
      Thread worker = gate.awaitEntered();
      CompletableFuture<List<EventEnvelope<String>>> read = store.getEventsByIdSinceSeqNr(AID, 0);
      gate.release();

      get(committed);
      assertEquals(List.of(1L), seqNrs(get(read)), "the head holds the tail");
      assertEquals(List.of(worker.getName()), appender.warnThreads);
      assertNotSame(Thread.currentThread(), worker);
    } finally {
      gate.release();
      drain(execution);
      logger.detachAppender(appender);
    }
    assertNull(EMULATOR.row(t.journal, journalKey("Order-1", 1)), "the tail row was not written");
    assertEquals(headSeqNr(1), EMULATOR.text(t.head, prefix("Order-1"), "head", "seq_nr"));
  }

  @Test
  void aDelayedWriteOutCannotReviveHistoryThatRetentionRetired() throws Exception {
    BigtableEmulator.Tables t = deleteStore(1);
    store(open(t, RetentionPolicy.delete(1))).persistEventAndSnapshot(event(1), snapshot(1));
    Gate gate = new Gate("journalWritePrevious");
    BlockingExecution slow = BlockingExecution.singleWorker(1);
    BlockingExecution fast = BlockingExecution.singleWorker(2);
    try {
      AsyncEventStore<String, String> delayed =
          BigtableEventStore.createOffloaded(
              open(t, RetentionPolicy.delete(1), hooks(gate)), config(gate), slow);
      AsyncEventStore<String, String> moving =
          BigtableEventStore.createOffloaded(
              open(t, RetentionPolicy.delete(1)), config(nothing()), fast);
      CompletableFuture<Void> late = delayed.persistEventAndSnapshot(event(2), snapshot(2));
      gate.awaitEntered();
      get(moving.persistEventAndSnapshot(event(2), snapshot(2)));
      get(moving.persistEventAndSnapshot(event(3), snapshot(3)));
      assertEquals("retired", historyState(t, 1));
      gate.release();

      assertInstanceOf(OptimisticLockException.class, failureOf(late));
    } finally {
      gate.release();
      drain(slow);
      drain(fast);
    }
    assertEquals("retired", historyState(t, 1));
    assertFalse(hasHistoryEnvelope(t, 1), "history was not recreated");
    assertEquals(List.of(1L, 2L, 3L), stored(t, RetentionPolicy.delete(1)));
  }

  @Test
  void anErrorDuringRetentionReachesTheCallerAndKeepsTheCommittedAppend() throws Exception {
    BigtableEmulator.Tables t = deleteStore(1);
    store(open(t, RetentionPolicy.delete(1))).persistEventAndSnapshot(event(1), snapshot(1));
    Gate gate = new Gate("beforeRetentionDelete", new AssertionError("injected"));
    BlockingExecution execution = BlockingExecution.singleWorker(2);
    try {
      AsyncEventStore<String, String> store =
          BigtableEventStore.createOffloaded(
              open(t, RetentionPolicy.delete(1), hooks(gate)), config(gate), execution);
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
    assertEquals(List.of(1L, 2L, 3L), stored(t, RetentionPolicy.delete(1)));
    assertEquals("active", historyState(t, 1), "the failed retention retired nothing");
  }

  @TestFactory
  Stream<DynamicTest> aFailingRetentionListenerKeepsTheCommittedAppend() {
    return Stream.<Throwable>of(new IllegalStateException("injected"), new AssertionError("x"))
        .map(thrown ->
            dynamicTest(
                thrown.getClass().getSimpleName(),
                () -> {
                  BigtableEmulator.Tables t = deleteStore(1);
                  store(open(t, RetentionPolicy.delete(1)))
                      .persistEventAndSnapshot(event(1), snapshot(1));
                  Gate gate = new Gate("listener", thrown);
                  BlockingExecution execution = BlockingExecution.singleWorker(2);
                  try {
                    AsyncEventStore<String, String> store =
                        BigtableEventStore.createOffloaded(
                            open(t, RetentionPolicy.delete(1), hooks(gate, true)),
                            config(gate),
                            execution);
                    CompletableFuture<Void> committed =
                        store.persistEventAndSnapshot(event(2), snapshot(2));
                    assertNotSame(Thread.currentThread(), gate.awaitEntered());
                    gate.release();

                    get(committed);
                  } finally {
                    gate.release();
                    drain(execution);
                  }
                  assertEquals(List.of(1L, 2L), stored(t, RetentionPolicy.delete(1)));
                  assertEquals("active", historyState(t, 1));
                }));
  }

  @Test
  void aLostCommitResponseIsAnUnknownOutcomeThatTheNextAcceptedWriteObserves() throws Exception {
    BigtableEmulator.Tables t = noneStore();
    BlockingExecution execution = BlockingExecution.singleWorker(2);
    try (Clients clients = new Clients()) {
      AsyncEventStore<String, String> store =
          BigtableEventStore.createOffloaded(
              clients.open(t, RetentionPolicy.none(), nothing()), config(nothing()), execution);
      int sent = clients.proxy.callsTo(BigtableRecordingProxy.CHECK_AND_MUTATE).size();
      clients.proxy.dropNextCheckAndMutateResponse();

      CompletableFuture<Void> lost = store.persistEvent(event(1));
      CompletableFuture<Void> resent = store.persistEvent(event(1));
      CompletableFuture<Void> next = store.persistEvent(event(2));

      assertInstanceOf(StorageException.class, failureOf(lost));
      assertInstanceOf(OptimisticLockException.class, failureOf(resent));
      get(next);
      List<BigtableRecordingProxy.Call> after =
          clients.proxy.callsTo(BigtableRecordingProxy.CHECK_AND_MUTATE);
      assertEquals(1, countHeadCommitsOf(after.subList(sent, after.size()), t));
    } finally {
      drain(execution);
    }
    assertEquals(List.of(1L, 2L), stored(t));
  }

  @Test
  void aLostResponseOfThePreviousWriteOutFailsTheAppendWithoutMovingTheHead() throws Exception {
    BigtableEmulator.Tables t = noneStore();
    store(open(t, RetentionPolicy.none())).persistEvent(event(1));
    EMULATOR.deleteRow(t.journal, journalKey("Order-1", 1));
    BlockingExecution execution = BlockingExecution.singleWorker(2);
    try (Clients clients = new Clients()) {
      AsyncEventStore<String, String> store =
          BigtableEventStore.createOffloaded(
              clients.open(t, RetentionPolicy.none(), nothing()), config(nothing()), execution);
      clients.proxy.dropNextCheckAndMutateResponse();

      CompletableFuture<Void> failed = store.persistEvent(event(2));
      CompletableFuture<Optional<SnapshotReadResult<String>>> headAfterFailure =
          store.getLatestSnapshotById(AID);
      CompletableFuture<Void> resent = store.persistEvent(event(2));

      assertInstanceOf(StorageException.class, failureOf(failed));
      assertEquals(1, get(headAfterFailure).get().headSeqNr(), "the failed append kept the head");
      get(resent);
    } finally {
      drain(execution);
    }
    assertEquals(List.of(1L, 2L), stored(t));
    assertEquals(headSeqNr(2), EMULATOR.text(t.head, prefix("Order-1"), "head", "seq_nr"));
  }

  /** The conditional updates that create the head row of Order-1. */
  private static int countHeadCommitsOf(
      List<BigtableRecordingProxy.Call> calls, BigtableEmulator.Tables t) throws Exception {
    int count = 0;
    for (BigtableRecordingProxy.Call call : calls) {
      for (byte[] request : call.requests) {
        CheckAndMutateRowRequest parsed = CheckAndMutateRowRequest.parseFrom(request);
        if (parsed.getTableName().endsWith("/" + t.head)
            && parsed.getRowKey().equals(prefix("Order-1"))
            && parsed.getFalseMutationsCount() > 0) {
          count++;
        }
      }
    }
    return count;
  }

  @Test
  void anSdkDeadlineOnTheCommitFailsOnlyThatResultAndTheWorkerMovesOn() throws Exception {
    BigtableEmulator.Tables t = noneStore();
    BlockingExecution execution = BlockingExecution.singleWorker(2);
    try (Clients clients = new Clients(Duration.ofMillis(500))) {
      AsyncEventStore<String, String> store =
          BigtableEventStore.createOffloaded(
              clients.open(t, RetentionPolicy.none(), nothing()), config(nothing()), execution);
      clients.proxy.holdNextCheckAndMutateResponse();

      CompletableFuture<Void> timedOut = store.persistEvent(event(1));
      CompletableFuture<Void> resent = store.persistEvent(event(1));
      CompletableFuture<Void> next = store.persistEvent(event(2));

      Throwable failure = failureOf(timedOut);
      assertInstanceOf(StorageException.class, failure);
      assertInstanceOf(DeadlineExceededException.class, failure.getCause());
      assertInstanceOf(OptimisticLockException.class, failureOf(resent));
      get(next);
    } finally {
      drain(execution);
    }
    assertEquals(List.of(1L, 2L), stored(t));
  }

  @Test
  void closingTheDataClientBeforeDrainFailsAcceptedWorkAndDrainStillCompletes() throws Exception {
    BigtableEmulator.Tables t = noneStore();
    Gate gate = new Gate("payload.serialize");
    BlockingExecution execution = BlockingExecution.singleWorker(2);
    try (Clients clients = new Clients()) {
      AsyncEventStore<String, String> store =
          BigtableEventStore.createOffloaded(
              clients.open(t, RetentionPolicy.none(), nothing()), config(gate), execution);
      AsyncEventStore<String, String> healthy =
          BigtableEventStore.createOffloaded(
              open(t, RetentionPolicy.none()), config(nothing()), execution);
      CompletableFuture<Void> active = store.persistEvent(event(1));
      gate.awaitEntered();
      CompletableFuture<List<EventEnvelope<String>>> queued = store.getEventsByIdSinceSeqNr(AID, 0);
      assertFalse(queued.isDone(), "the execution accepted the queued read");

      clients.data.close();
      gate.release();

      // #829: the closed SDK client fails with its own RejectedExecutionException, not STORAGE.
      assertInstanceOf(RejectedExecutionException.class, failureOf(active));
      assertInstanceOf(RejectedExecutionException.class, failureOf(queued));
      assertEquals(
          List.of(), seqNrs(get(healthy.getEventsByIdSinceSeqNr(AID, 0))), "the worker moved on");
      execution.close();
      get(execution.drained().toCompletableFuture());
    } finally {
      gate.release();
      drain(execution);
    }
    assertEquals(List.of(), stored(t));
  }

  @Test
  void concurrentCallersRunInTheOneOrderInWhichTheirAdmissionsWereAccepted() throws Exception {
    BigtableEmulator.Tables t = noneStore();
    int callers = 4;
    int rounds = 25;
    List<String> serialized = Collections.synchronizedList(new ArrayList<>());
    BlockingExecution execution = BlockingExecution.singleWorker(callers * rounds);
    try {
      AsyncEventStore<String, String> store =
          BigtableEventStore.createOffloaded(
              open(t, RetentionPolicy.none()),
              ConcurrentAdmission.recordingPayloads(serialized),
              execution);
      List<ConcurrentAdmission.Admission> admissions =
          ConcurrentAdmission.admit(
              callers,
              rounds,
              (caller, round) -> store.persistEvent(ConcurrentAdmission.event(caller, round)));
      for (ConcurrentAdmission.Admission admission : admissions) {
        get(admission.result);
      }
      ConcurrentAdmission.assertRanInOneAdmissionOrder(admissions, serialized);
    } finally {
      drain(execution);
    }
    EventStore<String, String> readBack = store(open(t, RetentionPolicy.none()));
    for (int caller = 0; caller < callers; caller++) {
      AggregateId id = ConcurrentAdmission.event(caller, 0).aggregateId();
      assertEquals(rounds, readBack.getEventsByIdSinceSeqNr(id, 0).size(), id.asString());
    }
  }

  @Test
  void allFourOperationsAgainstTheEmulatorAndCloseTheClientsAfterDrain() throws Exception {
    BigtableEmulator.Tables t = deleteStore(1);
    BlockingExecution execution = BlockingExecution.singleWorker(16);
    Clients clients = new Clients();
    AsyncEventStore<String, String> store =
        BigtableEventStore.createOffloaded(
            clients.open(t, RetentionPolicy.delete(1), nothing()), config(nothing()), execution);
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
    for (CompletableFuture<?> result : List.of(duplicate, latest, since)) {
      assertTrue(result.isDone(), "every result completed before drain");
    }
    clients.close();

    for (CompletableFuture<?> write : admitted) {
      assertTrue(write.isDone() && !write.isCompletedExceptionally());
    }
    assertInstanceOf(OptimisticLockException.class, failureOf(duplicate));
    assertEquals(4, get(latest).get().headSeqNr());
    assertEquals(3, get(latest).get().snapshot().get().seqNr());
    assertEquals(List.of(2L, 3L, 4L), seqNrs(get(since)));
    assertEquals(List.of(1L, 2L, 3L, 4L), stored(t, RetentionPolicy.delete(1)));
    assertNull(historyState(t, 1), "an events-only row has no history");
    assertEquals("retired", historyState(t, 2));
    assertEquals("active", historyState(t, 3));
    assertNull(historyState(t, 4));
  }
}
