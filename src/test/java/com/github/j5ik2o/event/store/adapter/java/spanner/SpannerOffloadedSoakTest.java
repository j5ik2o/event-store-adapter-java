package com.github.j5ik2o.event.store.adapter.java.spanner;

import static com.github.j5ik2o.event.store.adapter.java.spanner.SpannerTestFixtures.config;
import static com.github.j5ik2o.event.store.adapter.java.spanner.SpannerTestFixtures.deleteStore;
import static com.github.j5ik2o.event.store.adapter.java.spanner.SpannerTestFixtures.open;
import static com.github.j5ik2o.event.store.adapter.java.spanner.SpannerTestFixtures.store;

import com.github.j5ik2o.event.store.adapter.java.core.BlockingExecution;
import com.github.j5ik2o.event.store.adapter.java.soak.OffloadedSoak;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

@EnabledIfEnvironmentVariable(named = OffloadedSoak.DURATION_ENV, matches = "[1-9][0-9]*")
class SpannerOffloadedSoakTest {
  private static final SpannerRetentionPolicy POLICY = SpannerRetentionPolicy.delete(3);

  @Test
  void sustainedMixedLoadCompletesEveryAcceptedOperationInOrderWithoutLeaks() throws Exception {
    OffloadedSoak.Settings settings = OffloadedSoak.Settings.fromEnvironment("spanner");
    SpannerStorage storage = open(deleteStore(3), POLICY);
    BlockingExecution execution = BlockingExecution.singleWorker(16);
    OffloadedSoak.WorkerProbe probe = new OffloadedSoak.WorkerProbe();
    OffloadedSoak.Report report =
        OffloadedSoak.run(
            settings,
            SpannerEventStore.createOffloaded(storage, probe.config(), execution),
            execution,
            probe,
            store(storage));
    System.out.println(report.summary(settings));
    OffloadedSoak.assertHealthy(report, true);
  }

  @Test
  void callerThreadBaseline() throws Exception {
    OffloadedSoak.Settings settings =
        OffloadedSoak.Settings.fromEnvironment("spanner").callerThreadBaseline();
    SpannerStorage storage = open(deleteStore(3), POLICY);
    OffloadedSoak.Report report =
        OffloadedSoak.run(
            settings, SpannerEventStore.createAsync(storage, config()), null, null, store(storage));
    System.out.println(report.summary(settings));
    OffloadedSoak.assertHealthy(report, false);
  }
}
