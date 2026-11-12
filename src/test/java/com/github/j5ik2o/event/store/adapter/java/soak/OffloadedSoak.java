package com.github.j5ik2o.event.store.adapter.java.soak;

import com.github.j5ik2o.event.store.adapter.java.core.AggregateId;
import com.github.j5ik2o.event.store.adapter.java.core.AsyncEventStore;
import com.github.j5ik2o.event.store.adapter.java.core.BlockingExecution;
import com.github.j5ik2o.event.store.adapter.java.core.EventEnvelope;
import com.github.j5ik2o.event.store.adapter.java.core.EventStore;
import com.github.j5ik2o.event.store.adapter.java.core.EventStoreConfig;
import com.github.j5ik2o.event.store.adapter.java.core.JsonPayloadSerializer;
import com.github.j5ik2o.event.store.adapter.java.core.PayloadSerializer;
import com.github.j5ik2o.event.store.adapter.java.core.SnapshotEnvelope;
import com.github.j5ik2o.event.store.adapter.java.core.SnapshotReadResult;
import java.lang.management.ManagementFactory;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Random;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.locks.LockSupport;

/**
 * Drives a mixed load of the four operations through an offloaded store for a fixed time and checks
 * that every accepted operation completes with the result its admission order implies.
 *
 * <p>Each producer owns disjoint aggregates, so the expected head and snapshot of an aggregate at
 * admission are known without sharing state. Opt in with {@code EVENT_STORE_SOAK_SECONDS}.
 */
public final class OffloadedSoak {
  public static final String DURATION_ENV = "EVENT_STORE_SOAK_SECONDS";
  public static final String WORKER_THREAD = "event-store-blocking";

  private static final Duration STALL = Duration.ofSeconds(60);
  private static final int READ_WINDOW = 4;

  private enum Op {
    PERSIST_EVENT,
    PERSIST_EVENT_AND_SNAPSHOT,
    GET_LATEST_SNAPSHOT,
    GET_EVENTS_SINCE
  }

  public static final class Settings {
    final String backend;
    final Duration duration;
    final int producers;
    final int aggregatesPerProducer;
    final long seed;

    public Settings(
        String backend, Duration duration, int producers, int aggregatesPerProducer, long seed) {
      this.backend = backend;
      this.duration = duration;
      this.producers = producers;
      this.aggregatesPerProducer = aggregatesPerProducer;
      this.seed = seed;
    }

    public static Settings fromEnvironment(String backend) {
      long seconds = Long.parseLong(System.getenv(DURATION_ENV));
      return new Settings(backend, Duration.ofSeconds(seconds), 3, 8, 818L);
    }

    /** One caller on the caller-thread factory, to compare the serial service rate. */
    public Settings callerThreadBaseline() {
      Duration shorter =
          duration.compareTo(Duration.ofSeconds(30)) < 0 ? duration : Duration.ofSeconds(30);
      return new Settings(
          backend + "-caller-thread", shorter, 1, producers * aggregatesPerProducer, seed);
    }
  }

  /**
   * Counts serializer calls by thread, to catch any part of an operation that leaves the worker.
   */
  public static final class WorkerProbe {
    final AtomicLong onWorker = new AtomicLong();
    final AtomicLong offWorker = new AtomicLong();

    public EventStoreConfig<String, String> config() {
      return EventStoreConfig.<String, String>builder()
          .payloadSerializer(probe(JsonPayloadSerializer.of(String.class)))
          .snapshotSerializer(probe(JsonPayloadSerializer.of(String.class)))
          .build();
    }

    private PayloadSerializer<String> probe(PayloadSerializer<String> json) {
      return new PayloadSerializer<String>() {
        @Override
        public byte[] serialize(String value) {
          record();
          return json.serialize(value);
        }

        @Override
        public String deserialize(byte[] bytes) {
          record();
          return json.deserialize(bytes);
        }
      };
    }

    private void record() {
      boolean worker = Thread.currentThread().getName().equals(WORKER_THREAD);
      (worker ? onWorker : offWorker).incrementAndGet();
    }
  }

