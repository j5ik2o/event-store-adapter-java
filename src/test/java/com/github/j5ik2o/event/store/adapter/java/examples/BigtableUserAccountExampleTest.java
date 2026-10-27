package com.github.j5ik2o.event.store.adapter.java.examples;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.j5ik2o.event.store.adapter.java.bigtable.BigtableEmulator;
import com.github.j5ik2o.event.store.adapter.java.bigtable.BigtableEventStore;
import com.github.j5ik2o.event.store.adapter.java.bigtable.BigtableStorage;
import com.github.j5ik2o.event.store.adapter.java.bigtable.BigtableStorageConfig;
import com.github.j5ik2o.event.store.adapter.java.core.AggregateId;
import com.github.j5ik2o.event.store.adapter.java.core.AsyncEventStore;
import com.github.j5ik2o.event.store.adapter.java.core.EventStore;
import com.github.j5ik2o.event.store.adapter.java.core.EventStoreConfig;
import com.github.j5ik2o.event.store.adapter.java.core.RetentionPolicy;
import com.github.j5ik2o.event.store.adapter.java.core.SnapshotReadResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The README Bigtable example, run from outside the library package through public types only. The
 * tables are created outside the library, as the schema document tells an operator to do, and the
 * configuration rows are written by the public {@code BigtableStorage.initialize}.
 */
class BigtableUserAccountExampleTest {
  @Test
  void readmeBigtableExampleWorksThroughPublicTypesOnly() throws Exception {
    BigtableEmulator emulator = BigtableEmulator.shared();
    BigtableEmulator.Tables tables = emulator.createTables();
    EventStoreConfig<String, String> config = UserAccountExample.config();
    BigtableStorageConfig settings =
        BigtableStorageConfig.builder()
            .journalTableId(tables.journal)
            .headTableId(tables.head)
            .retentionPolicy(RetentionPolicy.none())
            .build();
    AggregateId id = AggregateId.of("UserAccount", "bigtable-example");

    BigtableStorage.initialize(emulator.data(), settings);
    BigtableStorage storage = BigtableStorage.open(emulator.data(), emulator.admin(), settings);
    EventStore<String, String> store = BigtableEventStore.create(storage, config);
    AsyncEventStore<String, String> async = BigtableEventStore.createAsync(storage, config);
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
        BigtableEventStore.create(
            BigtableStorage.open(emulator.data(), emulator.admin(), settings), config);
    assertEquals(Optional.of("Bob"), UserAccountExample.restore(reopened, id));

    ObjectNode evidence = new ObjectMapper().createObjectNode();
    evidence.put("journal_table_id", tables.journal);
    evidence.put("head_table_id", tables.head);
    evidence.put("journal_rows", emulator.rows(tables.journal).size());
    evidence.put("head_rows", emulator.rows(tables.head).size());
    // Each table holds its configuration row. The journal also holds rows 1 and 2 (the second is
    // the tail written out after the commit); the head holds the aggregate.
    assertEquals(3, evidence.get("journal_rows").asLong());
    assertEquals(2, evidence.get("head_rows").asLong());
    Path out = Path.of("build/reports/public-api-usage");
    Files.createDirectories(out);
    new ObjectMapper()
        .writerWithDefaultPrettyPrinter()
        .writeValue(out.resolve("bigtable.json").toFile(), evidence);
  }
}
