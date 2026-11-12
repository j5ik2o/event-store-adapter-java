package com.github.j5ik2o.event.store.adapter.java.sqlite;

import static com.github.j5ik2o.event.store.adapter.java.sqlite.SqliteTestFixtures.config;
import static com.github.j5ik2o.event.store.adapter.java.sqlite.SqliteTestFixtures.initialized;
import static com.github.j5ik2o.event.store.adapter.java.sqlite.SqliteTestFixtures.settings;
import static com.github.j5ik2o.event.store.adapter.java.sqlite.SqliteTestFixtures.store;

import com.github.j5ik2o.event.store.adapter.java.core.BlockingExecution;
import com.github.j5ik2o.event.store.adapter.java.core.RetentionPolicy;
import com.github.j5ik2o.event.store.adapter.java.soak.OffloadedSoak;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

@EnabledIfEnvironmentVariable(named = OffloadedSoak.DURATION_ENV, matches = "[1-9][0-9]*")
class SqliteOffloadedSoakTest {
  private static final RetentionPolicy POLICY = RetentionPolicy.delete(3);

  @TempDir Path directory;

  @Test
  void sustainedMixedLoadCompletesEveryAcceptedOperationInOrderWithoutLeaks() throws Exception {
    OffloadedSoak.Settings settings = OffloadedSoak.Settings.fromEnvironment("sqlite");
    Path file = initialized(Files.createDirectories(directory.resolve("offloaded")), POLICY);
    try (SqliteStorage storage = SqliteStorage.open(file, settings(POLICY))) {
      BlockingExecution execution = BlockingExecution.singleWorker(16);
      OffloadedSoak.Report report =
          OffloadedSoak.run(
              settings,
              SqliteEventStore.createOffloaded(storage, config(), execution),
              execution,
              store(storage));
      System.out.println(report.summary(settings));
      OffloadedSoak.assertHealthy(report, true);
    }
  }

  @Test
  void callerThreadBaseline() throws Exception {
    OffloadedSoak.Settings settings =
        OffloadedSoak.Settings.fromEnvironment("sqlite").callerThreadBaseline();
    Path file = initialized(Files.createDirectories(directory.resolve("caller")), POLICY);
    try (SqliteStorage storage = SqliteStorage.open(file, settings(POLICY))) {
      OffloadedSoak.Report report =
          OffloadedSoak.run(
              settings, SqliteEventStore.createAsync(storage, config()), null, store(storage));
      System.out.println(report.summary(settings));
      OffloadedSoak.assertHealthy(report, false);
    }
  }
}
