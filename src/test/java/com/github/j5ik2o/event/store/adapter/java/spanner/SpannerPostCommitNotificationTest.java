package com.github.j5ik2o.event.store.adapter.java.spanner;

import static com.github.j5ik2o.event.store.adapter.java.spanner.SpannerTestFixtures.EMULATOR;
import static com.github.j5ik2o.event.store.adapter.java.spanner.SpannerTestFixtures.deleteStore;
import static com.github.j5ik2o.event.store.adapter.java.spanner.SpannerTestFixtures.event;
import static com.github.j5ik2o.event.store.adapter.java.spanner.SpannerTestFixtures.open;
import static com.github.j5ik2o.event.store.adapter.java.spanner.SpannerTestFixtures.snapshot;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import com.github.j5ik2o.event.store.adapter.java.core.AggregateId;
import com.github.j5ik2o.event.store.adapter.java.core.AsyncEventStore;
import com.github.j5ik2o.event.store.adapter.java.core.EventStore;
import com.github.j5ik2o.event.store.adapter.java.core.EventStoreConfig;
import com.github.j5ik2o.event.store.adapter.java.core.JsonPayloadSerializer;
import com.github.j5ik2o.event.store.adapter.java.core.RetentionFailure;
import com.github.j5ik2o.event.store.adapter.java.core.RetentionFailureListener;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * A retention failure must never turn a committed append into a failure, even when the listener or
 * the log throws an {@code Error}. Every test asserts that the failure path was really reached.
 */
class SpannerPostCommitNotificationTest {
  /** Fails retention on the second append, after the first one has been stored. */
  private static final class FailRetentionAfterFirst implements SpannerStorageHooks {
    int calls;

    @Override
    public void beforeRetentionDelete(AggregateId aggregateId, List<Long> seqNrs) {
      calls++;
      throw new IllegalStateException("injected retention failure");
    }
  }

  /** doAppend is overridden so the Error reaches the logging caller unchanged. */
  private static final class ThrowingAppender extends AppenderBase<ILoggingEvent> {
    int warnCalls;

    @Override
    public void doAppend(ILoggingEvent loggingEvent) {
      if (loggingEvent.getLevel() == Level.WARN) {
        warnCalls++;
        throw new AssertionError("log failed");
      }
    }

    @Override
    protected void append(ILoggingEvent loggingEvent) {}
  }

  private static EventStoreConfig<String, String> configuration(RetentionFailureListener listener) {
    return EventStoreConfig.<String, String>builder()
        .payloadSerializer(JsonPayloadSerializer.of(String.class))
        .snapshotSerializer(JsonPayloadSerializer.of(String.class))
        .retentionFailureListener(listener)
        .build();
  }

  private static void assertBothAppendsAreStored(String prefix) {
    assertEquals(List.of(2L), EMULATOR.longs("SELECT seq_nr FROM " + prefix + "_head"));
    assertEquals(
        List.of(1L, 2L),
        EMULATOR.longs("SELECT seq_nr FROM " + prefix + "_journal ORDER BY seq_nr"));
    assertEquals(List.of(2L), EMULATOR.longs("SELECT seq_nr FROM " + prefix + "_snapshot_current"));
    assertEquals(
        List.of(1L, 2L),
        EMULATOR.longs("SELECT seq_nr FROM " + prefix + "_snapshot_history ORDER BY seq_nr"));
  }

  @Test
  void syncAppendSucceedsWhenTheListenerThrowsAnAssertionError() {
    String prefix = deleteStore(1);
    FailRetentionAfterFirst hooks = new FailRetentionAfterFirst();
    int[] listenerCalls = {0};
    EventStore<String, String> store =
        SpannerEventStore.create(
            open(prefix, SpannerRetentionPolicy.delete(1), hooks),
            configuration(
                failure -> {
                  listenerCalls[0]++;
                  throw new AssertionError("listener failed");
                }));
    assertDoesNotThrow(() -> store.persistEventAndSnapshot(event(1), snapshot(1)));
    int before = hooks.calls;
    int listenerBefore = listenerCalls[0];

    assertDoesNotThrow(() -> store.persistEventAndSnapshot(event(2), snapshot(2)));

    assertEquals(listenerBefore + 1, listenerCalls[0]);
    assertEquals(before + 1, hooks.calls);
    assertBothAppendsAreStored(prefix);
  }

