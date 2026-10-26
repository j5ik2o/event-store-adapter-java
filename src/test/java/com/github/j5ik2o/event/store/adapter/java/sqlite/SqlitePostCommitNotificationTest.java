package com.github.j5ik2o.event.store.adapter.java.sqlite;

import static com.github.j5ik2o.event.store.adapter.java.sqlite.SqliteTestFixtures.event;
import static com.github.j5ik2o.event.store.adapter.java.sqlite.SqliteTestFixtures.execute;
import static com.github.j5ik2o.event.store.adapter.java.sqlite.SqliteTestFixtures.initialized;
import static com.github.j5ik2o.event.store.adapter.java.sqlite.SqliteTestFixtures.longs;
import static com.github.j5ik2o.event.store.adapter.java.sqlite.SqliteTestFixtures.settings;
import static com.github.j5ik2o.event.store.adapter.java.sqlite.SqliteTestFixtures.snapshot;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import com.github.j5ik2o.event.store.adapter.java.core.AsyncEventStore;
import com.github.j5ik2o.event.store.adapter.java.core.EventStore;
import com.github.j5ik2o.event.store.adapter.java.core.EventStoreConfig;
import com.github.j5ik2o.event.store.adapter.java.core.JsonPayloadSerializer;
import com.github.j5ik2o.event.store.adapter.java.core.RetentionFailure;
import com.github.j5ik2o.event.store.adapter.java.core.RetentionFailureListener;
import com.github.j5ik2o.event.store.adapter.java.core.RetentionPolicy;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

class SqlitePostCommitNotificationTest {
  private static final String FAIL_RETENTION =
      "CREATE TRIGGER fail_retention BEFORE DELETE ON snapshot_history BEGIN "
          + "SELECT RAISE(ABORT, 'injected retention failure'); END";

  @TempDir Path directory;

  /** doAppend を上書きし、AppenderBase 内部の例外処理を通さずに Error をログ呼び出し元へ伝える。 */
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

  @Test
  void syncAppendSucceedsWhenTheListenerThrowsAnAssertionError() {
    Path file = initialized(directory, RetentionPolicy.delete(1));
    int[] listenerCalls = {0};
    try (SqliteStorage storage = SqliteStorage.open(file, settings(RetentionPolicy.delete(1)))) {
      EventStore<String, String> store =
          SqliteEventStore.create(
              storage,
              configuration(
                  failure -> {
                    listenerCalls[0]++;
                    throw new AssertionError("listener failed");
                  }));
      store.persistEventAndSnapshot(event(1), snapshot(1));
      execute(file, FAIL_RETENTION);

      assertDoesNotThrow(() -> store.persistEventAndSnapshot(event(2), snapshot(2)));
    }

    assertEquals(1, listenerCalls[0]);
    assertSecondAppendIsStored(file);
  }

  @Test
  void syncAppendSucceedsWhenTheRetentionLogThrowsAnAssertionError() {
    Path file = initialized(directory, RetentionPolicy.delete(1));
    List<RetentionFailure> notifications = new ArrayList<>();
    Logger logger = (Logger) LoggerFactory.getLogger(SqliteEventStore.class);
    ThrowingAppender appender = new ThrowingAppender();
    appender.setContext(logger.getLoggerContext());
    appender.start();
    logger.addAppender(appender);
    try (SqliteStorage storage = SqliteStorage.open(file, settings(RetentionPolicy.delete(1)))) {
      EventStore<String, String> store =
          SqliteEventStore.create(storage, configuration(notifications::add));
      store.persistEventAndSnapshot(event(1), snapshot(1));
      execute(file, FAIL_RETENTION);

      assertDoesNotThrow(() -> store.persistEventAndSnapshot(event(2), snapshot(2)));
    } finally {
      logger.detachAppender(appender);
      appender.stop();
    }

    assertEquals(1, appender.warnCalls);
    assertEquals(1, notifications.size());
    assertSecondAppendIsStored(file);
  }

  @Test
  void asyncAppendCompletesWhenTheListenerThrowsAnAssertionError() {
    Path file = initialized(directory, RetentionPolicy.delete(1));
    int[] listenerCalls = {0};
    try (SqliteStorage storage = SqliteStorage.open(file, settings(RetentionPolicy.delete(1)))) {
      AsyncEventStore<String, String> async =
          SqliteEventStore.createAsync(
              storage,
              configuration(
                  failure -> {
                    listenerCalls[0]++;
                    throw new AssertionError("listener failed");
                  }));
      assertDoesNotThrow(() -> async.persistEventAndSnapshot(event(1), snapshot(1)).join());
      execute(file, FAIL_RETENTION);

      CompletableFuture<Void> result =
          assertDoesNotThrow(() -> async.persistEventAndSnapshot(event(2), snapshot(2)));
      assertDoesNotThrow(result::join);
      assertFalse(result.isCompletedExceptionally());
    }

    assertEquals(1, listenerCalls[0]);
    assertSecondAppendIsStored(file);
  }

  @Test
  void asyncAppendCompletesWhenTheRetentionLogThrowsAnAssertionError() {
    Path file = initialized(directory, RetentionPolicy.delete(1));
    List<RetentionFailure> notifications = new ArrayList<>();
    Logger logger = (Logger) LoggerFactory.getLogger(SqliteEventStore.class);
    ThrowingAppender appender = new ThrowingAppender();
    appender.setContext(logger.getLoggerContext());
    appender.start();
    logger.addAppender(appender);
    try (SqliteStorage storage = SqliteStorage.open(file, settings(RetentionPolicy.delete(1)))) {
      AsyncEventStore<String, String> async =
          SqliteEventStore.createAsync(storage, configuration(notifications::add));
      assertDoesNotThrow(() -> async.persistEventAndSnapshot(event(1), snapshot(1)).join());
      execute(file, FAIL_RETENTION);

      CompletableFuture<Void> result =
          assertDoesNotThrow(() -> async.persistEventAndSnapshot(event(2), snapshot(2)));
      assertDoesNotThrow(result::join);
      assertFalse(result.isCompletedExceptionally());
    } finally {
      logger.detachAppender(appender);
      appender.stop();
    }

    assertEquals(1, appender.warnCalls);
    assertEquals(1, notifications.size());
    assertSecondAppendIsStored(file);
  }

  private static void assertSecondAppendIsStored(Path file) {
    assertEquals(List.of(2L), longs(file, "SELECT seq_nr FROM head"));
    assertEquals(List.of(1L, 2L), longs(file, "SELECT seq_nr FROM journal ORDER BY seq_nr"));
    assertEquals(List.of(2L), longs(file, "SELECT seq_nr FROM snapshot_current"));
    assertEquals(
        List.of(1L, 2L), longs(file, "SELECT seq_nr FROM snapshot_history ORDER BY seq_nr"));
  }

  private static EventStoreConfig<String, String> configuration(RetentionFailureListener listener) {
    return EventStoreConfig.<String, String>builder()
        .payloadSerializer(JsonPayloadSerializer.of(String.class))
        .snapshotSerializer(JsonPayloadSerializer.of(String.class))
        .retentionFailureListener(listener)
        .build();
  }
}
