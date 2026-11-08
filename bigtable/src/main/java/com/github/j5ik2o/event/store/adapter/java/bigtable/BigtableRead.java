package com.github.j5ik2o.event.store.adapter.java.bigtable;

import com.github.j5ik2o.event.store.adapter.java.core.AggregateId;
import com.github.j5ik2o.event.store.adapter.java.core.StorageException;
import com.google.cloud.bigtable.data.v2.models.Filters;
import com.google.cloud.bigtable.data.v2.models.Row;
import com.google.protobuf.ByteString;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;

/** Reads: the head first, then the journal range [start, h) (BT-7 step 1, BT-8, BT-9, BT-10). */
final class BigtableRead {
  /** 2^53 - 1, the largest seq_nr of the common contract. */
  private static final long MAX_SEQ_NR = 9_007_199_254_740_991L;

  private BigtableRead() {}

  /** A verified head row. */
  static final class HeadState {
    final long seqNr;
    final byte[] lastEventBytes;
    final BigtableEnvelope.Event lastEvent;
    final boolean hasSnapshot;

    /** The stored current snapshot envelope, or null. */
    final byte[] currentSnapshotBytes;

    final BigtableEnvelope.Snapshot currentSnapshot;

    HeadState(
        long seqNr,
        byte[] lastEventBytes,
        BigtableEnvelope.Event lastEvent,
        boolean hasSnapshot,
        byte[] currentSnapshotBytes,
        BigtableEnvelope.Snapshot currentSnapshot) {
      this.seqNr = seqNr;
      this.lastEventBytes = lastEventBytes;
      this.lastEvent = lastEvent;
      this.hasSnapshot = hasSnapshot;
      this.currentSnapshotBytes = currentSnapshotBytes;
      this.currentSnapshot = currentSnapshot;
    }
  }

  /** The head number and the current snapshot read from one head row. */
  static final class HeadAndSnapshot {
    final long head;
    final BigtableEnvelope.Snapshot snapshot;

    HeadAndSnapshot(long head, BigtableEnvelope.Snapshot snapshot) {
      this.head = head;
      this.snapshot = snapshot;
    }
  }

  /**
   * Reads the head row, or returns null when there is none. A row missing a required cell is not a
   * new aggregate; it is a storage error.
   */
  static HeadState readHead(BigtableStorage storage, ByteString prefix, String aid) {
    Row row =
        BigtableRows.readRow(
            storage.data, storage.config.headTableId(), prefix, BigtableRows.latestCell());
    if (row == null) {
      return null;
    }
    ByteString seqNrCell = BigtableRows.value(row, BigtableRows.HEAD, BigtableRows.SEQ_NR);
    long seqNr = parseHeadSeqNr(seqNrCell);
    ByteString lastEventCell = BigtableRows.value(row, BigtableRows.HEAD, BigtableRows.LAST_EVENT);
    if (lastEventCell == null) {
      throw invalid("the head row has no last event");
    }
    byte[] lastEventBytes = lastEventCell.toByteArray();
    BigtableEnvelope.Event lastEvent = BigtableEnvelope.decodeEvent(lastEventBytes);
    if (!lastEvent.aid().equals(aid) || lastEvent.seqNr() != seqNr) {
      throw invalid("the last event of the head row does not match the head");
    }
    boolean hasSnapshot =
        parseFlag(BigtableRows.value(row, BigtableRows.HEAD, BigtableRows.HAS_SNAPSHOT));
    ByteString currentCell = BigtableRows.value(row, BigtableRows.SNAPSHOT, BigtableRows.CURRENT);
    byte[] currentBytes = currentCell == null ? null : currentCell.toByteArray();
    BigtableEnvelope.Snapshot current =
        currentBytes == null ? null : BigtableEnvelope.decodeSnapshot(currentBytes);
    if (current != null
        && ((hasSnapshot && current.seqNr() != seqNr)
            || (!hasSnapshot && current.seqNr() >= seqNr))) {
      throw invalid("the current snapshot does not match the head seq_nr");
    }
    if (current == null && hasSnapshot) {
      throw invalid("the head row says it has a snapshot but holds none");
    }
    return new HeadState(seqNr, lastEventBytes, lastEvent, hasSnapshot, currentBytes, current);
  }

