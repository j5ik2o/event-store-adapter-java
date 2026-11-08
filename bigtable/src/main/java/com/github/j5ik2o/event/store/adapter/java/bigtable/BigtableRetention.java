package com.github.j5ik2o.event.store.adapter.java.bigtable;

import com.github.j5ik2o.event.store.adapter.java.core.AggregateId;
import com.github.j5ik2o.event.store.adapter.java.core.RetentionFailure;
import com.github.j5ik2o.event.store.adapter.java.core.RetentionMode;
import com.google.cloud.bigtable.data.v2.models.Filters;
import com.google.cloud.bigtable.data.v2.models.Mutation;
import com.google.cloud.bigtable.data.v2.models.Row;
import com.google.protobuf.ByteString;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.TreeSet;

/**
 * Snapshot history retention (BT-11, BT-12, BT-13). Old history rows are retired in one conditional
 * update each; events and the retired marker stay. A failure is returned as a value and never
 * changes the committed append.
 */
final class BigtableRetention {
  private BigtableRetention() {}

  static Optional<RetentionFailure> retain(BigtableStorage storage, AggregateId id, long h) {
    RetentionMode mode = storage.config.retentionPolicy().mode().get();
    try {
      int keep = storage.config.retentionPolicy().keepCount().getAsInt();
      ByteString prefix = storage.prefix(id.asString());
      List<Long> visible = storage.hooks.readHistory(id, scanActive(storage, prefix, h));
      TreeSet<Long> history = new TreeSet<>(Collections.reverseOrder());
      history.addAll(visible);
      history.add(h);
      List<Long> expired = new ArrayList<>();
      int index = 0;
      for (Long seqNr : history) {
        if (index++ >= keep) {
          expired.add(seqNr);
        }
      }
      if (expired.isEmpty()) {
        return Optional.empty();
      }
      storage.hooks.beforeRetentionDelete(id, expired);
      for (long seqNr : expired) {
        retire(storage, prefix, seqNr);
      }
      return Optional.empty();
    } catch (RuntimeException failure) {
      return Optional.of(new RetentionFailure(id, mode, failure));
    }
  }

  /** The seq_nr of the active history rows in [1, h], read from the state cell only. */
  private static List<Long> scanActive(BigtableStorage storage, ByteString prefix, long h) {
    List<Row> rows =
        BigtableRows.readRange(
            storage.data,
            storage.config.journalTableId(),
            BigtableKey.journalRowKey(prefix, 1),
            BigtableKey.journalRowKey(prefix, h + 1),
            Filters.FILTERS
                .chain()
                .filter(BigtableRows.column(BigtableRows.HISTORY, BigtableRows.STATE))
                .filter(BigtableRows.latestCell()));
    List<Long> active = new ArrayList<>();
    for (Row row : rows) {
      OptionalLong seq = BigtableKey.journalSeqNr(prefix, row.getKey());
      if (!seq.isPresent()) {
        throw BigtableRead.invalid("a journal row key does not hold a seq_nr");
      }
      ByteString state = BigtableRows.value(row, BigtableRows.HISTORY, BigtableRows.STATE);
      if (BigtableRows.ACTIVE.equals(state)) {
        active.add(seq.getAsLong());
      } else if (!BigtableRows.RETIRED.equals(state)) {
        throw BigtableRead.invalid("a journal row holds an unknown history state");
      }
    }
    return active;
  }

  /**
   * Deletes the history envelope and marks the row retired, in one conditional update that applies
   * only while the state is active. The event cells are kept.
   */
  private static void retire(BigtableStorage storage, ByteString prefix, long seqNr) {
    Mutation retire = Mutation.create().deleteCells(BigtableRows.HISTORY, BigtableRows.ENVELOPE);
    BigtableRows.replaceCell(
        retire, BigtableRows.HISTORY, BigtableRows.STATE, BigtableRows.RETIRED);
    BigtableRows.checkAndMutate(
        storage.data,
        storage.config.journalTableId(),
        BigtableKey.journalRowKey(prefix, seqNr),
        BigtableRows.latestCellEquals(
            BigtableRows.HISTORY, BigtableRows.STATE, BigtableRows.ACTIVE),
        retire,
        null);
  }
}
