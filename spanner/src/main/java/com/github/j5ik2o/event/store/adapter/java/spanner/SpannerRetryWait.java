package com.github.j5ik2o.event.store.adapter.java.spanner;

import com.github.j5ik2o.event.store.adapter.java.core.StorageException;
import com.google.api.gax.rpc.ErrorDetails;
import com.google.cloud.spanner.SpannerException;
import com.google.protobuf.Any;
import com.google.protobuf.Duration;
import com.google.rpc.RetryInfo;
import io.grpc.Metadata;
import io.grpc.StatusException;
import io.grpc.StatusRuntimeException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.DoubleSupplier;
import java.util.function.LongSupplier;

/** The private ADR-0009 wait policy; all durations passed to the sleeper are milliseconds. */
final class SpannerRetryWait {
  interface Sleeper {
    void sleep(long millis) throws InterruptedException;
  }

  static final SpannerRetryWait DEFAULT =
      new SpannerRetryWait(
          System::nanoTime, Thread::sleep, () -> ThreadLocalRandom.current().nextDouble());
  private static final Metadata.Key<byte[]> RETRY_INFO =
      Metadata.Key.of("google.rpc.retryinfo-bin", Metadata.BINARY_BYTE_MARSHALLER);
  private static final Metadata.Key<byte[]> STATUS_DETAILS =
      Metadata.Key.of("grpc-status-details-bin", Metadata.BINARY_BYTE_MARSHALLER);
  private final LongSupplier clock;
  private final Sleeper sleeper;
  private final DoubleSupplier random;

  SpannerRetryWait(LongSupplier clock, Sleeper sleeper, DoubleSupplier random) {
    this.clock = clock;
    this.sleeper = sleeper;
    this.random = random;
  }

  void checkInterrupted(String message, SpannerException lastAbort) {
    if (Thread.currentThread().isInterrupted()) {
      throw interrupted(
          message, new InterruptedException("transaction retry interrupted"), lastAbort);
    }
  }

  private StorageException interrupted(
      String message, InterruptedException cause, SpannerException lastAbort) {
    Thread.currentThread().interrupt();
    StorageException failure =
        new StorageException(message + ": transaction retry interrupted", cause);
    if (lastAbort != null) failure.addSuppressed(lastAbort);
    return failure;
  }

  void await(SpannerException failure, int ordinal, String message) {
    long millis = serverMillis(failure);
    if (millis > 60000) {
      throw new StorageException(
          message + ": server retry delay exceeds supported wait of 60000ms", failure);
    }
    if (millis < 0) millis = fallbackMillis(ordinal);
    checkInterrupted(message, failure);
    long started = clock.getAsLong();
    long remaining = millis * 1000000L;
    while (remaining > 0) {
      checkInterrupted(message, failure);
      try {
        sleeper.sleep((remaining + 999999L) / 1000000L);
      } catch (InterruptedException interrupted) {
        throw interrupted(message, interrupted, failure);
      }
      checkInterrupted(message, failure);
      remaining = millis * 1000000L - (clock.getAsLong() - started);
    }
  }

  long fallbackMillis(int ordinal) {
    int cap = ordinal >= 6 ? 1000 : 50 << (ordinal - 1);
    int lower = (cap + 1) / 2;
    return lower + (long) (random.getAsDouble() * (cap - lower + 1));
  }

  // -1 means fallback; any valid value, including an over-ceiling value, wins.
  private static long durationMillis(RetryInfo info) {
    if (info == null || !info.hasRetryDelay()) return -1;
    Duration duration = info.getRetryDelay();
    long seconds = duration.getSeconds();
    int nanos = duration.getNanos();
    if (seconds < 0 || seconds > 315576000000L || nanos < 0 || nanos > 999999999) return -1;
    return Math.max(1L, seconds * 1000L + (nanos + 999999L) / 1000000L);
  }

  private static Metadata trailers(Throwable failure) {
    if (failure instanceof StatusRuntimeException)
      return ((StatusRuntimeException) failure).getTrailers();
    if (failure instanceof StatusException) return ((StatusException) failure).getTrailers();
    return null;
  }

  static long serverMillis(SpannerException failure) {
    Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
    List<Metadata> metadata = new ArrayList<>();
    Throwable current = failure;
    while (current != null && visited.add(current)) {
      Metadata value = trailers(current);
      if (value != null) metadata.add(value);
      current = current.getCause();
    }
    if (current == null) {
      try {
        ErrorDetails details = failure.getErrorDetails();
        if (details != null) {
          long value = durationMillis(details.getRetryInfo());
          if (value >= 0) return value;
        }
      } catch (RuntimeException ignored) {
        // A decoding failure must not replace the original ABORTED.
      }
    } else {
      // SDK 6.127.0 getErrorDetails traverses causes without a cycle guard.
      for (Metadata value : metadata) {
        byte[] encoded = value.get(STATUS_DETAILS);
        if (encoded == null) continue;
        try {
          for (Any detail : com.google.rpc.Status.parseFrom(encoded).getDetailsList()) {
            if (detail.is(RetryInfo.class)) {
              long millis = durationMillis(detail.unpack(RetryInfo.class));
              if (millis >= 0) return millis;
            }
          }
        } catch (java.io.IOException | RuntimeException ignored) {
          // Invalid structured status leaves the raw trailer path available.
        }
      }
    }
    for (Metadata value : metadata) {
      Iterable<byte[]> entries = value.getAll(RETRY_INFO);
      if (entries == null) continue;
      for (byte[] entry : entries) {
        try {
          long millis = durationMillis(RetryInfo.parseFrom(entry));
          if (millis >= 0) return millis;
        } catch (java.io.IOException | RuntimeException ignored) {
          // Continue in wire order to the first valid structured duration.
        }
      }
    }
    return -1;
  }
}
