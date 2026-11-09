package com.github.j5ik2o.event.store.adapter.java.sqlite;

import com.github.j5ik2o.event.store.adapter.java.core.AggregateId;
import com.github.j5ik2o.event.store.adapter.java.core.StorageException;
import com.github.j5ik2o.event.store.adapter.java.sqlite.SqliteAppend.StoredSnapshot;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import javax.annotation.Nullable;

/** 読み取り。head と本体は、同じ明示の読み取りトランザクションで読む。 */
final class SqliteRead {
  private SqliteRead() {}

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

  static Optional<HeadAndSnapshot> readSnapshot(SqliteStorage storage, AggregateId id) {
    storage.lock.lock();
    try {
      Connection c = storage.connectionForOperation();
      begin(c);
      try {
        Optional<Long> head = readHead(c, id);
        storage.hooks.beforeReadSnapshot(id);
        Optional<HeadAndSnapshot> result = Optional.empty();
        if (head.isPresent()) {
          StoredSnapshot snapshot = null;
          try (PreparedStatement p =
              c.prepareStatement(
                  "SELECT seq_nr, manifest, payload FROM snapshot_current WHERE aid = ?")) {
            p.setString(1, id.asString());
            try (ResultSet rs = p.executeQuery()) {
              if (rs.next()) {
                snapshot = new StoredSnapshot(rs.getLong(1), rs.getString(2), rs.getBytes(3));
              }
            }
          }
          result = Optional.of(new HeadAndSnapshot(head.get(), snapshot));
        }
        SqliteTransactions.commit(c);
        return result;
      } catch (SQLException failure) {
        SqliteTransactions.rollbackQuietly(c, failure);
        throw SqliteTransactions.storage("failed to read the latest snapshot from sqlite", failure);
      } catch (RuntimeException failure) {
        SqliteTransactions.rollbackQuietly(c, failure);
        throw failure;
      }
    } finally {
      storage.lock.unlock();
    }
  }

  static List<StoredEvent> readEvents(SqliteStorage storage, AggregateId id, long seqNr) {
    long start = Math.max(1, seqNr);
    storage.lock.lock();
    try {
      Connection c = storage.connectionForOperation();
      begin(c);
      try {
        Optional<Long> head = readHead(c, id);
        storage.hooks.beforeReadEvents(id);
        List<StoredEvent> events = new ArrayList<>();
        if (head.isPresent()) {
          long h = head.get();
          try (PreparedStatement p =
              c.prepareStatement(
                  "SELECT seq_nr, occurred_at_ns, manifest, payload FROM journal"
                      + " WHERE aid = ? AND seq_nr >= ? AND seq_nr <= ? ORDER BY seq_nr")) {
            p.setString(1, id.asString());
            p.setLong(2, start);
            p.setLong(3, h);
            try (ResultSet rs = p.executeQuery()) {
              while (rs.next()) {
                events.add(
                    new StoredEvent(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getBytes(4)));
              }
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
        SqliteTransactions.commit(c);
        return events;
      } catch (SQLException failure) {
        SqliteTransactions.rollbackQuietly(c, failure);
        throw SqliteTransactions.storage("failed to read events from sqlite", failure);
      } catch (RuntimeException failure) {
        SqliteTransactions.rollbackQuietly(c, failure);
        throw failure;
      }
    } finally {
      storage.lock.unlock();
    }
  }

  private static void begin(Connection c) {
    try {
      SqliteTransactions.begin(c, false);
    } catch (SQLException failure) {
      throw SqliteTransactions.storage("failed to begin sqlite read transaction", failure);
    }
  }

  private static Optional<Long> readHead(Connection c, AggregateId id) throws SQLException {
    try (PreparedStatement p = c.prepareStatement("SELECT seq_nr FROM head WHERE aid = ?")) {
      p.setString(1, id.asString());
      try (ResultSet rs = p.executeQuery()) {
        return rs.next() ? Optional.of(rs.getLong(1)) : Optional.empty();
      }
    }
  }
}
