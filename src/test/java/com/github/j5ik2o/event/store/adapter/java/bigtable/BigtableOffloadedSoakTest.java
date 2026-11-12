package com.github.j5ik2o.event.store.adapter.java.bigtable;

import static com.github.j5ik2o.event.store.adapter.java.bigtable.BigtableTestFixtures.config;
import static com.github.j5ik2o.event.store.adapter.java.bigtable.BigtableTestFixtures.deleteStore;
import static com.github.j5ik2o.event.store.adapter.java.bigtable.BigtableTestFixtures.open;
import static com.github.j5ik2o.event.store.adapter.java.bigtable.BigtableTestFixtures.store;

import com.github.j5ik2o.event.store.adapter.java.core.BlockingExecution;
import com.github.j5ik2o.event.store.adapter.java.core.RetentionPolicy;
import com.github.j5ik2o.event.store.adapter.java.soak.OffloadedSoak;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

@EnabledIfEnvironmentVariable(named = OffloadedSoak.DURATION_ENV, matches = "[1-9][0-9]*")
class BigtableOffloadedSoakTest {
  private static final RetentionPolicy POLICY = RetentionPolicy.delete(3);

  @Test
  void sustainedMixedLoadCompletesEveryAcceptedOperationInOrderWithoutLeaks() throws Exception {
    OffloadedSoak.Settings settings = OffloadedSoak.Settings.fromEnvironment("bigtable");
    BigtableStorage storage = open(deleteStore(3), POLICY);
    BlockingExecution execution = BlockingExecution.singleWorker(16);
    OffloadedSoak.Report report =
        OffloadedSoak.run(
            settings,
            BigtableEventStore.createOffloaded(storage, config(), execution),
            execution,
            store(storage));
    System.out.println(report.summary(settings));
    OffloadedSoak.assertHealthy(report, true);
  }

  @Test
  void callerThreadBaseline() throws Exception {
    OffloadedSoak.Settings settings =
        OffloadedSoak.Settings.fromEnvironment("bigtable").callerThreadBaseline();
    BigtableStorage storage = open(deleteStore(3), POLICY);
    OffloadedSoak.Report report =
        OffloadedSoak.run(
            settings, BigtableEventStore.createAsync(storage, config()), null, store(storage));
    System.out.println(report.summary(settings));
    OffloadedSoak.assertHealthy(report, false);
  }
}