  public static final class Sample {
    final long elapsedMillis;
    final long completed;
    final long rejected;
    final int liveThreads;
    final int workerThreads;
    final long heapUsedAfterGc;
    final String serviceMillis;

    Sample(
        long elapsedMillis,
        long completed,
        long rejected,
        int liveThreads,
        int workerThreads,
        long heapUsedAfterGc,
        String serviceMillis) {
      this.elapsedMillis = elapsedMillis;
      this.completed = completed;
      this.rejected = rejected;
      this.liveThreads = liveThreads;
      this.workerThreads = workerThreads;
      this.heapUsedAfterGc = heapUsedAfterGc;
      this.serviceMillis = serviceMillis;
    }

    @Override
    public String toString() {
      return String.format(
          "t=%ds completed=%d rejected=%d threads=%d workers=%d heapAfterGcMiB=%.1f msPerOp[%s]",
          elapsedMillis / 1000,
          completed,
          rejected,
          liveThreads,
          workerThreads,
          heapUsedAfterGc / 1048576.0,
          serviceMillis);
    }
  }

  public static final class Report {
    public final List<String> anomalies = Collections.synchronizedList(new ArrayList<>());
    public final List<Sample> samples = Collections.synchronizedList(new ArrayList<>());
    final AtomicLongArray accepted = new AtomicLongArray(Op.values().length);
    final AtomicLong rejected = new AtomicLong();
    final AtomicLong completed = new AtomicLong();
    final AtomicLong lastCompletionNanos = new AtomicLong();
    final AtomicLongArray serviceNanos = new AtomicLongArray(Op.values().length);
    final AtomicLongArray serviceCount = new AtomicLongArray(Op.values().length);
    final long[] sampledServiceNanos = new long[Op.values().length];
    final long[] sampledServiceCount = new long[Op.values().length];
    Map<String, Integer> threadsBefore;
    Map<String, Integer> threadsAtLastSample;
    Map<String, Integer> threadsAfter;
    long loadNanos;
    long drainNanos;
    final AtomicLong workerCompleted = new AtomicLong();
    long pendingAtClose;
    boolean drainedAtClose;
    long serializerCallsOnWorker;
    long serializerCallsOffWorker;
    long eventsReadBack;
    int aggregates;
    Sample before;
    Sample after;

    long acceptedTotal() {
      long total = 0;
      for (int i = 0; i < accepted.length(); i++) {
        total += accepted.get(i);
      }
      return total;
    }

    public String summary(Settings settings) {
      StringBuilder out = new StringBuilder();
      double loadSeconds = loadNanos / 1e9;
      out.append(
          String.format(
              "SOAK backend=%s duration=%ds producers=%d aggregates=%d seed=%d%n",
              settings.backend,
              settings.duration.getSeconds(),
              settings.producers,
              aggregates,
              settings.seed));
      out.append(
          String.format(
              "SOAK accepted=%d completed=%d rejected=%d throughput=%.1f ops/s%n",
              acceptedTotal(), completed.get(), rejected.get(), completed.get() / loadSeconds));
      for (Op op : Op.values()) {
        out.append(String.format("SOAK   %s=%d%n", op, accepted.get(op.ordinal())));
      }
      out.append(
          String.format(
              "SOAK load=%.1fs pendingAtClose=%d drainedAtClose=%b drain=%.3fs eventsReadBack=%d%n",
              loadSeconds, pendingAtClose, drainedAtClose, drainNanos / 1e9, eventsReadBack));
      out.append(
          String.format(
              "SOAK serializerCalls onWorker=%d offWorker=%d%n",
              serializerCallsOnWorker, serializerCallsOffWorker));
      for (Op op : Op.values()) {
        long count = serviceCount.get(op.ordinal());
        out.append(
            String.format(
                "SOAK   meanGapMs %s=%.3f%n",
                op, count == 0 ? 0.0 : serviceNanos.get(op.ordinal()) / 1e6 / count));
      }
      out.append("SOAK threads before ").append(threadsBefore).append(System.lineSeparator());
      out.append("SOAK threads last sample ")
          .append(threadsAtLastSample)
          .append(System.lineSeparator());
      out.append("SOAK threads after ").append(threadsAfter).append(System.lineSeparator());
      out.append("SOAK before ").append(before).append(System.lineSeparator());
      synchronized (samples) {
        for (Sample sample : samples) {
          out.append("SOAK sample ").append(sample).append(System.lineSeparator());
        }
      }
      out.append("SOAK after ").append(after).append(System.lineSeparator());
      out.append("SOAK anomalies=").append(anomalies.size());
      synchronized (anomalies) {
        for (String anomaly : anomalies.subList(0, Math.min(20, anomalies.size()))) {
          out.append(System.lineSeparator()).append("SOAK   ").append(anomaly);
        }
      }
      return out.toString();
    }
  }

