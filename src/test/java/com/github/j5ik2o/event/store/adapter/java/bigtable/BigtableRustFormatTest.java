package com.github.j5ik2o.event.store.adapter.java.bigtable;

import static com.github.j5ik2o.event.store.adapter.java.bigtable.BigtableTestFixtures.AID;
import static com.github.j5ik2o.event.store.adapter.java.bigtable.BigtableTestFixtures.EMULATOR;
import static com.github.j5ik2o.event.store.adapter.java.bigtable.BigtableTestFixtures.deleteStore;
import static com.github.j5ik2o.event.store.adapter.java.bigtable.BigtableTestFixtures.event;
import static com.github.j5ik2o.event.store.adapter.java.bigtable.BigtableTestFixtures.headSeqNr;
import static com.github.j5ik2o.event.store.adapter.java.bigtable.BigtableTestFixtures.journalKey;
import static com.github.j5ik2o.event.store.adapter.java.bigtable.BigtableTestFixtures.noneStore;
import static com.github.j5ik2o.event.store.adapter.java.bigtable.BigtableTestFixtures.open;
import static com.github.j5ik2o.event.store.adapter.java.bigtable.BigtableTestFixtures.prefix;
import static com.github.j5ik2o.event.store.adapter.java.bigtable.BigtableTestFixtures.snapshot;
import static com.github.j5ik2o.event.store.adapter.java.bigtable.BigtableTestFixtures.store;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import com.github.j5ik2o.event.store.adapter.java.core.EventEnvelope;
import com.github.j5ik2o.event.store.adapter.java.core.EventStore;
import com.github.j5ik2o.event.store.adapter.java.core.RetentionPolicy;
import com.github.j5ik2o.event.store.adapter.java.core.SnapshotReadResult;
import com.google.protobuf.ByteString;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;

/**
 * Reads and writes rows in the shape of the Rust v5 implementation. The rows below are assembled
 * from the normative text with byte arrays written in this file, independent of the Java code under
 * test, so the layout cannot drift into a private one (acceptance condition 4).
 */
class BigtableRustFormatTest {
  private static byte[] bytes(int... values) {
    byte[] out = new byte[values.length];
    for (int i = 0; i < values.length; i++) out[i] = (byte) values[i];
    return out;
  }

  private static byte[] concat(byte[]... parts) {
    int n = 0;
    for (byte[] p : parts) n += p.length;
    byte[] out = new byte[n];
    int at = 0;
    for (byte[] p : parts) {
      System.arraycopy(p, 0, out, at, p.length);
      at += p.length;
    }
    return out;
  }

  private static byte[] u64(long v) {
    byte[] out = new byte[8];
    for (int i = 0; i < 8; i++) out[i] = (byte) (v >>> (56 - 8 * i));
    return out;
  }

  private static byte[] u32(int v) {
    return bytes(v >>> 24, v >>> 16, v >>> 8, v);
  }

  private static byte[] utf8(String s) {
    return s.getBytes(StandardCharsets.UTF_8);
  }

  /** 0x01 || U16 len || aid || U64 seq || I64 ns || U32 len || manifest || U32 len || payload. */
  private static ByteString eventEnvelope(
      String aid, long seqNr, long ns, String manifest, String payload) {
    byte[] a = utf8(aid);
    return ByteString.copyFrom(
        concat(
            bytes(0x01, a.length >>> 8, a.length),
            a,
            u64(seqNr),
            u64(ns),
            u32(utf8(manifest).length),
            utf8(manifest),
            u32(utf8(payload).length),
            utf8(payload)));
  }

  private static ByteString snapshotEnvelope(long seqNr, String manifest, String payload) {
    return ByteString.copyFrom(
        concat(
            bytes(0x01),
            u64(seqNr),
            u32(utf8(manifest).length),
            utf8(manifest),
            u32(utf8(payload).length),
            utf8(payload)));
  }

  private static void writeJournalRow(
      BigtableEmulator.Tables t, long seqNr, long ns, boolean snapshot) {
    ByteString key = journalKey("Order-1", seqNr);
    EMULATOR.put(
        t.journal,
        key,
        "event",
        "envelope",
        eventEnvelope("Order-1", seqNr, ns, "event", "\"event-" + seqNr + "\""));
    EMULATOR.put(
        t.journal, key, "event", "has_snapshot", ByteString.copyFromUtf8(snapshot ? "1" : "0"));
  }

