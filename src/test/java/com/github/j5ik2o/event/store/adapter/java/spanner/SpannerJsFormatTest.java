package com.github.j5ik2o.event.store.adapter.java.spanner;

import static com.github.j5ik2o.event.store.adapter.java.spanner.SpannerTestFixtures.AID;
import static com.github.j5ik2o.event.store.adapter.java.spanner.SpannerTestFixtures.EMULATOR;
import static com.github.j5ik2o.event.store.adapter.java.spanner.SpannerTestFixtures.event;
import static com.github.j5ik2o.event.store.adapter.java.spanner.SpannerTestFixtures.noneStore;
import static com.github.j5ik2o.event.store.adapter.java.spanner.SpannerTestFixtures.open;
import static com.github.j5ik2o.event.store.adapter.java.spanner.SpannerTestFixtures.snapshot;
import static com.github.j5ik2o.event.store.adapter.java.spanner.SpannerTestFixtures.store;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.github.j5ik2o.event.store.adapter.java.core.EventEnvelope;
import com.github.j5ik2o.event.store.adapter.java.core.EventStore;
import com.github.j5ik2o.event.store.adapter.java.core.SnapshotReadResult;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Reads and writes the rows of the JavaScript v5 implementation. The table layout comes from {@link
 * SpannerEmulator}, which copies the JavaScript DDL; the hash and the INSERT shapes below are
 * independent of the Java code under test.
 */
class SpannerJsFormatTest {
  /** FNV-1a 64, written independently of SpannerKey, as the 8 big-endian bytes in hex. */
  private static String fnv1a64Hex(String text) {
    long hash = 0xcbf29ce484222325L;
    for (byte b : text.getBytes(StandardCharsets.UTF_8)) {
      hash ^= (b & 0xffL);
      hash *= 0x100000001b3L;
    }
    return String.format("%016x", hash);
  }

  private static String key(String aid) {
    return "FROM_HEX('" + fnv1a64Hex(aid) + "'), '" + aid + "'";
  }

  @Test
  void javaReadsEventsAndSnapshotWrittenInTheJavaScriptShape() {
    String prefix = noneStore();
    // The JavaScript implementation stores milliseconds multiplied by 1,000,000.
    long nanos = 1_700_000_000_123L * 1_000_000L;
    for (int seq = 1; seq <= 2; seq++) {
      EMULATOR.execute(
          "INSERT INTO "
              + prefix
              + "_journal (hash_prefix, aid, seq_nr, occurred_at_ns, manifest, payload) VALUES ("
              + key("Order-1")
              + ", "
              + seq
              + ", "
              + nanos
              + ", 'event', b'\"event-"
              + seq
              + "\"')");
    }
    EMULATOR.execute(
        "INSERT INTO "
            + prefix
            + "_head (hash_prefix, aid, type_name, seq_nr, event_occurred_at_ns, event_manifest,"
            + " event_payload) VALUES ("
            + key("Order-1")
            + ", 'Order', 2, "
            + nanos
            + ", 'event', b'\"event-2\"')");
    EMULATOR.execute(
        "INSERT INTO "
            + prefix
            + "_snapshot_current (hash_prefix, aid, seq_nr, manifest, payload) VALUES ("
            + key("Order-1")
            + ", 2, 'snapshot', b'\"snapshot-2\"')");
    EventStore<String, String> store = store(open(prefix, SpannerRetentionPolicy.none()));

    List<EventEnvelope<String>> events = store.getEventsByIdSinceSeqNr(AID, 0);
    SnapshotReadResult<String> snapshot = store.getLatestSnapshotById(AID).orElseThrow();

    assertEquals(2, events.size());
    assertEquals("event-1", events.get(0).payload());
    assertEquals(Instant.ofEpochMilli(1_700_000_000_123L), events.get(0).occurredAt());
    assertEquals(2, snapshot.headSeqNr());
    assertEquals("snapshot-2", snapshot.snapshot().orElseThrow().aggregate());
  }

  @Test
  void javaAppendsAfterARowThatTheJavaScriptImplementationWrote() {
    String prefix = noneStore();
    EMULATOR.execute(
        "INSERT INTO "
            + prefix
            + "_journal (hash_prefix, aid, seq_nr, occurred_at_ns, manifest, payload) VALUES ("
            + key("Order-1")
            + ", 1, 0, 'event', b'\"event-1\"')");
    EMULATOR.execute(
        "INSERT INTO "
            + prefix
            + "_head (hash_prefix, aid, type_name, seq_nr, event_occurred_at_ns, event_manifest,"
            + " event_payload) VALUES ("
            + key("Order-1")
            + ", 'Order', 1, 0, 'event', b'\"event-1\"')");

    store(open(prefix, SpannerRetentionPolicy.none())).persistEvent(event(2));

    assertEquals(
        List.of(1L, 2L),
        EMULATOR.longs("SELECT seq_nr FROM " + prefix + "_journal ORDER BY seq_nr"));
    assertEquals(List.of(2L), EMULATOR.longs("SELECT seq_nr FROM " + prefix + "_head"));
  }

  @Test
  void rowsWrittenByJavaHaveTheSpecifiedHashPrefixNanosecondsAndPayloadBytes() {
    String prefix = noneStore();
    Instant occurredAt = Instant.ofEpochSecond(1_700_000_000L, 123_456_789);
    store(open(prefix, SpannerRetentionPolicy.none()))
        .persistEventAndSnapshot(event(AID, 1, occurredAt), snapshot(1));

    assertEquals(
        fnv1a64Hex("Order-1"),
        EMULATOR.string("SELECT TO_HEX(hash_prefix) FROM " + prefix + "_journal"));
    assertEquals(
        fnv1a64Hex("Order-1"),
        EMULATOR.string("SELECT TO_HEX(hash_prefix) FROM " + prefix + "_head"));
    assertEquals(
        fnv1a64Hex("Order-1"),
        EMULATOR.string("SELECT TO_HEX(hash_prefix) FROM " + prefix + "_snapshot_current"));
    assertEquals(
        "1700000000123456789",
        EMULATOR.string("SELECT CAST(occurred_at_ns AS STRING) FROM " + prefix + "_journal"));
    assertEquals(
        "1700000000123456789",
        EMULATOR.string("SELECT CAST(event_occurred_at_ns AS STRING) FROM " + prefix + "_head"));
    assertEquals(
        "\"event-1\"",
        EMULATOR.string("SELECT CAST(payload AS STRING) FROM " + prefix + "_journal"));
    assertEquals(
        "\"snapshot-1\"",
        EMULATOR.string("SELECT CAST(payload AS STRING) FROM " + prefix + "_snapshot_current"));
    assertEquals("event", EMULATOR.string("SELECT manifest FROM " + prefix + "_journal"));
  }
}
