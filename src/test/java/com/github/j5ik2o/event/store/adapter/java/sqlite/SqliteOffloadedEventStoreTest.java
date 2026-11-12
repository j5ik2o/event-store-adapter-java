package com.github.j5ik2o.event.store.adapter.java.sqlite;

import static com.github.j5ik2o.event.store.adapter.java.sqlite.SqliteTestFixtures.AID;
import static com.github.j5ik2o.event.store.adapter.java.sqlite.SqliteTestFixtures.count;
import static com.github.j5ik2o.event.store.adapter.java.sqlite.SqliteTestFixtures.event;
import static com.github.j5ik2o.event.store.adapter.java.sqlite.SqliteTestFixtures.execute;
import static com.github.j5ik2o.event.store.adapter.java.sqlite.SqliteTestFixtures.initialized;
import static com.github.j5ik2o.event.store.adapter.java.sqlite.SqliteTestFixtures.longs;
import static com.github.j5ik2o.event.store.adapter.java.sqlite.SqliteTestFixtures.settings;
import static com.github.j5ik2o.event.store.adapter.java.sqlite.SqliteTestFixtures.snapshot;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

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
import com.github.j5ik2o.event.store.adapter.java.core.UnrecoverableExecution;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

@Timeout(30)
class SqliteOffloadedEventStoreTest {
  private static final String JOURNAL = "SELECT seq_nr FROM journal ORDER BY seq_nr";
  private static final String FAIL_RETENTION =
      "CREATE TRIGGER fail_retention BEFORE DELETE ON snapshot_history BEGIN "
          + "SELECT RAISE(ABORT, 'injected retention failure'); END";

  @TempDir Path directory;

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

