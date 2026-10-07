package com.github.j5ik2o.event.store.adapter.java.core;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import javax.annotation.Nonnull;

/** Asynchronous event store operations. / 非同期のイベントストアの操作。 */
public interface AsyncEventStore<P, A> {

  /**
   * Appends one event. / 1 件のイベントを追記します。
   *
   * @param event event / イベント
   * @return future / 完了を表す Future
   */
  @Nonnull
  CompletableFuture<Void> persistEvent(@Nonnull EventEnvelope<P> event);

  /**
   * Appends one event and a snapshot atomically. / イベントとスナップショットを同じ確定で書きます。
   *
   * @param event event / イベント
   * @param snapshot snapshot / スナップショット
   * @return future / 完了を表す Future
   */
  @Nonnull
  CompletableFuture<Void> persistEventAndSnapshot(
      @Nonnull EventEnvelope<P> event, @Nonnull SnapshotEnvelope<A> snapshot);

  /**
   * Reads the latest snapshot. / 最新スナップショットを読みます。
   *
   * @param aggregateId aggregate ID / 集約 ID
   * @return future of the result, empty when there is no head / 結果の Future。ヘッドがなければ空
   */
  @Nonnull
  CompletableFuture<Optional<SnapshotReadResult<A>>> getLatestSnapshotById(
      @Nonnull AggregateId aggregateId);

  /**
   * Reads events since a seq_nr in ascending order. / seq_nr 以上のイベントを昇順に読みます。
   *
   * @param aggregateId aggregate ID / 集約 ID
   * @param seqNr first seq_nr / 最初の seq_nr
   * @return future of events / イベントの Future
   */
  @Nonnull
  CompletableFuture<List<EventEnvelope<P>>> getEventsByIdSinceSeqNr(
      @Nonnull AggregateId aggregateId, long seqNr);
}
