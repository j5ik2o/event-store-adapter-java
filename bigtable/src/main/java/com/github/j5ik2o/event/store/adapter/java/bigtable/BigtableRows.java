package com.github.j5ik2o.event.store.adapter.java.bigtable;

import com.github.j5ik2o.event.store.adapter.java.core.StorageException;
import com.google.api.gax.rpc.ApiException;
import com.google.cloud.bigtable.data.v2.BigtableDataClient;
import com.google.cloud.bigtable.data.v2.models.ConditionalRowMutation;
import com.google.cloud.bigtable.data.v2.models.Filters;
import com.google.cloud.bigtable.data.v2.models.Filters.Filter;
import com.google.cloud.bigtable.data.v2.models.Mutation;
import com.google.cloud.bigtable.data.v2.models.Query;
import com.google.cloud.bigtable.data.v2.models.Row;
import com.google.cloud.bigtable.data.v2.models.RowCell;
import com.google.protobuf.ByteString;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import javax.annotation.Nullable;

/**
 * Column names, filters and mutations, and the three row operations the storage uses. Every cell is
 * written at timestamp 0 (BT-6); a column that is replaced is first deleted in the same atomic
 * mutation. Filters read the latest cell of a column only. SDK failures become {@link
 * StorageException}. / 列の名前、フィルタ、変更。セルの時刻は 0 に固定し、置き換える列は同じ原子的な変更の中で先に全版を削除します。
 */
final class BigtableRows {
  static final String EVENT = "event";
  static final String HISTORY = "history";
  static final String HEAD = "head";
  static final String SNAPSHOT = "snapshot";
  static final String CONFIG = "config";

  static final ByteString ENVELOPE = ByteString.copyFromUtf8("envelope");
  static final ByteString HAS_SNAPSHOT = ByteString.copyFromUtf8("has_snapshot");
  static final ByteString STATE = ByteString.copyFromUtf8("state");
  static final ByteString SEQ_NR = ByteString.copyFromUtf8("seq_nr");
  static final ByteString LAST_EVENT = ByteString.copyFromUtf8("last_event");
  static final ByteString CURRENT = ByteString.copyFromUtf8("current");
  static final ByteString METADATA = ByteString.copyFromUtf8("metadata");

  static final ByteString ACTIVE = ByteString.copyFromUtf8("active");
  static final ByteString RETIRED = ByteString.copyFromUtf8("retired");
  static final ByteString FLAG_TRUE = ByteString.copyFromUtf8("1");
  static final ByteString FLAG_FALSE = ByteString.copyFromUtf8("0");

  private BigtableRows() {}

  static ByteString flag(boolean value) {
    return value ? FLAG_TRUE : FLAG_FALSE;
  }

  // ----- filters -----

  static Filter column(String family, ByteString qualifier) {
    return Filters.FILTERS
        .chain()
        .filter(Filters.FILTERS.family().exactMatch(family))
        .filter(Filters.FILTERS.qualifier().exactMatch(qualifier));
  }

  /** Every column, the latest cell of each. */
  static Filter latestCell() {
    return Filters.FILTERS.limit().cellsPerColumn(1);
  }

  /** One column, its latest cell only. */
  static Filter latestCellOf(String family, ByteString qualifier) {
    return Filters.FILTERS
        .chain()
        .filter(Filters.FILTERS.family().exactMatch(family))
        .filter(Filters.FILTERS.qualifier().exactMatch(qualifier))
        .filter(Filters.FILTERS.limit().cellsPerColumn(1));
  }

  /** Matches only when the latest cell of the column holds exactly this value. */
  static Filter latestCellEquals(String family, ByteString qualifier, ByteString value) {
    return Filters.FILTERS
        .chain()
        .filter(Filters.FILTERS.family().exactMatch(family))
        .filter(Filters.FILTERS.qualifier().exactMatch(qualifier))
        .filter(Filters.FILTERS.limit().cellsPerColumn(1))
        .filter(Filters.FILTERS.value().exactMatch(value));
  }

  static Filter latestCellExists(String family, ByteString qualifier) {
    return latestCellOf(family, qualifier);
  }

  // ----- mutations -----

  static Mutation setCell(Mutation mutation, String family, ByteString qualifier, ByteString v) {
    return mutation.setCell(family, qualifier, 0L, v);
  }

  /** Deletes every version of the column and writes the new value, in one atomic mutation. */
  static Mutation replaceCell(
      Mutation mutation, String family, ByteString qualifier, ByteString value) {
    return mutation.deleteCells(family, qualifier).setCell(family, qualifier, 0L, value);
  }

  // ----- reads -----

  /** The row, or null when it does not exist. */
  @Nullable
  static Row readRow(BigtableDataClient data, String table, ByteString key, Filter filter) {
    return call("read a row", () -> data.readRow(table, key, filter));
  }

  /** The rows of [start, end), start closed and end open, in key order. */
  static List<Row> readRange(
      BigtableDataClient data, String table, ByteString start, ByteString end, Filter filter) {
    return call(
        "read a row range",
        () -> {
          List<Row> rows = new ArrayList<>();
          for (Row row : data.readRows(Query.create(table).range(start, end).filter(filter))) {
            rows.add(row);
          }
          return rows;
        });
  }

  /** The value of the first cell of the column, or null when the row has none. */
  @Nullable
  static ByteString value(Row row, String family, ByteString qualifier) {
    List<RowCell> cells = row.getCells(family, qualifier);
    return cells.isEmpty() ? null : cells.get(0).getValue();
  }

  /**
   * One CheckAndMutateRow, sent once and never resent. Returns whether the predicate matched. A
   * response that never arrives is a {@link StorageException} of unknown outcome.
   */
  static boolean checkAndMutate(
      BigtableDataClient data,
      String table,
      ByteString key,
      Filter condition,
      @Nullable Mutation whenMatched,
      @Nullable Mutation whenNotMatched) {
    ConditionalRowMutation request = ConditionalRowMutation.create(table, key).condition(condition);
    if (whenMatched != null) {
      request = request.then(whenMatched);
    }
    if (whenNotMatched != null) {
      request = request.otherwise(whenNotMatched);
    }
    ConditionalRowMutation sent = request;
    return call("update a row conditionally", () -> data.checkAndMutateRow(sent));
  }

  static <T> T call(String what, Supplier<T> action) {
    try {
      return action.get();
    } catch (ApiException failure) {
      throw new StorageException("failed to " + what, failure);
    }
  }
}
