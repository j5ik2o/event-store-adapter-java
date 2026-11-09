package com.github.j5ik2o.event.store.adapter.java.sqlite;

import com.github.j5ik2o.event.store.adapter.java.core.AggregateId;
import com.github.j5ik2o.event.store.adapter.java.core.ContractViolationException;
import com.github.j5ik2o.event.store.adapter.java.core.EventEnvelope;
import com.github.j5ik2o.event.store.adapter.java.core.OptimisticLockException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.OptionalLong;
import javax.annotation.Nullable;
import org.sqlite.SQLiteException;

/** 追記。BEGIN IMMEDIATE で書き込みロックを取り、head を照合し、COMMIT を確定点とする。 */
final class SqliteAppend {
  private SqliteAppend() {}

  static final class StoredSnapshot {
    final long seqNr;
    final String manifest;
    final byte[] payload;

    StoredSnapshot(long seqNr, String manifest, byte[] payload) {
      this.seqNr = seqNr;
      this.manifest = manifest;
      this.payload = payload;
    }
  }

  static void append(
      SqliteStorage storage,
      EventEnvelope<?> event,
      byte[] payload,
      @Nullable StoredSnapshot snapshot) {
    AggregateId id = event.aggregateId();
    storage.lock.lock();
    try {
      Connection c = storage.connectionForOperation();
      try {
        SqliteTransactions.begin(c, true);
      } catch (SQLException failure) {
        throw SqliteTransactions.storage(beginMessage(failure), failure);
      }
      try {
        storage.hooks.afterBeginImmediate(id);
        write(storage, c, event, payload, snapshot);
        storage.hooks.beforeCommit(id);
        SqliteTransactions.commit(c);
      } catch (SQLException failure) {
        SqliteTransactions.rollbackQuietly(c, failure);
        throw SqliteTransactions.storage("failed to append to sqlite", failure);
      } catch (RuntimeException failure) {
        SqliteTransactions.rollbackQuietly(c, failure);
        throw failure;
      }
    } finally {
      storage.lock.unlock();
    }
  }

  private static void write(
      SqliteStorage storage,
      Connection c,
      EventEnvelope<?> event,
      byte[] payload,
      StoredSnapshot snapshot)
      throws SQLException {
    AggregateId id = event.aggregateId();
    String aid = id.asString();
    long seqNr = event.seqNr();
    long nanos = epochNanoseconds(event.occurredAt());
    boolean headExists = false;
    long head = 0;
    try (PreparedStatement p = c.prepareStatement("SELECT seq_nr FROM head WHERE aid = ?")) {
      p.setString(1, aid);
      try (ResultSet rs = p.executeQuery()) {
        if (rs.next()) {
          headExists = true;
          head = rs.getLong(1);
        }
      }
    }
    if (seqNr <= head) {
      throw new OptimisticLockException(id, seqNr, OptionalLong.of(head), null);
    }
    if (seqNr != head + 1) {
      throw new ContractViolationException(
          "W-8", OptionalLong.of(seqNr), "event sequence must immediately follow the head");
    }
    OptionalLong knownHead = headExists ? OptionalLong.of(head) : OptionalLong.empty();
    try (PreparedStatement p =
        c.prepareStatement(
            "INSERT INTO journal (aid, seq_nr, occurred_at_ns, manifest, payload)"
                + " VALUES (?, ?, ?, ?, ?)")) {
      p.setString(1, aid);
      p.setLong(2, seqNr);
      p.setLong(3, nanos);
      p.setString(4, event.manifest());
      p.setBytes(5, payload);
      p.executeUpdate();
    } catch (SQLException failure) {
      throw conflictOrRethrow(failure, id, seqNr, knownHead);
    }
    if (headExists) {
      try (PreparedStatement p =
          c.prepareStatement(
              "UPDATE head SET seq_nr = ?, event_occurred_at_ns = ?, event_manifest = ?,"
                  + " event_payload = ? WHERE aid = ?")) {
        p.setLong(1, seqNr);
        p.setLong(2, nanos);
        p.setString(3, event.manifest());
        p.setBytes(4, payload);
        p.setString(5, aid);
        p.executeUpdate();
      }
    } else {
      try (PreparedStatement p =
          c.prepareStatement(
              "INSERT INTO head (aid, type_name, seq_nr, event_occurred_at_ns, event_manifest,"
                  + " event_payload) VALUES (?, ?, ?, ?, ?, ?)")) {
        p.setString(1, aid);
        p.setString(2, id.typeName());
        p.setLong(3, seqNr);
        p.setLong(4, nanos);
        p.setString(5, event.manifest());
        p.setBytes(6, payload);
        p.executeUpdate();
      } catch (SQLException failure) {
        throw conflictOrRethrow(failure, id, seqNr, knownHead);
      }
    }
    if (snapshot != null) {
      try (PreparedStatement p =
          c.prepareStatement(
              "INSERT INTO snapshot_current (aid, seq_nr, manifest, payload) VALUES (?, ?, ?, ?)"
                  + " ON CONFLICT(aid) DO UPDATE SET seq_nr = excluded.seq_nr,"
                  + " manifest = excluded.manifest, payload = excluded.payload")) {
        bindSnapshot(p, aid, snapshot);
        p.executeUpdate();
      }
      if (storage.policy.keepCount().isPresent()) {
        try (PreparedStatement p =
            c.prepareStatement(
                "INSERT INTO snapshot_history (aid, seq_nr, manifest, payload)"
                    + " VALUES (?, ?, ?, ?)")) {
          bindSnapshot(p, aid, snapshot);
          p.executeUpdate();
        }
      }
    }
  }

  private static void bindSnapshot(PreparedStatement p, String aid, StoredSnapshot snapshot)
      throws SQLException {
    p.setString(1, aid);
    p.setLong(2, snapshot.seqNr);
    p.setString(3, snapshot.manifest);
    p.setBytes(4, snapshot.payload);
  }

  /** 主キー重複だけを楽観ロックにする。それ以外の失敗は呼び出し元で保存先エラーにする。 */
  private static SQLException conflictOrRethrow(
      SQLException failure, AggregateId id, long seqNr, OptionalLong head) {
    if (SqliteTransactions.isPrimaryKeyViolation(failure)) {
      throw new OptimisticLockException(id, seqNr, head, failure);
    }
    return failure;
  }

  private static String beginMessage(SQLException failure) {
    if (failure instanceof SQLiteException) {
      int code = ((SQLiteException) failure).getResultCode().code & 0xFF;
      if (code == 5 || code == 6) {
        return "sqlite write lock wait exceeded";
      }
    }
    return "failed to begin sqlite write transaction";
  }

  static long epochNanoseconds(Instant instant) {
    long seconds = instant.getEpochSecond();
    return seconds >= 0
        ? Math.addExact(Math.multiplyExact(seconds, 1_000_000_000L), instant.getNano())
        : Math.addExact(
            Math.multiplyExact(seconds + 1, 1_000_000_000L), instant.getNano() - 1_000_000_000L);
  }
}