  @Test
  void syncAppendSucceedsWhenTheRetentionLogThrowsAnAssertionError() {
    String prefix = deleteStore(1);
    FailRetentionAfterFirst hooks = new FailRetentionAfterFirst();
    List<RetentionFailure> notifications = new ArrayList<>();
    Logger logger = (Logger) LoggerFactory.getLogger(SpannerEventStore.class);
    ThrowingAppender appender = new ThrowingAppender();
    appender.setContext(logger.getLoggerContext());
    appender.start();
    logger.addAppender(appender);
    try {
      EventStore<String, String> store =
          SpannerEventStore.create(
              open(prefix, SpannerRetentionPolicy.delete(1), hooks),
              configuration(notifications::add));
      store.persistEventAndSnapshot(event(1), snapshot(1));
      int warnsBefore = appender.warnCalls;
      int notificationsBefore = notifications.size();

      assertDoesNotThrow(() -> store.persistEventAndSnapshot(event(2), snapshot(2)));

      assertEquals(warnsBefore + 1, appender.warnCalls);
      assertEquals(notificationsBefore + 1, notifications.size());
    } finally {
      logger.detachAppender(appender);
      appender.stop();
    }
    assertBothAppendsAreStored(prefix);
  }

  @Test
  void asyncAppendCompletesWhenTheListenerThrowsAnAssertionError() {
    String prefix = deleteStore(1);
    FailRetentionAfterFirst hooks = new FailRetentionAfterFirst();
    int[] listenerCalls = {0};
    AsyncEventStore<String, String> async =
        SpannerEventStore.createAsync(
            open(prefix, SpannerRetentionPolicy.delete(1), hooks),
            configuration(
                failure -> {
                  listenerCalls[0]++;
                  throw new AssertionError("listener failed");
                }));
    assertDoesNotThrow(() -> async.persistEventAndSnapshot(event(1), snapshot(1)).join());
    int before = listenerCalls[0];

    CompletableFuture<Void> result =
        assertDoesNotThrow(() -> async.persistEventAndSnapshot(event(2), snapshot(2)));
    assertDoesNotThrow(result::join);

    assertFalse(result.isCompletedExceptionally());
    assertEquals(before + 1, listenerCalls[0]);
    assertBothAppendsAreStored(prefix);
  }

  @Test
  void asyncAppendCompletesWhenTheRetentionLogThrowsAnAssertionError() {
    String prefix = deleteStore(1);
    FailRetentionAfterFirst hooks = new FailRetentionAfterFirst();
    List<RetentionFailure> notifications = new ArrayList<>();
    Logger logger = (Logger) LoggerFactory.getLogger(SpannerEventStore.class);
    ThrowingAppender appender = new ThrowingAppender();
    appender.setContext(logger.getLoggerContext());
    appender.start();
    logger.addAppender(appender);
    try {
      AsyncEventStore<String, String> async =
          SpannerEventStore.createAsync(
              open(prefix, SpannerRetentionPolicy.delete(1), hooks),
              configuration(notifications::add));
      assertDoesNotThrow(() -> async.persistEventAndSnapshot(event(1), snapshot(1)).join());
      int warnsBefore = appender.warnCalls;
      int notificationsBefore = notifications.size();

      CompletableFuture<Void> result =
          assertDoesNotThrow(() -> async.persistEventAndSnapshot(event(2), snapshot(2)));
      assertDoesNotThrow(result::join);

      assertFalse(result.isCompletedExceptionally());
      assertEquals(warnsBefore + 1, appender.warnCalls);
      assertEquals(notificationsBefore + 1, notifications.size());
    } finally {
      logger.detachAppender(appender);
      appender.stop();
    }
    assertBothAppendsAreStored(prefix);
  }
}
