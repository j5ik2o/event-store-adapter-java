package com.github.j5ik2o.event.store.adapter.java.bigtable;

import static com.github.j5ik2o.event.store.adapter.java.bigtable.BigtableTestFixtures.EMULATOR;
import static com.github.j5ik2o.event.store.adapter.java.bigtable.BigtableTestFixtures.deleteStore;
import static com.github.j5ik2o.event.store.adapter.java.bigtable.BigtableTestFixtures.event;
import static com.github.j5ik2o.event.store.adapter.java.bigtable.BigtableTestFixtures.headSeqNr;
import static com.github.j5ik2o.event.store.adapter.java.bigtable.BigtableTestFixtures.journalKey;
import static com.github.j5ik2o.event.store.adapter.java.bigtable.BigtableTestFixtures.noneStore;
import static com.github.j5ik2o.event.store.adapter.java.bigtable.BigtableTestFixtures.open;
import static com.github.j5ik2o.event.store.adapter.java.bigtable.BigtableTestFixtures.prefix;
import static com.github.j5ik2o.event.store.adapter.java.bigtable.BigtableTestFixtures.snapshot;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

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
import com.github.j5ik2o.event.store.adapter.java.core.RetentionPolicy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * A retention failure must never turn a committed append into a failure, even when the listener or
 * the log throws an {@code Error}. Every test asserts that the failure path was really reached.
 */
class BigtablePostCommitNotificationTest {
  /** Fails retention on the second append, after the first one has been stored. */
  private static final class FailRetentionAfterFirst implements BigtableStorageHooks {
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

  private static void assertBothAppendsAreStored(BigtableEmulator.Tables t) {
    assertEquals(headSeqNr(2), EMULATOR.text(t.head, prefix("Order-1"), "head", "seq_nr"));
    assertNotNull(EMULATOR.row(t.journal, journalKey("Order-1", 1)));
    assertEquals("active", EMULATOR.text(t.journal, journalKey("Order-1", 1), "history", "state"));
    assertEquals(
        2,
        BigtableEnvelope.decodeSnapshot(
                EMULATOR.bytes(t.head, prefix("Order-1"), "snapshot", "current").toByteArray())
            .seqNr());
  }

