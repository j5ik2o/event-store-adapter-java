package com.github.j5ik2o.event.store.adapter.java.spanner;

import static com.github.j5ik2o.event.store.adapter.java.spanner.SpannerTestFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

import com.github.j5ik2o.event.store.adapter.java.core.AggregateId;
import com.github.j5ik2o.event.store.adapter.java.core.EventEnvelope;
import com.google.cloud.spanner.SpannerException;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class SpannerRetryWaitIntegrationTest {
  @Test
  void encodedSdkHintsThroughPublicAppendCommitExactlyOnce() {
    for (SpannerException abort :
        List.of(
            SpannerRetryWaitTest.structured(SpannerRetryWaitTest.hint(0, 1000001)),
            SpannerRetryWaitTest.raw(SpannerRetryWaitTest.hint(0, 1000001).toByteArray()))) {
      String prefix = noneStore();
      AtomicInteger before = new AtomicInteger();
      AtomicInteger after = new AtomicInteger();
      SpannerStorageHooks hooks =
          new SpannerStorageHooks() {
            public void beforeCommit(AggregateId id) {
              if (before.incrementAndGet() == 1) throw abort;
            }

            public void afterCommit(AggregateId id) {
              after.incrementAndGet();
            }
          };
      EventEnvelope<String> envelope = event(1);
      store(
              SpannerStorage.open(
                  EMULATOR.client(), settings(prefix, SpannerRetentionPolicy.none()), hooks))
          .persistEvent(envelope);
      assertEquals(2, before.get());
      assertEquals(1, after.get());
      assertEquals(1, EMULATOR.count(prefix, "journal"));
      assertEquals(List.of(1L), EMULATOR.longs("SELECT seq_nr FROM " + prefix + "_head"));
    }
  }
}