  private static final class Aggregate {
    final AggregateId id;
    long head;
    long snapshot;

    Aggregate(AggregateId id) {
      this.id = id;
    }
  }

  private interface Check {
    void verify(Object result, Report report);
  }

  private static final class InFlight {
    final CompletableFuture<?> future;
    final Op op;
    final Check check;
    final AtomicLong completionTicket = new AtomicLong();
    CompletableFuture<?> observed;

    InFlight(CompletableFuture<?> future, Op op, Check check) {
      this.future = future;
      this.op = op;
      this.check = check;
    }

    void observe(Report report) {
      // While the queue stays full, the gap between consecutive completions on the single worker
      // is the service time of the operation that just completed.
      this.observed =
          future.whenComplete((value, failure) -> {
            completionTicket.set(TICKETS.incrementAndGet());
            report.workerCompleted.incrementAndGet();
            long now = System.nanoTime();
            long previous = report.lastCompletionNanos.getAndSet(now);
            if (previous != 0) {
              report.serviceNanos.addAndGet(op.ordinal(), now - previous);
              report.serviceCount.incrementAndGet(op.ordinal());
            }
          });
    }
  }

  private static final AtomicLong TICKETS = new AtomicLong();

  private OffloadedSoak() {}

  /**
   * Runs the load, closes {@code execution} at the deadline while the producers still fill its
   * queue, settles and drains every accepted operation, and reads every aggregate back through
   * {@code readback}. Pass null for {@code execution} and {@code probe} on the caller-thread
   * factory.
   */
  public static Report run(
      Settings settings,
      AsyncEventStore<String, String> store,
      BlockingExecution execution,
      WorkerProbe probe,
      EventStore<String, String> readback)
      throws InterruptedException {
    Report report = new Report();
    report.before = sample(0, report);
    report.threadsBefore = threadGroups();
    long start = System.nanoTime();
    long deadline = start + settings.duration.toNanos();
    List<List<Aggregate>> owned = new ArrayList<>();
    List<Thread> producers = new ArrayList<>();
    CountDownLatch stoppedSubmitting = new CountDownLatch(settings.producers);
    AtomicBoolean stop = new AtomicBoolean();
    for (int p = 0; p < settings.producers; p++) {
      List<Aggregate> aggregates = new ArrayList<>();
      for (int a = 0; a < settings.aggregatesPerProducer; a++) {
        aggregates.add(
            new Aggregate(
                AggregateId.of("Soak", settings.backend + "-" + start + "-" + p + "-" + a)));
      }
      owned.add(aggregates);
      Random random = new Random(settings.seed + p);
      Thread producer =
          new Thread(
              () -> produce(store, aggregates, random, stop, stoppedSubmitting, report),
              "soak-producer-" + p);
      producers.add(producer);
    }
    report.aggregates = settings.producers * settings.aggregatesPerProducer;
    Thread sampler =
        new Thread(() -> sampleUntil(deadline, start, settings, report), "soak-sampler");
    sampler.setDaemon(true);
    sampler.start();
    producers.forEach(Thread::start);
    TimeUnit.NANOSECONDS.sleep(deadline - System.nanoTime());
    sampler.interrupt();
    sampler.join(STALL.toMillis());
    long drainStart = System.nanoTime();
    if (execution != null) {
      report.pendingAtClose = report.acceptedTotal() - report.workerCompleted.get();
      execution.close();
      report.drainedAtClose = execution.drained().toCompletableFuture().isDone();
      TimeUnit.MILLISECONDS.sleep(100);
    }
    stop.set(true);
    if (!stoppedSubmitting.await(STALL.toMillis(), TimeUnit.MILLISECONDS)) {
      report.anomalies.add("producers did not stop submitting: " + workerStacks());
    }
    report.loadNanos = System.nanoTime() - start;
    for (Thread producer : producers) {
      producer.join(STALL.plus(STALL).toMillis());
      if (producer.isAlive()) {
        report.anomalies.add(producer.getName() + " did not finish: " + workerStacks());
      }
    }
    if (execution != null) {
      try {
        execution.drained().toCompletableFuture().get(STALL.getSeconds(), TimeUnit.SECONDS);
      } catch (ExecutionException | TimeoutException e) {
        report.anomalies.add("drain did not complete: " + e + " " + workerStacks());
      }
      report.drainNanos = System.nanoTime() - drainStart;
      CompletableFuture<Void> late = store.persistEvent(event(owned.get(0).get(0), 1));
      if (!late.isCompletedExceptionally() || !rejectedNow(late)) {
        report.anomalies.add("closed execution accepted an operation");
      }
      awaitNoWorker(report);
    }

    for (List<Aggregate> aggregates : owned) {
      for (Aggregate aggregate : aggregates) {
        readBack(readback, aggregate, report);
      }
    }
    if (probe != null) {
      report.serializerCallsOnWorker = probe.onWorker.get();
      report.serializerCallsOffWorker = probe.offWorker.get();
    }
    report.after = sample(System.nanoTime() - start, report);
    report.threadsAfter = threadGroups();
    return report;
  }

