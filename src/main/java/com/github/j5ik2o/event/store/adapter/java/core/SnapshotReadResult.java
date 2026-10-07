package com.github.j5ik2o.event.store.adapter.java.core;

import java.util.Objects;
import java.util.Optional;
import javax.annotation.Nonnull;

/** Result of reading the latest snapshot. / 最新スナップショットの読み取り結果。 */
public final class SnapshotReadResult<A> {

  private final SnapshotEnvelope<A> snapshot;
  private final long headSeqNr;

  private SnapshotReadResult(SnapshotEnvelope<A> snapshot, long headSeqNr) {
    this.snapshot = snapshot;
    this.headSeqNr = headSeqNr;
  }

  /**
   * Creates a result with a snapshot. / スナップショットありの結果を作ります。
   *
   * @param snapshot snapshot / スナップショット
   * @param headSeqNr head seq_nr / ヘッドの seq_nr
   * @param <A> aggregate type / 集約の型
   * @return result / 結果
   */
  @Nonnull
  public static <A> SnapshotReadResult<A> of(
      @Nonnull SnapshotEnvelope<A> snapshot, long headSeqNr) {
    return new SnapshotReadResult<>(Objects.requireNonNull(snapshot, "snapshot"), headSeqNr);
  }

  /**
   * Creates a result without a snapshot. / スナップショットなしの結果を作ります。
   *
   * @param headSeqNr head seq_nr / ヘッドの seq_nr
   * @param <A> aggregate type / 集約の型
   * @return result / 結果
   */
  @Nonnull
  public static <A> SnapshotReadResult<A> withoutSnapshot(long headSeqNr) {
    return new SnapshotReadResult<>(null, headSeqNr);
  }

  @Nonnull
  public Optional<SnapshotEnvelope<A>> snapshot() {
    return Optional.ofNullable(snapshot);
  }

  public long headSeqNr() {
    return headSeqNr;
  }
}
