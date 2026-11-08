package com.github.j5ik2o.event.store.adapter.java.spanner;

import com.github.j5ik2o.event.store.adapter.java.core.AggregateId;
import com.github.j5ik2o.event.store.adapter.java.core.StorageException;
import com.github.j5ik2o.event.store.adapter.java.spanner.SpannerAppend.StoredSnapshot;
import com.google.cloud.ByteArray;
import com.google.cloud.spanner.ReadOnlyTransaction;
import com.google.cloud.spanner.ResultSet;
import com.google.cloud.spanner.SpannerException;
import com.google.cloud.spanner.Statement;
import com.google.cloud.spanner.TimestampBound;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import javax.annotation.Nullable;

/** Reads with one strong read-only transaction, so every row comes from the same timestamp. */
final class SpannerRead {
  private SpannerRead() {}

  static final class HeadAndSnapshot {
    final long head;
    @Nullable final StoredSnapshot snapshot;

    HeadAndSnapshot(long head, @Nullable StoredSnapshot snapshot) {
      this.head = head;
      this.snapshot = snapshot;
    }
  }

  static final class StoredEvent {
    final long seqNr;
    final long occurredAtNanos;
    final String manifest;
    final byte[] payload;

    StoredEvent(long seqNr, long occurredAtNanos, String manifest, byte[] payload) {
      this.seqNr = seqNr;
      this.occurredAtNanos = occurredAtNanos;
      this.manifest = manifest;
      this.payload = payload;
    }
  }

  static Optional<HeadAndSnapshot> readSnapshot(SpannerStorage storage, AggregateId id) {
    SpannerKey key = SpannerKey.of(id);
    ByteArray hashPrefix = ByteArray.copyFrom(key.hashPrefix());
    SpannerTableNames names = storage.config.tableNames;
    try (ReadOnlyTransaction tx = storage.client.readOnlyTransaction(TimestampBound.strong())) {
      Optional<Long> head = readHead(tx, names, hashPrefix, key.aid());
      storage.hooks.beforeReadSnapshot(id);
      if (!head.isPresent()) {
        return Optional.empty();
      }
      StoredSnapshot snapshot = null;
      try (ResultSet rs =
          tx.executeQuery(
              Statement.newBuilder(
                      "SELECT seq_nr, manifest, payload FROM "
                          + names.snapshotCurrent
                          + " WHERE hash_prefix = @hp AND aid = @aid")
                  .bind("hp")
                  .to(hashPrefix)
                  .bind("aid")
                  .to(key.aid())
                  .build())) {
        if (rs.next()) {
          snapshot =
              new StoredSnapshot(rs.getLong(0), rs.getString(1), rs.getBytes(2).toByteArray());
        }
      }
      return Optional.of(new HeadAndSnapshot(head.get(), snapshot));
    } catch (SpannerException failure) {
      throw new StorageException("failed to read the latest snapshot from spanner", failure);
    }
  }

  static List<StoredEvent> readEvents(SpannerStorage storage, AggregateId id, long seqNr) {
    long start = Math.max(1, seqNr);
    SpannerKey key = SpannerKey.of(id);
    ByteArray hashPrefix = ByteArray.copyFrom(key.hashPrefix());
    SpannerTableNames names = storage.config.tableNames;
    try (ReadOnlyTransaction tx = storage.client.readOnlyTransaction(TimestampBound.strong())) {
      Optional<Long> head = readHead(tx, names, hashPrefix, key.aid());
      storage.hooks.beforeReadEvents(id);
      List<StoredEvent> events = new ArrayList<>();
      if (head.isPresent()) {
        long h = head.get();
        try (ResultSet rs =
            tx.executeQuery(
                Statement.newBuilder(
                        "SELECT seq_nr, occurred_at_ns, manifest, payload FROM "
                            + names.journal
                            + " WHERE hash_prefix = @hp AND aid = @aid AND seq_nr >= @start"
                            + " AND seq_nr <= @head ORDER BY seq_nr")
                    .bind("hp")
                    .to(hashPrefix)
                    .bind("aid")
                    .to(key.aid())
                    .bind("start")
                    .to(start)
                    .bind("head")
                    .to(h)
                    .build())) {
          while (rs.next()) {
            events.add(
                new StoredEvent(
                    rs.getLong(0), rs.getLong(1), rs.getString(2), rs.getBytes(3).toByteArray()));
          }
        }
        long expected = start > h ? 0 : h - start + 1;
        boolean contiguous = events.size() == expected;
        for (int i = 0; contiguous && i < events.size(); i++) {
          contiguous = events.get(i).seqNr == start + i;
        }
        if (!contiguous) {
          throw new StorageException("journal has a gap in the requested range");
        }
      }
      return events;
    } catch (SpannerException failure) {
      throw new StorageException("failed to read events from spanner", failure);
    }
  }

  private static Optional<Long> readHead(
      ReadOnlyTransaction tx, SpannerTableNames names, ByteArray hashPrefix, String aid) {
    try (ResultSet rs =
        tx.executeQuery(
            Statement.newBuilder(
                    "SELECT seq_nr FROM " + names.head + " WHERE hash_prefix = @hp AND aid = @aid")
                .bind("hp")
                .to(hashPrefix)
                .bind("aid")
                .to(aid)
                .build())) {
      return rs.next() ? Optional.of(rs.getLong(0)) : Optional.empty();
    }
  }
}
