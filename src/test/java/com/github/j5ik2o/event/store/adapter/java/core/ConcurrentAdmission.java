package com.github.j5ik2o.event.store.adapter.java.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Admits operations from several threads at once and checks that the execution ran them in one
 * order that is consistent with when each admission was called and returned.
 */
public final class ConcurrentAdmission {
  @FunctionalInterface
  public interface Submit {
    CompletableFuture<?> submit(int caller, int round) throws Exception;
  }

  public static final class Admission {
    public final String key;
    public final int caller;
    public final long calledAt;
    public final long returnedAt;
    public final CompletableFuture<?> result;

    Admission(int caller, int round, long calledAt, long returnedAt, CompletableFuture<?> result) {
      this.key = key(caller, round);
      this.caller = caller;
      this.calledAt = calledAt;
      this.returnedAt = returnedAt;
      this.result = result;
    }
  }

  private ConcurrentAdmission() {}

  public static String key(int caller, int round) {
    return "c" + caller + "-" + round;
  }

  /**
   * The event a caller appends in a round: its own aggregate, the next seq_nr, its key as payload.
   */
  public static EventEnvelope<String> event(int caller, int round) {
    return EventEnvelope.<String>builder()
        .aggregateId(AggregateId.of("Order", "c" + caller))
        .seqNr(round + 1L)
        .occurredAt(Instant.EPOCH)
        .manifest("event")
        .payload(key(caller, round))
        .build();
  }

  /**
   * A config whose payload serializer adds each payload to {@code serialized} the first time it is
   * serialized, so a store that serializes again on a retry still records one position per write.
   */
  public static EventStoreConfig<String, String> recordingPayloads(List<String> serialized) {
    PayloadSerializer<String> json = JsonPayloadSerializer.of(String.class);
    Set<String> seen = ConcurrentHashMap.newKeySet();
    return EventStoreConfig.<String, String>builder()
        .payloadSerializer(
            new PayloadSerializer<String>() {
              @Override
              public byte[] serialize(String value) {
                if (seen.add(value)) {
                  serialized.add(value);
                }
                return json.serialize(value);
              }

              @Override
              public String deserialize(byte[] bytes) {
                return json.deserialize(bytes);
              }
            })
        .snapshotSerializer(json)
        .build();
  }

  /**
   * Each caller thread admits one operation per round. Every caller takes its call time before the
   * first barrier and its return time before the second, so the admissions of one round overlap and
   * all of them return before any admission of the next round is called.
   */
  public static List<Admission> admit(int callers, int rounds, Submit submit) throws Exception {
    AtomicLong clock = new AtomicLong();
    CyclicBarrier called = new CyclicBarrier(callers);
    CyclicBarrier returned = new CyclicBarrier(callers);
    List<Admission> admissions = Collections.synchronizedList(new ArrayList<>());
    AtomicReference<Throwable> failure = new AtomicReference<>();
    List<Thread> threads = new ArrayList<>();
    for (int caller = 0; caller < callers; caller++) {
      int c = caller;
      Thread thread =
          new Thread(
              () -> {
                try {
                  for (int round = 0; round < rounds; round++) {
                    long calledAt = clock.incrementAndGet();
                    called.await(5, TimeUnit.SECONDS);
                    CompletableFuture<?> result = submit.submit(c, round);
                    long returnedAt = clock.incrementAndGet();
                    admissions.add(new Admission(c, round, calledAt, returnedAt, result));
                    returned.await(5, TimeUnit.SECONDS);
                  }
                } catch (Throwable t) {
                  failure.compareAndSet(null, t);
                  called.reset();
                  returned.reset();
                }
              },
              "admission-caller-" + c);
      threads.add(thread);
      thread.start();
    }
    for (Thread thread : threads) {
      thread.join(TimeUnit.SECONDS.toMillis(20));
      assertFalse(thread.isAlive(), thread.getName() + " finished admitting");
    }
    if (failure.get() != null) {
      throw new AssertionError("a caller failed to admit", failure.get());
    }
    assertEquals(callers * rounds, admissions.size());
    return new ArrayList<>(admissions);
  }

  /**
   * Asserts that {@code executed} holds every admission once, and that an admission that returned
   * before another was called also ran before it. Fails unless both admissions that overlapped and
   * admissions of different callers that followed each other were checked.
   */
  public static void assertRanInOneAdmissionOrder(
      List<Admission> admissions, List<String> executed) {
    assertEquals(admissions.size(), executed.size(), "each accepted operation ran once");
    Map<String, Integer> position = new HashMap<>();
    for (int i = 0; i < executed.size(); i++) {
      position.put(executed.get(i), i);
    }
    for (Admission admission : admissions) {
      assertTrue(position.containsKey(admission.key), admission.key + " ran");
    }
    int followedAcrossCallers = 0;
    int overlapped = 0;
    for (Admission earlier : admissions) {
      for (Admission later : admissions) {
        if (earlier.returnedAt < later.calledAt) {
          if (position.get(earlier.key) > position.get(later.key)) {
            fail(
                earlier.key
                    + " returned before "
                    + later.key
                    + " was called but ran after it: "
                    + executed);
          }
          if (earlier.caller != later.caller) {
            followedAcrossCallers++;
          }
        } else if (earlier.caller != later.caller && later.returnedAt >= earlier.calledAt) {
          overlapped++;
        }
      }
    }
    assertTrue(followedAcrossCallers > 0, "admissions of different callers followed each other");
    assertTrue(overlapped > 0, "admissions of different callers overlapped");
  }
}
