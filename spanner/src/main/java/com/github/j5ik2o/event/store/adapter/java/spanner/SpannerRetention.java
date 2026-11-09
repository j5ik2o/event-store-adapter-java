package com.github.j5ik2o.event.store.adapter.java.spanner;

import com.github.j5ik2o.event.store.adapter.java.core.AggregateId;
import com.github.j5ik2o.event.store.adapter.java.core.RetentionFailure;
import com.github.j5ik2o.event.store.adapter.java.core.RetentionMode;
import com.google.cloud.ByteArray;
import com.google.cloud.spanner.ResultSet;
import com.google.cloud.spanner.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.TreeSet;

/**
 * After the append has committed, removes or marks old snapshot history in separate transactions.
 * Every chunk selects its targets again, so a retry never works from stale data.
 */
final class SpannerRetention {
  /** Rows handled by one transaction. */
  static final int CHUNK = 500;

  private SpannerRetention() {}

  static Optional<RetentionFailure> retain(
      SpannerStorage storage, AggregateId id, long justWrittenSeqNr) {
    RetentionMode mode = storage.config.retentionPolicy().mode().get();
    try {
      int processed;
      do {
        processed =
            SpannerTransactions.runReadWrite(
                storage,
                null,
                "failed to apply spanner retention",
                tx -> chunk(storage, id, justWrittenSeqNr, tx));
      } while (processed >= CHUNK);
      return Optional.empty();
    } catch (RuntimeException failure) {
      return Optional.of(new RetentionFailure(id, mode, failure));
    }
  }

  private static int chunk(
      SpannerStorage storage,
      AggregateId id,
      long justWrittenSeqNr,
      com.google.cloud.spanner.TransactionContext tx) {
    SpannerKey key = SpannerKey.of(id);
    ByteArray hashPrefix = ByteArray.copyFrom(key.hashPrefix());
    String table = storage.config.tableNames.snapshotHistory;
    int keep = storage.config.retentionPolicy().keepCount().getAsInt();
    List<Long> history = new ArrayList<>();
    try (ResultSet rs =
        tx.executeQuery(
            Statement.newBuilder(
                    "SELECT seq_nr FROM "
                        + table
                        + " WHERE hash_prefix = @hp AND aid = @aid AND expired_at IS NULL"
                        + " ORDER BY seq_nr DESC LIMIT "
                        + (keep + CHUNK))
                .bind("hp")
                .to(hashPrefix)
                .bind("aid")
                .to(key.aid())
                .build())) {
      while (rs.next()) {
        history.add(rs.getLong(0));
      }
    }
    // The newest rows are kept, so they stay in the window; each chunk takes the CHUNK rows after
    // them. The row just written is always a candidate, as in the other storages.
    TreeSet<Long> candidates = new TreeSet<>(Comparator.reverseOrder());
    candidates.addAll(storage.hooks.readHistory(id, history));
    candidates.add(justWrittenSeqNr);
    List<Long> obsolete = new ArrayList<>();
    int index = 0;
    for (Long seqNr : candidates) {
      if (index++ >= keep && obsolete.size() < CHUNK) {
        obsolete.add(seqNr);
      }
    }
    if (obsolete.isEmpty()) {
      return 0;
    }
    Collections.sort(obsolete);
    storage.hooks.beforeRetentionDelete(id, new ArrayList<>(obsolete));
    boolean ttl = storage.config.retentionPolicy().mode().get() == RetentionMode.TTL;
    String sql =
        ttl
            ? "UPDATE "
                + table
                + " SET expired_at = CURRENT_TIMESTAMP() WHERE hash_prefix = @hp AND aid = @aid"
                + " AND expired_at IS NULL AND seq_nr IN UNNEST(@seqs)"
            : "DELETE FROM "
                + table
                + " WHERE hash_prefix = @hp AND aid = @aid AND seq_nr IN UNNEST(@seqs)";
    tx.executeUpdate(
        Statement.newBuilder(sql)
            .bind("hp")
            .to(hashPrefix)
            .bind("aid")
            .to(key.aid())
            .bind("seqs")
            .toInt64Array(obsolete)
            .build());
    return obsolete.size();
  }
}