  /**
   * Fails on any anomaly, on an accepted operation that did not complete, when the queue never
   * filled or was empty at close, when any serializer call left the worker, when a worker outlives
   * drain, or when threads or the post-GC heap keep growing.
   */
  public static void assertHealthy(Report report, boolean offloaded) {
    if (offloaded && report.before.workerThreads != 1) {
      throw new AssertionError(
          "expected only this soak's worker before the load, found " + report.before.workerThreads);
    }
    if (!report.anomalies.isEmpty()) {
      throw new AssertionError(
          "anomalies: " + report.anomalies.size() + " first " + report.anomalies.get(0));
    }
    if (report.acceptedTotal() != report.completed.get()) {
      throw new AssertionError(
          "accepted " + report.acceptedTotal() + " but completed " + report.completed.get());
    }
    if (report.acceptedTotal() == 0) {
      throw new AssertionError("no operation was accepted");
    }
    if (!offloaded) {
      return;
    }
    if (report.rejected.get() == 0) {
      throw new AssertionError("the queue never filled");
    }
    if (report.pendingAtClose == 0 || report.drainedAtClose) {
      throw new AssertionError(
          "close found no pending work: pending "
              + report.pendingAtClose
              + " drained "
              + report.drainedAtClose);
    }
    if (report.serializerCallsOffWorker != 0 || report.serializerCallsOnWorker == 0) {
      throw new AssertionError(
          "serializer calls off the worker "
              + report.serializerCallsOffWorker
              + ", on it "
              + report.serializerCallsOnWorker);
    }
    if (report.after.workerThreads != 0) {
      throw new AssertionError("worker alive after drain");
    }
    List<Sample> samples;
    synchronized (report.samples) {
      samples = new ArrayList<>(report.samples);
    }
    if (samples.size() < 4) {
      return;
    }
    List<Sample> early = samples.subList(0, samples.size() / 2);
    List<Sample> late = samples.subList(samples.size() / 2, samples.size());
    if (samples.stream().anyMatch(s -> s.workerThreads != 1)) {
      throw new AssertionError("worker count left 1 during the load");
    }
    // Median, not max: SDK cached pools of other clients in the JVM spike for one sample and
    // shrink.
    int earlyThreads = medianThreads(early);
    int lateThreads = medianThreads(late);
    if (lateThreads > earlyThreads + 2) {
      throw new AssertionError("threads grew from " + earlyThreads + " to " + lateThreads);
    }
    long earlyHeap = early.stream().mapToLong(s -> s.heapUsedAfterGc).max().getAsLong();
    long lateHeap = late.stream().mapToLong(s -> s.heapUsedAfterGc).min().getAsLong();
    if (lateHeap > earlyHeap * 2 + 32L * 1048576) {
      throw new AssertionError("post-GC heap grew from " + earlyHeap + " to " + lateHeap);
    }
  }

