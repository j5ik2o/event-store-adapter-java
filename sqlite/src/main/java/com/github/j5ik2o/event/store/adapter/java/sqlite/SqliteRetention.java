package com.github.j5ik2o.event.store.adapter.java.sqlite;

import com.github.j5ik2o.event.store.adapter.java.core.AggregateId;
import com.github.j5ik2o.event.store.adapter.java.core.RetentionFailure;
import com.github.j5ik2o.event.store.adapter.java.core.RetentionMode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.TreeSet;

/** 追記の確定後に、別の BEGIN IMMEDIATE で古いスナップショット履歴だけを削除する。 */
final class SqliteRetention {
  private SqliteRetention() {}

  static Optional<RetentionFailure> retain(
      SqliteStorage storage, AggregateId id, long justWrittenSeqNr) {
    storage.lock.lock();
    try {
      try {
        Connection c = storage.connectionForOperation();
        SqliteTransactions.begin(c, true);
        try {
          List<Long> actual = new ArrayList<>();
          try (PreparedStatement p =
              c.prepareStatement(
                  "SELECT seq_nr FROM snapshot_history WHERE aid = ? ORDER BY seq_nr DESC")) {
            p.setString(1, id.asString());
            try (ResultSet rs = p.executeQuery()) {
              while (rs.next()) {
                actual.add(rs.getLong(1));
              }
            }
          }
          TreeSet<Long> candidates = new TreeSet<>(Comparator.reverseOrder());
          candidates.addAll(storage.hooks.readHistory(id, actual));
          candidates.add(justWrittenSeqNr);
          List<Long> obsolete = new ArrayList<>();
          int index = 0;
          for (Long seqNr : candidates) {
            if (index++ >= storage.policy.keepCount().getAsInt()) {
              obsolete.add(seqNr);
            }
          }
          obsolete.sort(Comparator.naturalOrder());
          if (!obsolete.isEmpty()) {
            storage.hooks.beforeRetentionDelete(id, new ArrayList<>(obsolete));
            try (PreparedStatement p =
                c.prepareStatement("DELETE FROM snapshot_history WHERE aid = ? AND seq_nr = ?")) {
              for (Long seqNr : obsolete) {
                p.setString(1, id.asString());
                p.setLong(2, seqNr);
                p.executeUpdate();
              }
            }
          }
          SqliteTransactions.commit(c);
          return Optional.empty();
        } catch (SQLException | RuntimeException failure) {
          SqliteTransactions.rollbackQuietly(c, failure);
          throw failure;
        }
      } catch (SQLException | RuntimeException failure) {
        return Optional.of(new RetentionFailure(id, RetentionMode.DELETE, failure));
      }
    } finally {
      storage.lock.unlock();
    }
  }
}