  private static SqliteStorageHooks hooks(Consumer<String> at) {
    return new SqliteStorageHooks() {
      @Override
      public void afterBeginImmediate(AggregateId aggregateId) {
        at.accept("afterBeginImmediate");
      }

      @Override
      public void beforeCommit(AggregateId aggregateId) {
        at.accept("beforeCommit");
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

  private Path file(String name, RetentionPolicy policy) throws Exception {
    return initialized(Files.createDirectories(directory.resolve(name)), policy);
  }

  private static final class Point {
    final String name;
    final RetentionPolicy policy;
    final Consumer<AsyncEventStore<String, String>> setup;
    final Function<AsyncEventStore<String, String>, CompletableFuture<?>> operation;
    final List<Long> journal;

    Point(
        String name,
        RetentionPolicy policy,
        Consumer<AsyncEventStore<String, String>> setup,
        Function<AsyncEventStore<String, String>, CompletableFuture<?>> operation,
        List<Long> journal) {
      this.name = name;
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
    RetentionPolicy keepOne = RetentionPolicy.delete(1);
    return Stream.of(
        new Point("payload.serialize", RetentionPolicy.none(), none, append, List.of(1L)),
        new Point("afterBeginImmediate", RetentionPolicy.none(), none, append, List.of(1L)),
        new Point("snapshot.serialize", RetentionPolicy.none(), none, appendSnapshot, List.of(1L)),
        new Point("beforeCommit", RetentionPolicy.none(), none, appendSnapshot, List.of(1L)),
        new Point("beforeRetentionDelete", keepOne, oneSnapshot, secondSnapshot, List.of(1L, 2L)),
        new Point("listener", keepOne, oneSnapshot, secondSnapshot, List.of(1L, 2L)),
        new Point("beforeReadEvents", RetentionPolicy.none(), oneEvent, readEvents, List.of(1L)),
        new Point("payload.deserialize", RetentionPolicy.none(), oneEvent, readEvents, List.of(1L)),
        new Point(
            "beforeReadSnapshot", RetentionPolicy.none(), oneSnapshot, readSnapshot, List.of(1L)),
        new Point(
            "snapshot.deserialize",
            RetentionPolicy.none(),
            oneSnapshot,
            readSnapshot,
            List.of(1L)));
  }

  @TestFactory
  Stream<DynamicTest> everyOperationPartRunsOnTheWorkerAndDrainWaitsForItsExit() {
    return points()
        .map(point ->
            dynamicTest(
                point.name,
                () -> {
                  Path file = file(point.name, point.policy);
                  Gate gate = new Gate(point.name);
                  BlockingExecution execution = BlockingExecution.singleWorker(4);
                  try (SqliteStorage storage =
                      SqliteStorage.open(file, settings(point.policy), hooks(gate))) {
                    AsyncEventStore<String, String> store =
                        SqliteEventStore.createOffloaded(storage, config(gate), execution);
                    point.setup.accept(store);
                    if (point.name.equals("listener")) {
                      execute(file, FAIL_RETENTION);
                    }
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
                  assertEquals(point.journal, longs(file, JOURNAL));
                }));
  }

  @Test
  void factoryChecksArgumentsWithoutInvokingAnyOperationCode() throws Exception {
    Path file = file("factory", RetentionPolicy.none());
    List<String> reached = Collections.synchronizedList(new ArrayList<>());
    BlockingExecution execution = BlockingExecution.singleWorker(1);
    try (SqliteStorage storage =
        SqliteStorage.open(file, settings(RetentionPolicy.none()), hooks(reached::add))) {
      EventStoreConfig<String, String> config = config(reached::add);
      assertThrows(
          ConfigurationException.class,
          () -> SqliteEventStore.createOffloaded(null, config, execution));
      assertThrows(
          ConfigurationException.class,
          () -> SqliteEventStore.<String, String>createOffloaded(storage, null, execution));
      assertThrows(
          ConfigurationException.class,
          () -> SqliteEventStore.createOffloaded(storage, config, null));
      SqliteEventStore.createOffloaded(storage, config, execution);
      assertEquals(List.of(), reached);
    } finally {
      drain(execution);
    }
  }

  @Test
  void validationRunsOnTheWorkerSoInvalidInputFailsTheFutureInOrder() throws Exception {
    Path file = file("validation", RetentionPolicy.none());
    Gate gate = new Gate("afterBeginImmediate");
    BlockingExecution execution = BlockingExecution.singleWorker(2);
    try (SqliteStorage storage =
        SqliteStorage.open(file, settings(RetentionPolicy.none()), hooks(gate))) {
      AsyncEventStore<String, String> store =
          SqliteEventStore.createOffloaded(storage, config(gate), execution);
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
    assertEquals(List.of(1L), longs(file, JOURNAL));
  }

  @Test
  void aFullQueueRejectsWithoutRunningOrFallingBackToTheCaller() throws Exception {
    Path file = file("saturation", RetentionPolicy.none());
    Gate gate = new Gate("afterBeginImmediate");
    List<String> reached = Collections.synchronizedList(new ArrayList<>());
    Consumer<String> at =
        name -> {
          reached.add(name);
          gate.accept(name);
        };
    BlockingExecution execution = BlockingExecution.singleWorker(1);
    try (SqliteStorage storage =
        SqliteStorage.open(file, settings(RetentionPolicy.none()), hooks(at))) {
      AsyncEventStore<String, String> store =
          SqliteEventStore.createOffloaded(storage, config(at), execution);
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
    assertEquals(List.of(1L, 2L), longs(file, JOURNAL));
    assertEquals(2, reached.stream().filter("payload.serialize"::equals).count());
  }

  @Test
  void readsAndWritesFromOneCallerRunInAdmissionOrder() throws Exception {
    Path file = file("fifo", RetentionPolicy.none());
    Gate gate = new Gate("afterBeginImmediate");
    BlockingExecution execution = BlockingExecution.singleWorker(8);
    try (SqliteStorage storage =
        SqliteStorage.open(file, settings(RetentionPolicy.none()), hooks(gate))) {
      AsyncEventStore<String, String> store =
          SqliteEventStore.createOffloaded(storage, config(gate), execution);
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
  void storesSharingOneStorageAndOneExecutionShareOneOrder() throws Exception {
    Path file = file("alias", RetentionPolicy.none());
    Gate gate = new Gate("afterBeginImmediate");
    BlockingExecution execution = BlockingExecution.singleWorker(4);
    try (SqliteStorage storage =
        SqliteStorage.open(file, settings(RetentionPolicy.none()), hooks(gate))) {
      AsyncEventStore<String, String> writer =
          SqliteEventStore.createOffloaded(storage, config(gate), execution);
      AsyncEventStore<String, String> other =
          SqliteEventStore.createOffloaded(storage, config(name -> {}), execution);
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
  void anotherExecutionOnTheSameStorageIsNotOrderedWithIt() throws Exception {
    Path file = file("two-executions", RetentionPolicy.none());
    Gate gate = new Gate("payload.serialize");
    BlockingExecution first = BlockingExecution.singleWorker(1);
    BlockingExecution second = BlockingExecution.singleWorker(1);
    try (SqliteStorage storage = SqliteStorage.open(file, settings(RetentionPolicy.none()))) {
      AsyncEventStore<String, String> early =
          SqliteEventStore.createOffloaded(storage, config(gate), first);
      AsyncEventStore<String, String> late =
          SqliteEventStore.createOffloaded(storage, config(name -> {}), second);
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
  }

  @Test
  void concurrentCallersRunInTheOneOrderInWhichTheirAdmissionsWereAccepted() throws Exception {
    Path file = file("concurrent-admission", RetentionPolicy.none());
    int callers = 4;
    int rounds = 50;
    List<String> serialized = Collections.synchronizedList(new ArrayList<>());
    BlockingExecution execution = BlockingExecution.singleWorker(callers * rounds);
    try (SqliteStorage storage = SqliteStorage.open(file, settings(RetentionPolicy.none()))) {
      AsyncEventStore<String, String> store =
          SqliteEventStore.createOffloaded(
              storage, ConcurrentAdmission.recordingPayloads(serialized), execution);
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
    assertEquals((long) callers * rounds, count(file, "journal"));
  }

  @Test
  void theRawApiBeforeTheOffloadedStoreAndAfterDrainSharesOneOrderWithIt() throws Exception {
    Path file = file("raw-compliant", RetentionPolicy.none());
    BlockingExecution execution = BlockingExecution.singleWorker(4);
    try (SqliteStorage storage = SqliteStorage.open(file, settings(RetentionPolicy.none()))) {
      EventStore<String, String> raw = SqliteEventStore.create(storage, config(name -> {}));
      AsyncEventStore<String, String> rawAsync =
          SqliteEventStore.createAsync(storage, config(name -> {}));
      raw.persistEvent(event(1));
      get(rawAsync.persistEvent(event(2)));
      AsyncEventStore<String, String> offloaded =
          SqliteEventStore.createOffloaded(storage, config(name -> {}), execution);
      CompletableFuture<Void> third = offloaded.persistEvent(event(3));
      CompletableFuture<Void> fourth = offloaded.persistEvent(event(4));
      CompletableFuture<List<EventEnvelope<String>>> read =
          offloaded.getEventsByIdSinceSeqNr(AID, 0);
      drain(execution);

      get(third);
      get(fourth);
      assertEquals(List.of(1L, 2L, 3L, 4L), seqNrs(get(read)));
      raw.persistEvent(event(5));
      get(rawAsync.persistEvent(event(6)));
      assertEquals(List.of(1L, 2L, 3L, 4L, 5L, 6L), seqNrs(raw.getEventsByIdSinceSeqNr(AID, 0)));
    } finally {
      drain(execution);
    }
    assertEquals(List.of(1L, 2L, 3L, 4L, 5L, 6L), longs(file, JOURNAL));
  }

  @TestFactory
  Stream<DynamicTest> theRawApiDuringAcceptedWorkOvertakesItAndTheConflictReportsIt() {
    EventEnvelope<String> rawEvent =
        EventEnvelope.<String>builder()
            .aggregateId(AID)
            .seqNr(1)
            .occurredAt(Instant.EPOCH)
            .manifest("event")
            .payload("raw-1")
            .build();
    Map<String, Consumer<SqliteStorage>> rawWrites = new LinkedHashMap<>();
    rawWrites.put(
        "create",
        storage -> SqliteEventStore.create(storage, config(name -> {})).persistEvent(rawEvent));
    rawWrites.put(
        "createAsync",
        storage ->
            SqliteEventStore.createAsync(storage, config(name -> {}))
                .persistEvent(rawEvent)
                .join());
    return rawWrites.entrySet().stream()
        .map(rawWrite ->
            dynamicTest(
                rawWrite.getKey(),
                () -> {
                  Path file = file("raw-" + rawWrite.getKey(), RetentionPolicy.none());
                  Gate gate = new Gate("payload.serialize");
                  BlockingExecution execution = BlockingExecution.singleWorker(2);
                  try (SqliteStorage storage =
                      SqliteStorage.open(file, settings(RetentionPolicy.none()))) {
                    AsyncEventStore<String, String> offloaded =
                        SqliteEventStore.createOffloaded(storage, config(gate), execution);
                    CompletableFuture<Void> accepted = offloaded.persistEvent(event(1));
                    gate.awaitEntered();
                    CompletableFuture<List<EventEnvelope<String>>> queued =
                        offloaded.getEventsByIdSinceSeqNr(AID, 0);

                    rawWrite.getValue().accept(storage);
                    gate.release();

                    assertInstanceOf(OptimisticLockException.class, failureOf(accepted));
                    List<EventEnvelope<String>> read = get(queued);
                    assertEquals(List.of(1L), seqNrs(read));
                    assertEquals("raw-1", read.get(0).payload());
                  } finally {
                    gate.release();
                    drain(execution);
                  }
                }));
  }

  @Test
  void theRawApiDuringAcceptedWorkCanLetAnAcceptedWriteSucceedWithoutAnyReport() throws Exception {
    Path file = file("raw-unreported", RetentionPolicy.none());
    Gate gate = new Gate("payload.serialize");
    BlockingExecution execution = BlockingExecution.singleWorker(1);
    try (SqliteStorage storage = SqliteStorage.open(file, settings(RetentionPolicy.none()))) {
      AsyncEventStore<String, String> offloaded =
          SqliteEventStore.createOffloaded(storage, config(gate), execution);
      CompletableFuture<Void> accepted = offloaded.persistEvent(event(2));
      gate.awaitEntered();

      SqliteEventStore.create(storage, config(name -> {})).persistEvent(event(1));
      gate.release();

      get(accepted);
    } finally {
      gate.release();
      drain(execution);
    }
    assertEquals(List.of(1L, 2L), longs(file, JOURNAL));
  }

  @Test
  void closingStorageBeforeDrainFailsAcceptedWork() throws Exception {
    Path file = file("early-close", RetentionPolicy.none());
    Gate gate = new Gate("payload.serialize");
    BlockingExecution execution = BlockingExecution.singleWorker(1);
    try {
      SqliteStorage storage = SqliteStorage.open(file, settings(RetentionPolicy.none()));
      AsyncEventStore<String, String> store =
          SqliteEventStore.createOffloaded(storage, config(gate), execution);
      CompletableFuture<Void> accepted = store.persistEvent(event(1));
      gate.awaitEntered();

      storage.close();
      gate.release();

      assertInstanceOf(StorageException.class, failureOf(accepted));
    } finally {
      gate.release();
      drain(execution);
    }
    assertEquals(List.of(), longs(file, JOURNAL));
  }

  @Test
  void closeDrainsAFullQueueAndRejectsLaterAdmission() throws Exception {
    Path file = file("close-full", RetentionPolicy.none());
    Gate gate = new Gate("afterBeginImmediate");
    BlockingExecution execution = BlockingExecution.singleWorker(2);
    try (SqliteStorage storage =
        SqliteStorage.open(file, settings(RetentionPolicy.none()), hooks(gate))) {
      AsyncEventStore<String, String> store =
          SqliteEventStore.createOffloaded(storage, config(gate), execution);
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
    assertEquals(List.of(1L, 2L, 3L), longs(file, JOURNAL));
  }

  @Test
  void cancellingAQueuedWriteSkipsItWithoutRenumberingLaterWrites() throws Exception {
    Path file = file("queued-cancel", RetentionPolicy.none());
    Gate gate = new Gate("afterBeginImmediate");
    BlockingExecution execution = BlockingExecution.singleWorker(2);
    try (SqliteStorage storage =
        SqliteStorage.open(file, settings(RetentionPolicy.none()), hooks(gate))) {
      AsyncEventStore<String, String> store =
          SqliteEventStore.createOffloaded(storage, config(gate), execution);
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
    assertEquals(List.of(1L), longs(file, JOURNAL));
  }

  @Test
  void closeAdmissionAndCancellationRacingNeverStrandAnAcceptedWrite() throws Exception {
    Path file = file("close-race", RetentionPolicy.none());
    try (SqliteStorage storage = SqliteStorage.open(file, settings(RetentionPolicy.none()))) {
      EventStore<String, String> readBack = SqliteEventStore.create(storage, config(name -> {}));
      for (int i = 0; i < 50; i++) {
        boolean cancel = i % 2 == 0;
        AggregateId leaderId = AggregateId.of("Order", "leader-" + i);
        AggregateId racedId = AggregateId.of("Order", "raced-" + i);
        Gate gate = new Gate("payload.serialize");
        BlockingExecution execution = BlockingExecution.singleWorker(1);
        CountDownLatch race = new CountDownLatch(1);
        AtomicReference<CompletableFuture<Void>> raced = new AtomicReference<>();
        AtomicReference<Throwable> threadFailure = new AtomicReference<>();
        Thread producer = null, closer = null;
        try {
          AsyncEventStore<String, String> store =
              SqliteEventStore.createOffloaded(storage, config(gate), execution);
          CompletableFuture<Void> leader = store.persistEvent(event(leaderId, 1));
          gate.awaitEntered();
          producer =
              new Thread(() -> {
                try {
                  race.await();
                  raced.set(store.persistEvent(event(racedId, 1)));
                  if (cancel) {
                    raced.get().cancel(true);
                  }
                } catch (Throwable failure) {
                  threadFailure.set(failure);
                }
              });
          closer =
              new Thread(() -> {
                try {
                  race.await();
                  execution.close();
                } catch (Throwable failure) {
                  threadFailure.set(failure);
                }
              });
          producer.start();
          closer.start();
          race.countDown();
          gate.release();
          producer.join(5000);
          closer.join(5000);
          assertFalse(producer.isAlive());
          assertFalse(closer.isAlive());
          assertNull(threadFailure.get());
          drain(execution);

          get(leader);
          assertEquals(List.of(1L), seqNrs(readBack.getEventsByIdSinceSeqNr(leaderId, 0)));
          CompletableFuture<Void> result = raced.get();
          assertNotNull(result);
          assertTrue(result.isDone(), "the raced write is not stranded after drain");
          List<Long> stored = seqNrs(readBack.getEventsByIdSinceSeqNr(racedId, 0));
          if (result.isCancelled()) {
            assertTrue(stored.size() <= 1, "a cancelled write ran at most once: " + stored);
          } else if (result.isCompletedExceptionally()) {
            assertInstanceOf(RejectedExecutionException.class, failureOf(result));
            assertEquals(List.of(), stored, "a rejected write never ran");
          } else {
            assertEquals(List.of(1L), stored, "an accepted write that was not cancelled ran");
          }
        } finally {
          race.countDown();
          gate.release();
          if (producer != null) producer.join(5000);
          if (closer != null) closer.join(5000);
          drain(execution);
        }
      }
    }
  }

  @TestFactory
  Stream<DynamicTest> aFailedCancelOfAQueuedWriteNeverSkipsIt() {
    Map<String, Consumer<CompletableFuture<Void>>> interferences = new LinkedHashMap<>();
    interferences.put("complete", result -> result.complete(null));
    interferences.put(
        "completeExceptionally",
        result -> result.completeExceptionally(new IllegalStateException("consumer")));
    interferences.put(
        "orTimeout",
        result -> {
          result.orTimeout(1, TimeUnit.MILLISECONDS);
          assertThrows(ExecutionException.class, () -> result.get(3, TimeUnit.SECONDS));
        });
    return interferences.entrySet().stream()
        .map(interference ->
            dynamicTest(
                interference.getKey(),
                () -> {
                  Path file =
                      file("failed-cancel-" + interference.getKey(), RetentionPolicy.none());
                  Gate gate = new Gate("afterBeginImmediate");
                  BlockingExecution execution = BlockingExecution.singleWorker(1);
                  try (SqliteStorage storage =
                      SqliteStorage.open(file, settings(RetentionPolicy.none()), hooks(gate))) {
                    AsyncEventStore<String, String> store =
                        SqliteEventStore.createOffloaded(storage, config(gate), execution);
                    CompletableFuture<Void> first = store.persistEvent(event(1));
                    gate.awaitEntered();
                    CompletableFuture<Void> queued = store.persistEvent(event(2));

                    interference.getValue().accept(queued);
                    assertFalse(queued.cancel(false));
                    gate.release();

                    get(first);
                    drain(execution);
                  } finally {
                    gate.release();
                    drain(execution);
                  }
                  assertEquals(List.of(1L, 2L), longs(file, JOURNAL));
                }));
  }

  @Test
  void anUnrecoverableWorkerFailsAcceptedWorkAndDrainWithoutRunningIt() throws Exception {
    Path file = file("unrecoverable", RetentionPolicy.none());
    AssertionError recovery = new AssertionError("replacement denied");
    Gate gate = new Gate("afterBeginImmediate", new AssertionError("fatal"));
    BlockingExecution execution = UnrecoverableExecution.withoutReplacement(2, recovery);
    try (SqliteStorage storage =
        SqliteStorage.open(file, settings(RetentionPolicy.none()), hooks(gate))) {
      AsyncEventStore<String, String> store =
          SqliteEventStore.createOffloaded(storage, config(gate), execution);
      CompletableFuture<Void> fatal = store.persistEvent(event(1));
      gate.awaitEntered();
      CompletableFuture<Void> second = store.persistEvent(event(AggregateId.of("Order", "2"), 1));
      CompletableFuture<Void> third = store.persistEvent(event(AggregateId.of("Order", "3"), 1));
      execution.close();
      gate.release();

      assertInstanceOf(AssertionError.class, failureOf(fatal));
      assertSame(recovery, failureOf(second));
      assertSame(recovery, failureOf(third));
      assertSame(recovery, failureOf(execution.drained().toCompletableFuture()));
      assertInstanceOf(RejectedExecutionException.class, failureOf(store.persistEvent(event(4))));
    } finally {
      gate.release();
      execution.close();
    }
    assertEquals(List.of(), longs(file, JOURNAL), "no stranded write ran");
  }

  @TestFactory
  Stream<DynamicTest> cancellingAnInFlightWriteNeitherInterruptsNorRollsBackIt() {
    return Stream.of(true, false)
        .map(mayInterrupt ->
            dynamicTest(
                "mayInterruptIfRunning=" + mayInterrupt,
                () -> {
                  Path file = file("in-flight-" + mayInterrupt, RetentionPolicy.none());
                  Gate gate = new Gate("beforeCommit");
                  BlockingExecution execution = BlockingExecution.singleWorker(1);
                  try (SqliteStorage storage =
                      SqliteStorage.open(file, settings(RetentionPolicy.none()), hooks(gate))) {
                    AsyncEventStore<String, String> store =
                        SqliteEventStore.createOffloaded(storage, config(gate), execution);
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
                  assertEquals(List.of(1L), longs(file, JOURNAL));
                }));
  }

  @Test
  void aCallbackOnTheWorkerMayAdmitAnotherOperationWithinCapacity() throws Exception {
    Path file = file("reentrant", RetentionPolicy.none());
    Gate gate = new Gate("beforeCommit");
    BlockingExecution execution = BlockingExecution.singleWorker(1);
    try (SqliteStorage storage =
        SqliteStorage.open(file, settings(RetentionPolicy.none()), hooks(gate))) {
      AsyncEventStore<String, String> store =
          SqliteEventStore.createOffloaded(storage, config(gate), execution);
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
  }

  @TestFactory
  Stream<DynamicTest> failuresKeepTheirClassificationAndLaterAcceptedWorkStillRuns() {
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
                  Path file =
                      file(
                          failure.point + "-" + failure.thrown.getClass().getSimpleName(),
                          RetentionPolicy.none());
                  Gate gate = new Gate(failure.point, failure.thrown);
                  BlockingExecution execution = BlockingExecution.singleWorker(2);
                  try (SqliteStorage storage =
                      SqliteStorage.open(file, settings(RetentionPolicy.none()), hooks(gate))) {
                    AsyncEventStore<String, String> store =
                        SqliteEventStore.createOffloaded(storage, config(gate), execution);
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
                  assertEquals(List.of(1L, 2L), longs(file, JOURNAL));
                }));
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
                  Path file =
                      file(
                          failure.point + "-" + failure.thrown.getClass().getSimpleName(),
                          RetentionPolicy.none());
                  Function<AsyncEventStore<String, String>, CompletableFuture<?>> read =
                      failure.point.equals("beforeReadSnapshot")
                          ? store -> store.getLatestSnapshotById(AID)
                          : store -> store.getEventsByIdSinceSeqNr(AID, 0);
                  Gate gate = new Gate(failure.point, failure.thrown);
                  BlockingExecution execution = BlockingExecution.singleWorker(2);
                  try (SqliteStorage storage =
                      SqliteStorage.open(file, settings(RetentionPolicy.none()), hooks(gate))) {
                    AsyncEventStore<String, String> store =
                        SqliteEventStore.createOffloaded(storage, config(gate), execution);
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
                  assertEquals(List.of(1L, 2L), longs(file, JOURNAL));
                }));
  }

  @Test
  void anErrorDuringRetentionReachesTheCallerAndKeepsTheCommittedAppend() throws Exception {
    Path file = file("retention-error", RetentionPolicy.delete(1));
    Gate gate = new Gate("beforeRetentionDelete", new AssertionError("injected"));
    BlockingExecution execution = BlockingExecution.singleWorker(2);
    try (SqliteStorage storage =
        SqliteStorage.open(file, settings(RetentionPolicy.delete(1)), hooks(gate))) {
      AsyncEventStore<String, String> store =
          SqliteEventStore.createOffloaded(storage, config(gate), execution);
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
    assertEquals(List.of(1L, 2L, 3L), longs(file, JOURNAL));
    assertEquals(List.of(1L, 2L), longs(file, "SELECT seq_nr FROM snapshot_history ORDER BY 1"));
  }

  @TestFactory
  Stream<DynamicTest> aFailingRetentionListenerKeepsTheCommittedAppend() {
    return Stream.<Throwable>of(new IllegalStateException("injected"), new AssertionError("x"))
        .map(thrown ->
            dynamicTest(
                thrown.getClass().getSimpleName(),
                () -> {
                  Path file =
                      file(
                          "listener-" + thrown.getClass().getSimpleName(),
                          RetentionPolicy.delete(1));
                  Gate gate = new Gate("listener", thrown);
                  BlockingExecution execution = BlockingExecution.singleWorker(2);
                  try (SqliteStorage storage =
                      SqliteStorage.open(file, settings(RetentionPolicy.delete(1)))) {
                    AsyncEventStore<String, String> store =
                        SqliteEventStore.createOffloaded(storage, config(gate), execution);
                    get(store.persistEventAndSnapshot(event(1), snapshot(1)));
                    execute(file, FAIL_RETENTION);

                    CompletableFuture<Void> committed =
                        store.persistEventAndSnapshot(event(2), snapshot(2));
                    assertNotSame(Thread.currentThread(), gate.awaitEntered());
                    gate.release();

                    get(committed);
                  } finally {
                    gate.release();
                    drain(execution);
                  }
                  assertEquals(List.of(1L, 2L), longs(file, JOURNAL));
                  assertEquals(
                      List.of(1L, 2L),
                      longs(file, "SELECT seq_nr FROM snapshot_history ORDER BY 1"));
                }));
  }

  @Test
  void allFourOperationsAgainstARealFileAndCloseAfterDrain() throws Exception {
    Path file = file("real", RetentionPolicy.delete(1));
    BlockingExecution execution = BlockingExecution.singleWorker(16);
    SqliteStorage storage = SqliteStorage.open(file, settings(RetentionPolicy.delete(1)));
    AsyncEventStore<String, String> store =
        SqliteEventStore.createOffloaded(storage, config(name -> {}), execution);
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
    storage.close();

    for (CompletableFuture<?> write : admitted) {
      assertTrue(write.isDone() && !write.isCompletedExceptionally());
    }
    assertInstanceOf(OptimisticLockException.class, failureOf(duplicate));
    assertEquals(4, get(latest).get().headSeqNr());
    assertEquals(3, get(latest).get().snapshot().get().seqNr());
    assertEquals(List.of(2L, 3L, 4L), seqNrs(get(since)));
    assertEquals(List.of(1L, 2L, 3L, 4L), longs(file, JOURNAL));
    assertEquals(List.of(3L), longs(file, "SELECT seq_nr FROM snapshot_history"));
    try (SqliteStorage reopened = SqliteStorage.open(file, settings(RetentionPolicy.delete(1)))) {
      EventStore<String, String> sync = SqliteEventStore.create(reopened, config(name -> {}));
      assertEquals(List.of(1L, 2L, 3L, 4L), seqNrs(sync.getEventsByIdSinceSeqNr(AID, 0)));
    }
  }
}
