package com.github.j5ik2o.event.store.adapter.java.examples;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.j5ik2o.event.store.adapter.java.core.AggregateId;
import com.github.j5ik2o.event.store.adapter.java.core.AsyncEventStore;
import com.github.j5ik2o.event.store.adapter.java.core.EventStore;
import com.github.j5ik2o.event.store.adapter.java.core.EventStoreConfig;
import com.github.j5ik2o.event.store.adapter.java.core.SnapshotReadResult;
import com.github.j5ik2o.event.store.adapter.java.spanner.SpannerEmulator;
import com.github.j5ik2o.event.store.adapter.java.spanner.SpannerEventStore;
import com.github.j5ik2o.event.store.adapter.java.spanner.SpannerRetentionPolicy;
import com.github.j5ik2o.event.store.adapter.java.spanner.SpannerStorage;
import com.github.j5ik2o.event.store.adapter.java.spanner.SpannerStorageConfig;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** The README Spanner example, run from outside the library package through public types only. */
class SpannerUserAccountExampleTest {
  @Test
  void readmeSpannerExampleWorksThroughPublicTypesOnly() throws Exception {
    SpannerEmulator emulator = SpannerEmulator.shared();
    String prefix =
        emulator.createStore(null, SpannerEmulator.ConfigRow.matching(null, "delete", null));
    EventStoreConfig<String, String> config = UserAccountExample.config();
    SpannerStorageConfig settings =
        SpannerStorageConfig.builder()
            .tablePrefix(prefix)
            .retentionPolicy(SpannerRetentionPolicy.none())
            .transactionRetryLimit(5)
            .build();
    AggregateId id = AggregateId.of("UserAccount", "spanner-example");

    SpannerStorage storage = SpannerStorage.open(emulator.client(), settings);
    EventStore<String, String> store = SpannerEventStore.create(storage, config);
    AsyncEventStore<String, String> async = SpannerEventStore.createAsync(storage, config);
    store.persistEvent(UserAccountExample.event(id, 1, "Alice"));
    async
        .persistEventAndSnapshot(
            UserAccountExample.event(id, 2, "Bob"), UserAccountExample.snapshot(2, "Bob"))
        .join();

    assertEquals(Optional.of("Bob"), UserAccountExample.restore(store, id));
    assertEquals(Optional.of("Bob"), UserAccountExample.restoreAsync(async, id).join());
    SnapshotReadResult<String> result = store.getLatestSnapshotById(id).orElseThrow();
    assertEquals(2, result.headSeqNr());
    assertEquals(2, result.snapshot().orElseThrow().seqNr());

    // Reopening on the same tables sees the same data.
    EventStore<String, String> reopened =
        SpannerEventStore.create(SpannerStorage.open(emulator.client(), settings), config);
    assertEquals(Optional.of("Bob"), UserAccountExample.restore(reopened, id));

    ObjectNode evidence = new ObjectMapper().createObjectNode();
    evidence.put("table_prefix", prefix);
    for (String table : new String[] {"journal", "head", "snapshot_current", "snapshot_history"}) {
      evidence.put(table + "_rows", emulator.count(prefix, table));
    }
    assertEquals(2, evidence.get("journal_rows").asLong());
    assertEquals(1, evidence.get("snapshot_current_rows").asLong());
    Path out = Path.of("build/reports/public-api-usage");
    Files.createDirectories(out);
    new ObjectMapper()
        .writerWithDefaultPrettyPrinter()
        .writeValue(out.resolve("spanner.json").toFile(), evidence);
  }
}