  /** The head seq_nr is 20 zero-padded ASCII digits in 1..2^53-1. */
  private static long parseHeadSeqNr(ByteString cell) {
    if (cell == null || cell.size() != 20) {
      throw invalid("the head row has a missing or invalid seq_nr");
    }
    String text = cell.toString(StandardCharsets.US_ASCII);
    for (int i = 0; i < text.length(); i++) {
      if (text.charAt(i) < '0' || text.charAt(i) > '9') {
        throw invalid("the head row has a missing or invalid seq_nr");
      }
    }
    try {
      long value = Long.parseLong(text);
      if (value < 1 || value > MAX_SEQ_NR) {
        throw invalid("the head seq_nr is out of range");
      }
      return value;
    } catch (NumberFormatException failure) {
      throw invalid("the head row has a missing or invalid seq_nr");
    }
  }

  static boolean parseFlag(ByteString cell) {
    if (cell != null && cell.equals(BigtableRows.FLAG_TRUE)) {
      return true;
    }
    if (cell != null && cell.equals(BigtableRows.FLAG_FALSE)) {
      return false;
    }
    throw invalid("a has_snapshot flag is missing or invalid");
  }

  /** 20 ASCII digits, zero padded. It never depends on the default locale (BT-D6a). */
  static String formatHeadSeqNr(long seqNr) {
    return "0".repeat(Math.max(0, 20 - Long.toString(seqNr).length())) + Long.toString(seqNr);
  }

  static Optional<HeadAndSnapshot> readSnapshot(BigtableStorage storage, AggregateId id) {
    ByteString prefix = storage.prefix(id.asString());
    HeadState head = readHead(storage, prefix, id.asString());
    storage.hooks.beforeReadSnapshot(id);
    if (head == null) {
      return Optional.empty();
    }
    return Optional.of(new HeadAndSnapshot(head.seqNr, head.currentSnapshot));
  }

  /** The events from {@code max(1, seqNr)} to the head, in order. */
  static List<BigtableEnvelope.Event> readEvents(
      BigtableStorage storage, AggregateId id, long seqNr) {
    String aid = id.asString();
    long start = Math.max(1, seqNr);
    ByteString prefix = storage.prefix(aid);
    // BT-9: fetch the head first and keep using these values for the whole read.
    HeadState head = readHead(storage, prefix, aid);
    storage.hooks.beforeReadEvents(id);
    if (head == null || start > head.seqNr) {
      return new ArrayList<>();
    }
    long h = head.seqNr;
    List<BigtableEnvelope.Event> events = new ArrayList<>();
    if (start < h) {
      events.addAll(readJournal(storage, prefix, aid, start, h));
    }
    events.add(head.lastEvent);
    return events;
  }

  /** The journal rows [start, h). A gap in the range is a storage error (BT-10). */
  private static List<BigtableEnvelope.Event> readJournal(
      BigtableStorage storage, ByteString prefix, String aid, long start, long h) {
    List<Row> rows =
        BigtableRows.readRange(
            storage.data,
            storage.config.journalTableId(),
            BigtableKey.journalRowKey(prefix, start),
            BigtableKey.journalRowKey(prefix, h),
            Filters.FILTERS
                .chain()
                .filter(
                    Filters.FILTERS
                        .chain()
                        .filter(Filters.FILTERS.family().exactMatch(BigtableRows.EVENT))
                        .filter(Filters.FILTERS.qualifier().exactMatch(BigtableRows.ENVELOPE)))
                .filter(BigtableRows.latestCell()));
    if (rows.size() != h - start) {
      throw invalid("the journal has a gap in the requested range");
    }
    List<BigtableEnvelope.Event> events = new ArrayList<>();
    long expected = start;
    for (Row row : rows) {
      OptionalLong seq = BigtableKey.journalSeqNr(prefix, row.getKey());
      if (!seq.isPresent() || seq.getAsLong() != expected) {
        throw invalid("the journal has a gap in the requested range");
      }
      ByteString envelope = BigtableRows.value(row, BigtableRows.EVENT, BigtableRows.ENVELOPE);
      if (envelope == null) {
        throw invalid("a journal row has no event envelope");
      }
      BigtableEnvelope.Event event = BigtableEnvelope.decodeEvent(envelope.toByteArray());
      if (!event.aid().equals(aid) || event.seqNr() != expected) {
        throw invalid("a journal row holds an envelope of another aid or seq_nr");
      }
      events.add(event);
      expected++;
    }
    return events;
  }

  static StorageException invalid(String detail) {
    return new StorageException("invalid bigtable data: " + detail);
  }
}