  private static int medianThreads(List<Sample> samples) {
    int[] counts = samples.stream().mapToInt(s -> s.liveThreads).sorted().toArray();
    return counts[counts.length / 2];
  }

  private static void produce(
      AsyncEventStore<String, String> store,
      List<Aggregate> aggregates,
      Random random,
      AtomicBoolean stop,
      CountDownLatch stoppedSubmitting,
      Report report) {
    ArrayDeque<InFlight> inFlight = new ArrayDeque<>();
    long lastTicket = 0;
    boolean submitting = true;
    try {
      while (!stop.get()) {
        lastTicket = settleDone(inFlight, lastTicket, report);
        Aggregate aggregate = aggregates.get(random.nextInt(aggregates.size()));
        Op op = choose(random);
        InFlight submitted = submit(store, aggregate, op, report);
        if (rejectedNow(submitted.future)) {
          report.rejected.incrementAndGet();
          if (inFlight.isEmpty()) {
            LockSupport.parkNanos(TimeUnit.MICROSECONDS.toNanos(200));
          } else {
            lastTicket = settle(inFlight.removeFirst(), lastTicket, report);
          }
          continue;
        }
        submitted.observe(report);
        report.accepted.incrementAndGet(op.ordinal());
        advance(aggregate, op);
        inFlight.addLast(submitted);
      }
      submitting = false;
      stoppedSubmitting.countDown();
      while (!inFlight.isEmpty()) {
        lastTicket = settle(inFlight.removeFirst(), lastTicket, report);
      }
    } catch (Throwable failure) {
      if (submitting) {
        stoppedSubmitting.countDown();
      }
      report.anomalies.add(Thread.currentThread().getName() + " failed: " + failure);
    }
  }

  private static long settleDone(ArrayDeque<InFlight> inFlight, long lastTicket, Report report) {
    while (!inFlight.isEmpty() && inFlight.peekFirst().observed.isDone()) {
      lastTicket = settle(inFlight.removeFirst(), lastTicket, report);
    }
    return lastTicket;
  }

  private static long settle(InFlight entry, long lastTicket, Report report) {
    Object result;
    try {
      result = entry.observed.get(STALL.getSeconds(), TimeUnit.SECONDS);
    } catch (TimeoutException e) {
      throw new IllegalStateException("stall: no completion in " + STALL + " " + workerStacks());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    } catch (ExecutionException e) {
      report.anomalies.add("accepted operation failed: " + e.getCause());
      return lastTicket;
    }
    report.completed.incrementAndGet();
    entry.check.verify(result, report);
    long ticket = entry.completionTicket.get();
    if (ticket <= lastTicket) {
      report.anomalies.add("completion out of admission order: " + ticket + " after " + lastTicket);
    }
    return ticket;
  }

  private static Op choose(Random random) {
    int roll = random.nextInt(100);
    if (roll < 40) {
      return Op.PERSIST_EVENT;
    }
    if (roll < 55) {
      return Op.PERSIST_EVENT_AND_SNAPSHOT;
    }
    if (roll < 75) {
      return Op.GET_LATEST_SNAPSHOT;
    }
    return Op.GET_EVENTS_SINCE;
  }

