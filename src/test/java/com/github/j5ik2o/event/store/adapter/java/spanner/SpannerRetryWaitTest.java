package com.github.j5ik2o.event.store.adapter.java.spanner;

import static org.junit.jupiter.api.Assertions.*;

import com.github.j5ik2o.event.store.adapter.java.core.AggregateId;
import com.github.j5ik2o.event.store.adapter.java.core.EventEnvelope;
import com.github.j5ik2o.event.store.adapter.java.core.EventStoreConfig;
import com.github.j5ik2o.event.store.adapter.java.core.JsonPayloadSerializer;
import com.github.j5ik2o.event.store.adapter.java.core.OptimisticLockException;
import com.github.j5ik2o.event.store.adapter.java.core.StorageException;
import com.google.cloud.spanner.*;
import com.google.protobuf.Any;
import com.google.protobuf.Duration;
import com.google.rpc.RetryInfo;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.protobuf.StatusProto;
import java.lang.reflect.Constructor;
import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class SpannerRetryWaitTest {
  static final AggregateId ID = AggregateId.of("Order", "1");
  static final Metadata.Key<byte[]> KEY =
      Metadata.Key.of("google.rpc.retryinfo-bin", Metadata.BINARY_BYTE_MARSHALLER);
  final List<String> order = new ArrayList<>();
  final List<Long> sleeps = new ArrayList<>();
  final AtomicLong clock = new AtomicLong();
  int managers;
  int bodies;
  int commits;
  boolean closed;
  RuntimeException closeFailure;
  Runnable onClose = () -> {};
  Runnable onCommit = () -> {};
  SpannerRetryWait wait =
      new SpannerRetryWait(
          clock::get,
          ms -> {
            assertTrue(closed);
            assertTrue(ms >= 1 && ms <= 60000);
            sleeps.add(ms);
            order.add("sleep");
            clock.addAndGet(ms * 1000000);
          },
          () -> 0);

  @AfterEach
  void clearInterrupt() {
    Thread.interrupted();
  }

  @SuppressWarnings("unchecked")
  static <T> T proxy(Class<T> type, java.lang.reflect.InvocationHandler handler) {
    return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, handler);
  }

  SpannerStorage storage(int limit, SpannerStorageHooks hooks) throws Exception {
    DatabaseClient client =
        proxy(
            DatabaseClient.class,
            (p, method, args) -> {
              if (!method.getName().equals("transactionManager")) throw new AssertionError(method);
              managers++;
              closed = false;
              order.add("manager");
              return proxy(
                  TransactionManager.class,
                  (m, operation, a) -> {
                    switch (operation.getName()) {
                      case "begin":
                        order.add("begin");
                        return proxy(
                            TransactionContext.class,
                            (t, op, values) -> {
                              throw new AssertionError(op);
                            });
                      case "commit":
                        commits++;
                        order.add("commit");
                        onCommit.run();
                        return null;
                      case "close":
                        closed = true;
                        order.add("close");
                        onClose.run();
                        if (closeFailure != null) throw closeFailure;
                        return null;
                      default:
                        throw new AssertionError(operation);
                    }
                  });
            });
    Constructor<SpannerStorage> constructor =
        SpannerStorage.class.getDeclaredConstructor(
            DatabaseClient.class, SpannerStorageConfig.class, SpannerStorageHooks.class);
    constructor.setAccessible(true);
    return constructor.newInstance(
        client,
        SpannerStorageConfig.builder().tablePrefix("test").transactionRetryLimit(limit).build(),
        hooks);
  }

  <T> T run(int limit, SpannerStorageHooks hooks, SpannerTransactions.Body<T> body)
      throws Exception {
    return SpannerTransactions.runReadWrite(
        storage(limit, hooks),
        ID,
        "append",
        t -> {
          bodies++;
          order.add("body");
          return body.run(t);
        },
        wait);
  }

  static SpannerException abort() {
    return SpannerExceptionFactory.newSpannerException(ErrorCode.ABORTED, "abort");
  }

  static RetryInfo hint(long seconds, int nanos) {
    return RetryInfo.newBuilder()
        .setRetryDelay(Duration.newBuilder().setSeconds(seconds).setNanos(nanos))
        .build();
  }

  static SpannerException raw(byte[]... entries) {
    Metadata metadata = new Metadata();
    for (byte[] entry : entries) metadata.put(KEY, entry);
    return SpannerExceptionFactory.newSpannerException(
        ErrorCode.ABORTED, "abort", Status.ABORTED.asRuntimeException(metadata));
  }

  static SpannerException structured(RetryInfo info) {
    return SpannerExceptionFactory.newSpannerException(
        StatusProto.toStatusRuntimeException(
            com.google.rpc.Status.newBuilder().setCode(10).addDetails(Any.pack(info)).build()));
  }

  @Test
  void limitsAndLaterSuccess() throws Exception {
    for (int limit : new int[] {0, 1, 5}) {
      managers = bodies = 0;
      sleeps.clear();
      order.clear();
      SpannerException abort = abort();
      StorageException error =
          assertThrows(
              StorageException.class,
              () ->
                  run(
                      limit,
                      SpannerStorageHooks.NONE,
                      t -> {
                        throw abort;
                      }));
      assertSame(abort, error.getCause());
      assertEquals(limit + 1, managers);
      assertEquals(managers, bodies);
      assertEquals(limit, sleeps.size());
      managers = bodies = 0;
      sleeps.clear();
      assertEquals(
          "ok",
          run(
              limit,
              SpannerStorageHooks.NONE,
              t -> {
                if (bodies <= limit) throw abort;
                return "ok";
              }));
      assertEquals(limit + 1, managers);
      assertEquals(limit, sleeps.size());
    }
  }

  @Test
  void fallbackEndpoints() {
    int[] caps = {50, 100, 200, 400, 800, 1000, 1000};
    int[] ordinals = {1, 2, 3, 4, 5, 6, Integer.MAX_VALUE};
    for (int i = 0; i < caps.length; i++) {
      assertEquals(caps[i] / 2, wait.fallbackMillis(ordinals[i]));
      assertEquals(
          caps[i],
          new SpannerRetryWait(clock::get, ms -> {}, () -> Math.nextDown(1.0))
              .fallbackMillis(ordinals[i]));
    }
  }

  @Test
  void realSdkEncodedStatusAndRawTrailersReachNormalEntry() throws Exception {
    for (SpannerException abort :
        List.of(structured(hint(0, 1000001)), raw(hint(0, 1000001).toByteArray()))) {
      assertEquals(2, SpannerRetryWait.serverMillis(abort));
      int before = managers;
      SpannerStorage storage = storage(1, SpannerStorageHooks.NONE);
      AtomicLong attempts = new AtomicLong();
      assertEquals(
          "ok",
          SpannerTransactions.runReadWrite(
              storage,
              ID,
              "append",
              t -> {
                if (attempts.getAndIncrement() == 0) throw abort;
                return "ok";
              }));
      assertEquals(before + 2, managers);
    }
    assertNotNull(structured(hint(1, 1)).getErrorDetails().getRetryInfo());
  }

  @Test
  void durationBoundariesAndWireOrder() {
    long[][] cases = {
      {0, 0, 1},
      {0, 1, 1},
      {0, 1000000, 1},
      {0, 1000001, 2},
      {1, 1, 1001},
      {60, 0, 60000},
      {60, 1, 60001},
      {315576000000L, 999999999, 315576000001000L},
      {-1, 0, -1},
      {1, -1, -1},
      {0, 1000000000, -1},
      {315576000001L, 0, -1}
    };
    for (long[] c : cases) {
      assertEquals(c[2], SpannerRetryWait.serverMillis(raw(hint(c[0], (int) c[1]).toByteArray())));
      assertEquals(c[2], SpannerRetryWait.serverMillis(structured(hint(c[0], (int) c[1]))));
    }
    assertEquals(-1, SpannerRetryWait.serverMillis(abort()));
    assertEquals(-1, SpannerRetryWait.serverMillis(raw(new byte[] {(byte) 255})));
    assertEquals(
        -1, SpannerRetryWait.serverMillis(raw(RetryInfo.getDefaultInstance().toByteArray())));
    assertEquals(
        61000,
        SpannerRetryWait.serverMillis(
            raw(
                new byte[] {(byte) 255},
                hint(61, 0).toByteArray(),
                hint(0, 10000000).toByteArray())));
    assertEquals(
        1,
        SpannerRetryWait.serverMillis(
            raw(RetryInfo.getDefaultInstance().toByteArray(), hint(0, 0).toByteArray())));
  }

  @Test
  void longHintStopsAndExhaustionPrecedesDecision() throws Exception {
    SpannerException abort = raw(hint(61, 0).toByteArray());
    StorageException error =
        assertThrows(
            StorageException.class,
            () ->
                run(
                    5,
                    SpannerStorageHooks.NONE,
                    t -> {
                      throw abort;
                    }));
    assertSame(abort, error.getCause());
    assertTrue(error.getMessage().contains("server retry delay"));
    assertTrue(sleeps.isEmpty());
    assertEquals(1, managers);
    error =
        assertThrows(
            StorageException.class,
            () ->
                run(
                    0,
                    SpannerStorageHooks.NONE,
                    t -> {
                      throw abort;
                    }));
    assertTrue(error.getMessage().contains("retry limit"));
    wait =
        new SpannerRetryWait(
            () -> {
              throw new AssertionError();
            },
            ms -> {
              throw new AssertionError();
            },
            () -> {
              throw new AssertionError();
            });
    assertThrows(
        StorageException.class,
        () ->
            run(
                0,
                SpannerStorageHooks.NONE,
                t -> {
                  throw abort();
                }));
  }

  @Test
  void earlyWakeWaitsForRoundedMonotonicRemainder() throws Exception {
    wait =
        new SpannerRetryWait(
            clock::get,
            ms -> {
              assertTrue(closed);
              assertEquals(1, managers);
              assertEquals(1, bodies);
              sleeps.add(ms);
              clock.addAndGet(sleeps.size() == 1 ? 1000001 : ms * 1000000);
            },
            () -> 0);
    assertEquals(
        "ok",
        run(
            1,
            SpannerStorageHooks.NONE,
            t -> {
              if (bodies == 1) throw raw(hint(0, 10000001).toByteArray());
              return "ok";
            }));
    assertEquals(List.of(11L, 10L), sleeps);
    assertEquals(2, managers);
  }

  @Test
  void errorsPreserveClassificationAndSuppression() throws Exception {
    RuntimeException ordinary = new IllegalStateException("body");
    RuntimeException cleanup = new IllegalArgumentException("close");
    closeFailure = cleanup;
    assertSame(
        ordinary,
        assertThrows(
            IllegalStateException.class,
            () ->
                run(
                    5,
                    SpannerStorageHooks.NONE,
                    t -> {
                      throw ordinary;
                    })));
    assertArrayEquals(new Throwable[] {cleanup}, ordinary.getSuppressed());
    closeFailure = null;
    OptimisticLockException domain = new OptimisticLockException(ID, 1, OptionalLong.of(1), null);
    assertSame(
        domain,
        assertThrows(
            OptimisticLockException.class,
            () ->
                run(
                    5,
                    SpannerStorageHooks.NONE,
                    t -> {
                      throw domain;
                    })));
    SpannerException unavailable =
        SpannerExceptionFactory.newSpannerException(ErrorCode.UNAVAILABLE, "unavailable");
    assertSame(
        unavailable,
        assertThrows(
                StorageException.class,
                () ->
                    run(
                        5,
                        SpannerStorageHooks.NONE,
                        t -> {
                          throw unavailable;
                        }))
            .getCause());
    assertTrue(sleeps.isEmpty());
  }

  @Test
  void hookCloseOrderAndSuccessEffectOnce() throws Exception {
    SpannerStorageHooks hooks =
        new SpannerStorageHooks() {
          public void beforeCommit(AggregateId id) {
            order.add("before");
            if (bodies == 1) throw abort();
          }

          public void afterCommit(AggregateId id) {
            assertTrue(closed);
            order.add("after");
          }
        };
    assertEquals("ok", run(5, hooks, t -> "ok"));
    assertEquals(
        List.of(
            "manager", "begin", "body", "before", "close", "sleep", "manager", "begin", "body",
            "before", "commit", "close", "after"),
        order);
  }

  @Test
  void ordinaryHookAndCloseFailuresAndAfterCommitAbortDoNotRetry() throws Exception {
    RuntimeException ordinary = new IllegalArgumentException("ordinary");
    SpannerStorageHooks hooks =
        new SpannerStorageHooks() {
          public void beforeCommit(AggregateId id) {
            throw ordinary;
          }
        };
    assertSame(
        ordinary, assertThrows(IllegalArgumentException.class, () -> run(5, hooks, t -> "ok")));
    closeFailure = ordinary;
    assertSame(
        ordinary,
        assertThrows(
            IllegalArgumentException.class, () -> run(5, SpannerStorageHooks.NONE, t -> "ok")));
    closeFailure = null;
    SpannerException abort = abort();
    final SpannerStorageHooks after =
        new SpannerStorageHooks() {
          public void afterCommit(AggregateId id) {
            throw abort;
          }
        };
    StorageException failure = assertThrows(StorageException.class, () -> run(5, after, t -> "ok"));
    assertTrue(failure.getMessage().contains("outcome is unknown"));
    assertSame(abort, failure.getCause());
    assertTrue(sleeps.isEmpty());
    assertEquals(3, managers);
  }

  @Test
  void interruptionBeforeInitialAttemptAndCleanupAndSleep() throws Exception {
    Thread.currentThread().interrupt();
    StorageException error =
        assertThrows(StorageException.class, () -> run(5, SpannerStorageHooks.NONE, t -> "ok"));
    assertInstanceOf(InterruptedException.class, error.getCause());
    assertEquals(0, managers);
    assertTrue(Thread.interrupted());
    SpannerException abort = abort();
    onClose = () -> Thread.currentThread().interrupt();
    error =
        assertThrows(
            StorageException.class,
            () ->
                run(
                    5,
                    SpannerStorageHooks.NONE,
                    t -> {
                      throw abort;
                    }));
    assertArrayEquals(new Throwable[] {abort}, error.getSuppressed());
    assertTrue(Thread.interrupted());
    assertTrue(sleeps.isEmpty());
    assertEquals(1, managers);
    onClose = () -> {};
    InterruptedException interrupted = new InterruptedException("sleep");
    wait =
        new SpannerRetryWait(
            clock::get,
            ms -> {
              throw interrupted;
            },
            () -> 0);
    error =
        assertThrows(
            StorageException.class,
            () ->
                run(
                    5,
                    SpannerStorageHooks.NONE,
                    t -> {
                      throw abort;
                    }));
    assertSame(interrupted, error.getCause());
    assertArrayEquals(new Throwable[] {abort}, error.getSuppressed());
    assertTrue(Thread.interrupted());
    assertEquals(2, managers);
    wait = new SpannerRetryWait(clock::get, ms -> Thread.currentThread().interrupt(), () -> 0);
    assertThrows(
        StorageException.class,
        () ->
            run(
                5,
                SpannerStorageHooks.NONE,
                t -> {
                  throw abort;
                }));
    assertTrue(Thread.interrupted());
    assertEquals(3, managers);
  }

  @Test
  void interruptDuringSuccessfulCommitFinishesAfterCommit() throws Exception {
    onCommit = () -> Thread.currentThread().interrupt();
    SpannerStorageHooks hooks =
        new SpannerStorageHooks() {
          public void afterCommit(AggregateId id) {
            order.add("after");
          }
        };
    assertEquals("ok", run(5, hooks, t -> "ok"));
    assertTrue(Thread.currentThread().isInterrupted());
    assertEquals("after", order.get(order.size() - 1));
    assertEquals(1, commits);
  }

  @Test
  void publicAppendReusesEnvelopeAndRechecksAdvancedHead() throws Exception {
    List<Statement> reads = new ArrayList<>();
    List<Statement> writes = new ArrayList<>();
    DatabaseClient client =
        proxy(
            DatabaseClient.class,
            (p, method, args) -> {
              assertEquals("transactionManager", method.getName());
              managers++;
              TransactionContext tx =
                  proxy(
                      TransactionContext.class,
                      (t, op, values) -> {
                        if (op.getName().equals("executeQuery")) {
                          reads.add((Statement) values[0]);
                          return ResultSets.forRows(
                              Type.struct(Type.StructField.of("seq_nr", Type.int64())),
                              managers == 1
                                  ? List.of()
                                  : List.of(Struct.newBuilder().set("seq_nr").to(1L).build()));
                        }
                        if (op.getName().equals("executeUpdate")) {
                          writes.add((Statement) values[0]);
                          return 1L;
                        }
                        throw new AssertionError(op);
                      });
              return proxy(
                  TransactionManager.class,
                  (m, op, values) -> {
                    if (op.getName().equals("begin")) return tx;
                    if (op.getName().equals("close")) return null;
                    throw new AssertionError(op);
                  });
            });
    SpannerStorageHooks hooks =
        new SpannerStorageHooks() {
          public void beforeCommit(AggregateId id) {
            throw abort();
          }

          public void afterCommit(AggregateId id) {
            fail("no successful commit");
          }
        };
    Constructor<SpannerStorage> ctor =
        SpannerStorage.class.getDeclaredConstructor(
            DatabaseClient.class, SpannerStorageConfig.class, SpannerStorageHooks.class);
    ctor.setAccessible(true);
    SpannerStorage storage =
        ctor.newInstance(client, SpannerStorageConfig.builder().tablePrefix("test").build(), hooks);
    EventEnvelope<String> envelope =
        EventEnvelope.<String>builder()
            .aggregateId(ID)
            .seqNr(1)
            .occurredAt(Instant.EPOCH)
            .manifest("event")
            .payload("one")
            .build();
    assertThrows(
        OptimisticLockException.class,
        () ->
            SpannerEventStore.create(
                    storage,
                    EventStoreConfig.<String, String>builder()
                        .payloadSerializer(JsonPayloadSerializer.of(String.class))
                        .snapshotSerializer(JsonPayloadSerializer.of(String.class))
                        .build())
                .persistEvent(envelope));
    assertEquals(2, managers);
    assertEquals(2, reads.size());
    assertEquals(reads.get(0), reads.get(1));
    assertEquals(1, writes.get(0).getParameters().get("seq").getInt64());
    assertEquals(1, envelope.seqNr());
  }

  @Test
  void structuredStatusPrecedesRawTrailersAndCauseCyclesTerminate() {
    Metadata metadata = new Metadata();
    metadata.put(KEY, hint(0, 10000000).toByteArray());
    com.google.rpc.Status status =
        com.google.rpc.Status.newBuilder().setCode(10).addDetails(Any.pack(hint(61, 0))).build();
    SpannerException failure =
        SpannerExceptionFactory.newSpannerException(
            StatusProto.toStatusRuntimeException(status, metadata));
    assertEquals(61000, SpannerRetryWait.serverMillis(failure));
    status =
        com.google.rpc.Status.newBuilder()
            .setCode(10)
            .addDetails(Any.pack(RetryInfo.getDefaultInstance()))
            .build();
    failure =
        SpannerExceptionFactory.newSpannerException(
            StatusProto.toStatusRuntimeException(status, metadata));
    assertEquals(10, SpannerRetryWait.serverMillis(failure));
    RuntimeException cycle = new RuntimeException("cycle");
    RuntimeException other = new RuntimeException("other", cycle);
    failure = SpannerExceptionFactory.newSpannerException(ErrorCode.ABORTED, "abort", cycle);
    cycle.initCause(other);
    assertEquals(-1, SpannerRetryWait.serverMillis(failure));
  }

  @Test
  void abortRetainsCleanupEvidenceAndExhaustionWinsOverInterrupt() throws Exception {
    SpannerException abort = abort();
    closeFailure = new IllegalStateException("cleanup");
    StorageException error =
        assertThrows(
            StorageException.class,
            () ->
                run(
                    0,
                    SpannerStorageHooks.NONE,
                    t -> {
                      throw abort;
                    }));
    assertSame(abort, error.getCause());
    assertArrayEquals(new Throwable[] {closeFailure}, abort.getSuppressed());
    onClose = () -> Thread.currentThread().interrupt();
    error =
        assertThrows(
            StorageException.class,
            () ->
                run(
                    0,
                    SpannerStorageHooks.NONE,
                    t -> {
                      throw abort();
                    }));
    assertTrue(error.getMessage().contains("retry limit"));
    assertTrue(Thread.currentThread().isInterrupted());
    assertTrue(sleeps.isEmpty());
  }

  @Test
  void encodedBoundariesScheduleExactTimersOrStopWithoutRandomness() throws Exception {
    long[][] cases = {
      {0, 0, 1},
      {0, 1, 1},
      {0, 1000001, 2},
      {1, 1, 1001},
      {60, 0, 60000},
      {60, 1, 60001},
      {315576000000L, 999999999, 315576000001000L},
      {-1, 0, 25},
      {1, -1, 25},
      {0, 1000000000, 25},
      {315576000001L, 0, 25}
    };
    for (long[] c : cases) {
      sleeps.clear();
      managers = bodies = 0;
      SpannerException failure = raw(hint(c[0], (int) c[1]).toByteArray());
      if (c[2] > 60000) {
        StorageException error =
            assertThrows(
                StorageException.class,
                () ->
                    run(
                        1,
                        SpannerStorageHooks.NONE,
                        t -> {
                          throw failure;
                        }));
        assertSame(failure, error.getCause());
        assertTrue(sleeps.isEmpty());
        assertEquals(1, managers);
      } else {
        assertEquals(
            "ok",
            run(
                1,
                SpannerStorageHooks.NONE,
                t -> {
                  if (bodies == 1) throw failure;
                  return "ok";
                }));
        assertEquals(List.of(c[2]), sleeps);
        assertEquals(2, managers);
      }
    }
    wait =
        new SpannerRetryWait(
            clock::get,
            ms -> clock.addAndGet(ms * 1000000),
            () -> {
              throw new AssertionError("server hint must not sample fallback");
            });
    managers = bodies = 0;
    assertEquals(
        "ok",
        run(
            1,
            SpannerStorageHooks.NONE,
            t -> {
              if (bodies == 1) throw structured(hint(0, 0));
              return "ok";
            }));
  }

  @Test
  void cyclicCausesKeepStructuredStatusPrecedenceOverRawTrailers() {
    RuntimeException wrapper = new RuntimeException("wrapper");
    Metadata metadata = new Metadata();
    metadata.put(KEY, hint(0, 10000000).toByteArray());
    metadata.put(
        Metadata.Key.of("grpc-status-details-bin", Metadata.BINARY_BYTE_MARSHALLER),
        com.google.rpc.Status.newBuilder()
            .setCode(10)
            .addDetails(Any.pack(hint(61, 0)))
            .build()
            .toByteArray());
    RuntimeException grpc = Status.ABORTED.withCause(wrapper).asRuntimeException(metadata);
    SpannerException failure =
        SpannerExceptionFactory.newSpannerException(ErrorCode.ABORTED, "abort", grpc);
    wrapper.initCause(grpc);
    assertEquals(61000, SpannerRetryWait.serverMillis(failure));
  }

  @Test
  void causeAccessorFailureFallsBackAndPreservesAbort() throws Exception {
    AtomicBoolean armed = new AtomicBoolean();
    RuntimeException cause =
        new RuntimeException("broken accessor") {
          @Override
          public synchronized Throwable getCause() {
            if (armed.get()) throw new IllegalStateException("cause accessor failed");
            return null;
          }
        };
    SpannerException abort =
        SpannerExceptionFactory.newSpannerException(ErrorCode.ABORTED, "original ABORTED", cause);
    armed.set(true);
    assertEquals(-1, SpannerRetryWait.serverMillis(abort));
    StorageException error =
        assertThrows(
            StorageException.class,
            () ->
                run(
                    1,
                    SpannerStorageHooks.NONE,
                    tx -> {
                      throw abort;
                    }));
    assertSame(abort, error.getCause());
    assertEquals(2, managers);
    assertEquals(List.of(25L), sleeps);
    managers = bodies = 0;
    sleeps.clear();
    assertEquals(
        "ok",
        run(
            1,
            SpannerStorageHooks.NONE,
            tx -> {
              if (bodies == 1) throw abort;
              return "ok";
            }));
    assertEquals(2, managers);
    assertEquals(List.of(25L), sleeps);
  }

  @Test
  void causeAccessorFailureKeepsCollectedMetadata() {
    for (boolean structured : new boolean[] {false, true}) {
      Metadata metadata = new Metadata();
      metadata.put(KEY, hint(0, 3000001).toByteArray());
      if (structured)
        metadata.put(
            Metadata.Key.of("grpc-status-details-bin", Metadata.BINARY_BYTE_MARSHALLER),
            com.google.rpc.Status.newBuilder()
                .setCode(10)
                .addDetails(Any.pack(hint(0, 2000001)))
                .build()
                .toByteArray());
      AtomicBoolean armed = new AtomicBoolean();
      io.grpc.StatusRuntimeException cause =
          new io.grpc.StatusRuntimeException(Status.ABORTED, metadata) {
            @Override
            public synchronized Throwable getCause() {
              if (armed.get()) throw new IllegalStateException("cause accessor failed");
              return null;
            }
          };
      SpannerException abort =
          SpannerExceptionFactory.newSpannerException(ErrorCode.ABORTED, "original ABORTED", cause);
      armed.set(true);
      assertEquals(structured ? 3 : 4, SpannerRetryWait.serverMillis(abort));
    }
  }
}