  private static void writeHead(
      BigtableEmulator.Tables t, long seqNr, long ns, boolean hasSnapshot) {
    ByteString key = prefix("Order-1");
    EMULATOR.put(t.head, key, "head", "seq_nr", ByteString.copyFromUtf8(headSeqNr(seqNr)));
    EMULATOR.put(
        t.head,
        key,
        "head",
        "last_event",
        eventEnvelope("Order-1", seqNr, ns, "event", "\"event-" + seqNr + "\""));
    EMULATOR.put(
        t.head, key, "head", "has_snapshot", ByteString.copyFromUtf8(hasSnapshot ? "1" : "0"));
  }

  @Test
  void javaReadsEventsAndSnapshotWrittenInTheRustShape() {
    BigtableEmulator.Tables t = deleteStore(2);
    long ns = 1_700_000_000_123_456_789L;
    writeJournalRow(t, 1, ns, true);
    writeHead(t, 2, ns, true);
    EMULATOR.put(
        t.head,
        prefix("Order-1"),
        "snapshot",
        "current",
        snapshotEnvelope(2, "snapshot", "\"snapshot-2\""));
    EventStore<String, String> store = store(open(t, RetentionPolicy.delete(2)));

    List<EventEnvelope<String>> events = store.getEventsByIdSinceSeqNr(AID, 0);
    SnapshotReadResult<String> snapshot = store.getLatestSnapshotById(AID).orElseThrow();

    assertEquals(2, events.size());
    assertEquals("event-1", events.get(0).payload());
    assertEquals("event-2", events.get(1).payload());
    assertEquals(Instant.ofEpochSecond(1_700_000_000L, 123_456_789), events.get(0).occurredAt());
    assertEquals(2, snapshot.headSeqNr());
    assertEquals("snapshot-2", snapshot.snapshot().orElseThrow().aggregate());
  }

  @Test
  void javaAppendsAfterARowThatTheRustImplementationWroteAndCompletesTheJournal() {
    BigtableEmulator.Tables t = noneStore();
    writeHead(t, 1, 0L, false);

    store(open(t, RetentionPolicy.none())).persistEvent(event(2));

    assertArrayEquals(
        eventEnvelope("Order-1", 1, 0L, "event", "\"event-1\"").toByteArray(),
        EMULATOR.bytes(t.journal, journalKey("Order-1", 1), "event", "envelope").toByteArray());
    assertEquals("0", EMULATOR.text(t.journal, journalKey("Order-1", 1), "event", "has_snapshot"));
    assertEquals(headSeqNr(2), EMULATOR.text(t.head, prefix("Order-1"), "head", "seq_nr"));
  }

  @Test
  void rowsWrittenByJavaAreByteIdenticalToTheNormativeLayout() {
    BigtableEmulator.Tables t = deleteStore(2);
    Instant occurredAt = Instant.ofEpochSecond(1_700_000_000L, 123_456_789);
    EventStore<String, String> store = store(open(t, RetentionPolicy.delete(2)));
    store.persistEventAndSnapshot(event(AID, 1, occurredAt), snapshot(1));
    store.persistEventAndSnapshot(event(AID, 2, occurredAt), snapshot(2));

    long ns = 1_700_000_000_123_456_789L;
    ByteString head = prefix("Order-1");
    assertEquals(headSeqNr(2), EMULATOR.text(t.head, head, "head", "seq_nr"));
    assertEquals("1", EMULATOR.text(t.head, head, "head", "has_snapshot"));
    assertEquals(
        eventEnvelope("Order-1", 2, ns, "event", "\"event-2\""),
        EMULATOR.bytes(t.head, head, "head", "last_event"));
    assertEquals(
        snapshotEnvelope(2, "snapshot", "\"snapshot-2\""),
        EMULATOR.bytes(t.head, head, "snapshot", "current"));
    ByteString row1 = journalKey("Order-1", 1);
    assertEquals(
        eventEnvelope("Order-1", 1, ns, "event", "\"event-1\""),
        EMULATOR.bytes(t.journal, row1, "event", "envelope"));
    assertEquals("1", EMULATOR.text(t.journal, row1, "event", "has_snapshot"));
    assertEquals("active", EMULATOR.text(t.journal, row1, "history", "state"));
    assertEquals(
        snapshotEnvelope(1, "snapshot", "\"snapshot-1\""),
        EMULATOR.bytes(t.journal, row1, "history", "envelope"));
  }