  @Test
  void syncAppendSucceedsWhenTheListenerThrowsAnAssertionError() {
    BigtableEmulator.Tables prefix = deleteStore(1);
    FailRetentionAfterFirst hooks = new FailRetentionAfterFirst();
    int[] listenerCalls = {0};
    EventStore<String, String> store =
        BigtableEventStore.create(
            open(prefix, RetentionPolicy.delete(1), hooks),
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
    BigtableEmulator.Tables prefix = deleteStore(1);
    FailRetentionAfterFirst hooks = new FailRetentionAfterFirst();
    List<RetentionFailure> notifications = new ArrayList<>();
    Logger logger = (Logger) LoggerFactory.getLogger(BigtableEventStore.class);
    ThrowingAppender appender = new ThrowingAppender();
    appender.setContext(logger.getLoggerContext());
    appender.start();
    logger.addAppender(appender);
    try {
      EventStore<String, String> store =
          BigtableEventStore.create(
              open(prefix, RetentionPolicy.delete(1), hooks), configuration(notifications::add));
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
    BigtableEmulator.Tables prefix = deleteStore(1);
    FailRetentionAfterFirst hooks = new FailRetentionAfterFirst();
    int[] listenerCalls = {0};
    AsyncEventStore<String, String> async =
        BigtableEventStore.createAsync(
            open(prefix, RetentionPolicy.delete(1), hooks),
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
    BigtableEmulator.Tables prefix = deleteStore(1);
    FailRetentionAfterFirst hooks = new FailRetentionAfterFirst();
    List<RetentionFailure> notifications = new ArrayList<>();
    Logger logger = (Logger) LoggerFactory.getLogger(BigtableEventStore.class);
    ThrowingAppender appender = new ThrowingAppender();
    appender.setContext(logger.getLoggerContext());
    appender.start();
    logger.addAppender(appender);
    try {
      AsyncEventStore<String, String> async =
          BigtableEventStore.createAsync(
              open(prefix, RetentionPolicy.delete(1), hooks), configuration(notifications::add));
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

  // ----- tail write-out failure: a warning on the journal logger only (no listener) -----

  private static final String JOURNAL_LOGGER =
      "com.github.j5ik2o.event.store.adapter.java.bigtable.journal";

  /** Fails the write-out of the new tail on the second append. */
  private static final class FailTailOnSecond implements BigtableStorageHooks {
    int calls;

    @Override
    public void journalWriteTail(AggregateId aggregateId, long seqNr) {
      calls++;
      if (seqNr == 2) throw new IllegalStateException("injected tail failure");
    }
  }

  private static void assertTailFailureAppendsAreStored(BigtableEmulator.Tables t) {
    assertEquals(headSeqNr(2), EMULATOR.text(t.head, prefix("Order-1"), "head", "seq_nr"));
    assertNotNull(EMULATOR.row(t.journal, journalKey("Order-1", 1)));
    assertEquals(null, EMULATOR.row(t.journal, journalKey("Order-1", 2)));
  }

  @Test
  void syncAppendSucceedsWhenTheJournalLogThrowsAnAssertionErrorAndWarnsExactlyOnce() {
    BigtableEmulator.Tables t = noneStore();
    FailTailOnSecond hooks = new FailTailOnSecond();
    List<RetentionFailure> notifications = new ArrayList<>();
    Logger logger = (Logger) LoggerFactory.getLogger(JOURNAL_LOGGER);
    ThrowingAppender appender = new ThrowingAppender();
    appender.setContext(logger.getLoggerContext());
    appender.start();
    logger.addAppender(appender);
    try {
      EventStore<String, String> store =
          BigtableEventStore.create(
              open(t, RetentionPolicy.none(), hooks), configuration(notifications::add));
      store.persistEvent(event(1));
      int before = appender.warnCalls;

      assertDoesNotThrow(() -> store.persistEvent(event(2)));

      assertEquals(2, hooks.calls, "the tail write-out path was really taken");
      assertEquals(before + 1, appender.warnCalls);
      assertEquals(0, notifications.size(), "a tail failure is not a retention failure");
    } finally {
      logger.detachAppender(appender);
      appender.stop();
    }
    assertTailFailureAppendsAreStored(t);
  }

  @Test
  void asyncAppendCompletesWhenTheJournalLogThrowsAnAssertionError() {
    BigtableEmulator.Tables t = noneStore();
    FailTailOnSecond hooks = new FailTailOnSecond();
    Logger logger = (Logger) LoggerFactory.getLogger(JOURNAL_LOGGER);
    ThrowingAppender appender = new ThrowingAppender();
    appender.setContext(logger.getLoggerContext());
    appender.start();
    logger.addAppender(appender);
    try {
      AsyncEventStore<String, String> async =
          BigtableEventStore.createAsync(
              open(t, RetentionPolicy.none(), hooks), configuration(failure -> {}));
      assertDoesNotThrow(() -> async.persistEvent(event(1)).join());
      int before = appender.warnCalls;

      CompletableFuture<Void> result = assertDoesNotThrow(() -> async.persistEvent(event(2)));
      assertDoesNotThrow(result::join);

      assertFalse(result.isCompletedExceptionally());
      assertEquals(before + 1, appender.warnCalls);
    } finally {
      logger.detachAppender(appender);
      appender.stop();
    }
    assertTailFailureAppendsAreStored(t);
  }

  @Test
  void aTailFailureStillLetsTheNextAppendCompleteTheMissingRow() {
    BigtableEmulator.Tables t = noneStore();
    FailTailOnSecond hooks = new FailTailOnSecond();
    EventStore<String, String> store =
        BigtableEventStore.create(open(t, RetentionPolicy.none(), hooks), configuration(f -> {}));
    store.persistEvent(event(1));
    store.persistEvent(event(2));

    store.persistEvent(event(3));

    assertNotNull(EMULATOR.row(t.journal, journalKey("Order-1", 2)));
    assertEquals(3, store.getEventsByIdSinceSeqNr(BigtableTestFixtures.AID, 1).size());
  }
}