  private static InFlight submit(
      AsyncEventStore<String, String> store, Aggregate aggregate, Op op, Report report) {
    long head = aggregate.head;
    long snapshot = aggregate.snapshot;
    AggregateId id = aggregate.id;
    CompletableFuture<?> future;
    Check check;
    switch (op) {
      case PERSIST_EVENT:
        future = store.persistEvent(event(aggregate, head + 1));
        check = (result, into) -> {};
        break;
      case PERSIST_EVENT_AND_SNAPSHOT:
        future =
            store.persistEventAndSnapshot(event(aggregate, head + 1), snap(aggregate, head + 1));
        check = (result, into) -> {};
        break;
      case GET_LATEST_SNAPSHOT:
        future = store.getLatestSnapshotById(id);
        check = (result, into) -> checkSnapshot(id, head, snapshot, result, into);
        break;
      case GET_EVENTS_SINCE:
        long since = Math.max(1, head - READ_WINDOW);
        future = store.getEventsByIdSinceSeqNr(id, since);
        check = (result, into) -> checkEvents(id, since, head, result, into);
        break;
      default:
        throw new IllegalStateException(op.name());
    }
    return new InFlight(future, op, check);
  }

  private static void advance(Aggregate aggregate, Op op) {
    if (op == Op.PERSIST_EVENT) {
      aggregate.head++;
    } else if (op == Op.PERSIST_EVENT_AND_SNAPSHOT) {
      aggregate.head++;
      aggregate.snapshot = aggregate.head;
    }
  }

  private static void checkSnapshot(
      AggregateId id, long head, long snapshot, Object result, Report report) {
    @SuppressWarnings("unchecked")
    Optional<SnapshotReadResult<String>> read = (Optional<SnapshotReadResult<String>>) result;
    if (head == 0) {
      if (read.isPresent()) {
        report.anomalies.add(id.asString() + " read a head before any write");
      }
      return;
    }
    if (!read.isPresent() || read.get().headSeqNr() != head) {
      report.anomalies.add(
          id.asString() + " head expected " + head + " actual " + read.map(r -> r.headSeqNr()));
      return;
    }
    Optional<SnapshotEnvelope<String>> envelope = read.get().snapshot();
    long actual = envelope.map(SnapshotEnvelope::seqNr).orElse(0L);
    String expectedAggregate = snapshot == 0 ? null : snapshotValue(id, snapshot);
    String actualAggregate = envelope.map(SnapshotEnvelope::aggregate).orElse(null);
    if (actual != snapshot || !Objects.equals(expectedAggregate, actualAggregate)) {
      report.anomalies.add(
          id.asString()
              + " snapshot expected "
              + snapshot
              + " actual "
              + actual
              + "/"
              + actualAggregate);
    }
  }

  private static void checkEvents(
      AggregateId id, long since, long head, Object result, Report report) {
    @SuppressWarnings("unchecked")
    List<EventEnvelope<String>> events = (List<EventEnvelope<String>>) result;
    long expectedCount = head == 0 ? 0 : head - since + 1;
    if (events.size() != expectedCount) {
      report.anomalies.add(
          id.asString()
              + " since "
              + since
              + " expected "
              + expectedCount
              + " got "
              + events.size());
      return;
    }
    for (int i = 0; i < events.size(); i++) {
      EventEnvelope<String> event = events.get(i);
      long seqNr = since + i;
      if (event.seqNr() != seqNr || !eventValue(id, seqNr).equals(event.payload())) {
        report.anomalies.add(
            id.asString()
                + " event "
                + seqNr
                + " read as "
                + event.seqNr()
                + "/"
                + event.payload());
        return;
      }
    }
  }

  private static void readBack(
      EventStore<String, String> readback, Aggregate aggregate, Report report) {
    List<EventEnvelope<String>> events = readback.getEventsByIdSinceSeqNr(aggregate.id, 1);
    checkEvents(aggregate.id, 1, aggregate.head, events, report);
    report.eventsReadBack += events.size();
    checkSnapshot(
        aggregate.id,
        aggregate.head,
        aggregate.snapshot,
        readback.getLatestSnapshotById(aggregate.id),
        report);
  }

  private static boolean rejectedNow(CompletableFuture<?> future) {
    if (!future.isCompletedExceptionally()) {
      return false;
    }
    try {
      future.getNow(null);
      return false;
    } catch (Exception e) {
      return e.getCause() instanceof RejectedExecutionException && fromAdmission(e.getCause());
    }
  }