  @Test
  void javaKeepsReadingHistoryWrittenByRustWithRetiredAndActiveRows() {
    BigtableEmulator.Tables t = deleteStore(1);
    writeJournalRow(t, 1, 0L, true);
    EMULATOR.put(
        t.journal,
        journalKey("Order-1", 1),
        "history",
        "state",
        ByteString.copyFromUtf8("retired"));
    writeJournalRow(t, 2, 0L, true);
    EMULATOR.put(
        t.journal, journalKey("Order-1", 2), "history", "state", ByteString.copyFromUtf8("active"));
    EMULATOR.put(
        t.journal,
        journalKey("Order-1", 2),
        "history",
        "envelope",
        snapshotEnvelope(2, "snapshot", "\"snapshot-2\""));
    writeHead(t, 3, 0L, true);
    EMULATOR.put(
        t.head,
        prefix("Order-1"),
        "snapshot",
        "current",
        snapshotEnvelope(3, "snapshot", "\"snapshot-3\""));
    EventStore<String, String> store = store(open(t, RetentionPolicy.delete(1)));

    store.persistEventAndSnapshot(event(4), snapshot(4));

    assertEquals("retired", EMULATOR.text(t.journal, journalKey("Order-1", 1), "history", "state"));
    assertEquals("retired", EMULATOR.text(t.journal, journalKey("Order-1", 2), "history", "state"));
    assertEquals("retired", EMULATOR.text(t.journal, journalKey("Order-1", 3), "history", "state"));
    assertEquals(4, store.getEventsByIdSinceSeqNr(AID, 1).size());
  }

  /**
   * The first default locale whose zero digit is not ASCII '0'. A locale is searched at run time
   * because the available digits differ between JDK versions; a missing one fails the test.
   */
  private static Locale nonAsciiDigitLocale() {
    for (String tag : new String[] {"ar-EG", "ar-EG-u-nu-arab", "th-TH-u-nu-thai", "fa-IR"}) {
      Locale candidate = Locale.forLanguageTag(tag);
      if (!"0".equals(String.format(candidate, "%d", 0))) return candidate;
    }
    throw new AssertionError("no locale with a non-ASCII zero digit on this JDK");
  }

  private static void withDefaultLocale(Runnable body) {
    Locale all = Locale.getDefault();
    Locale display = Locale.getDefault(Locale.Category.DISPLAY);
    Locale format = Locale.getDefault(Locale.Category.FORMAT);
    try {
      Locale.setDefault(nonAsciiDigitLocale());
      assertNotEquals(
          "0", String.format("%d", 0), "the precondition: the default digits are not ASCII");
      body.run();
    } finally {
      Locale.setDefault(all);
      Locale.setDefault(Locale.Category.DISPLAY, display);
      Locale.setDefault(Locale.Category.FORMAT, format);
    }
  }

  @Test
  void theHeadSeqNrIsTwentyAsciiDigitsWhateverTheDefaultLocale() {
    BigtableEmulator.Tables t = noneStore();
    withDefaultLocale(
        () -> {
          EventStore<String, String> store = store(open(t, RetentionPolicy.none()));

          store.persistEvent(event(1));

          ByteString cell = EMULATOR.bytes(t.head, prefix("Order-1"), "head", "seq_nr");
          assertEquals(20, cell.size());
          assertEquals(ByteString.copyFrom(headSeqNr(1), StandardCharsets.US_ASCII), cell);
          assertEquals(1, store.getEventsByIdSinceSeqNr(AID, 1).size());
          store.persistEvent(event(2));
          assertEquals(
              ByteString.copyFrom(headSeqNr(2), StandardCharsets.US_ASCII),
              EMULATOR.bytes(t.head, prefix("Order-1"), "head", "seq_nr"));
        });
  }

  @Test
  void anAsciiHeadWrittenByAnotherWriterIsUpdatedWhateverTheDefaultLocale() {
    BigtableEmulator.Tables t = noneStore();
    writeHead(t, 1, 0L, false);
    withDefaultLocale(
        () -> {
          store(open(t, RetentionPolicy.none())).persistEvent(event(2));

          assertEquals(
              ByteString.copyFrom(headSeqNr(2), StandardCharsets.US_ASCII),
              EMULATOR.bytes(t.head, prefix("Order-1"), "head", "seq_nr"));
        });
  }
}