  /** A storage may also throw RejectedExecutionException (#829); only admission is a rejection. */
  private static boolean fromAdmission(Throwable rejection) {
    for (StackTraceElement frame : rejection.getStackTrace()) {
      if (frame.getClassName().equals(BlockingExecution.class.getName())
          && frame.getMethodName().equals("submit")) {
        return true;
      }
    }
    return false;
  }

  private static EventEnvelope<String> event(Aggregate aggregate, long seqNr) {
    return EventEnvelope.<String>builder()
        .aggregateId(aggregate.id)
        .seqNr(seqNr)
        .occurredAt(Instant.EPOCH.plusSeconds(seqNr))
        .manifest("event")
        .payload(eventValue(aggregate.id, seqNr))
        .build();
  }

  private static SnapshotEnvelope<String> snap(Aggregate aggregate, long seqNr) {
    return SnapshotEnvelope.<String>builder()
        .seqNr(seqNr)
        .manifest("snapshot")
        .aggregate(snapshotValue(aggregate.id, seqNr))
        .build();
  }

  private static String eventValue(AggregateId id, long seqNr) {
    return id.asString() + "/event/" + seqNr;
  }

  private static String snapshotValue(AggregateId id, long seqNr) {
    return id.asString() + "/snapshot/" + seqNr;
  }

  private static void sampleUntil(long deadline, long start, Settings settings, Report report) {
    long every = Math.max(1000, Math.min(60_000, settings.duration.toMillis() / 10));
    try {
      while (System.nanoTime() < deadline) {
        Thread.sleep(every);
        report.samples.add(sample(System.nanoTime() - start, report));
        report.threadsAtLastSample = threadGroups();
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  private static Sample sample(long elapsedNanos, Report report) {
    System.gc();
    long heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
    StringBuilder service = new StringBuilder();
    for (Op op : Op.values()) {
      int i = op.ordinal();
      long nanos = report.serviceNanos.get(i);
      long count = report.serviceCount.get(i);
      long deltaCount = count - report.sampledServiceCount[i];
      double mean =
          deltaCount == 0 ? 0 : (nanos - report.sampledServiceNanos[i]) / 1e6 / deltaCount;
      report.sampledServiceNanos[i] = nanos;
      report.sampledServiceCount[i] = count;
      service.append(service.length() == 0 ? "" : " ").append(String.format("%.2f", mean));
    }
    return new Sample(
        TimeUnit.NANOSECONDS.toMillis(elapsedNanos),
        report.completed.get(),
        report.rejected.get(),
        ManagementFactory.getThreadMXBean().getThreadCount(),
        workerThreads().size(),
        heap,
        service.toString());
  }

  private static Map<String, Integer> threadGroups() {
    Map<String, Integer> groups = new TreeMap<>();
    for (Thread thread : Thread.getAllStackTraces().keySet()) {
      groups.merge(thread.getName().replaceAll("[0-9]+", "#"), 1, Integer::sum);
    }
    return groups;
  }

  private static List<Thread> workerThreads() {
    List<Thread> workers = new ArrayList<>();
    for (Thread thread : Thread.getAllStackTraces().keySet()) {
      if (thread.getName().equals(WORKER_THREAD) && thread.isAlive()) {
        workers.add(thread);
      }
    }
    return workers;
  }

  private static void awaitNoWorker(Report report) throws InterruptedException {
    long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (System.nanoTime() < until) {
      if (workerThreads().isEmpty()) {
        return;
      }
      Thread.sleep(50);
    }
    report.anomalies.add("worker thread still alive after drain: " + workerStacks());
  }

  private static String workerStacks() {
    StringBuilder out = new StringBuilder();
    for (Map.Entry<Thread, StackTraceElement[]> entry : Thread.getAllStackTraces().entrySet()) {
      if (entry.getKey().getName().equals(WORKER_THREAD)) {
        out.append(entry.getKey()).append(' ').append(entry.getKey().getState());
        for (StackTraceElement frame : entry.getValue()) {
          out.append("\n    at ").append(frame);
        }
      }
    }
    return out.toString();
  }
}
