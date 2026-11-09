package com.github.j5ik2o.event.store.adapter.java.core;

import java.util.Objects;
import java.util.OptionalLong;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** An optimistic lock conflict. / 楽観ロックの競合。 */
public final class OptimisticLockException extends EventStoreException {

  /**
   * Creates an exception. The message holds only the aid, the seq_nr and the head. /
   * 例外を作ります。メッセージには aid、seq_nr、ヘッドだけを入れます。
   *
   * @param aggregateId aggregate ID / 集約 ID
   * @param seqNr seq_nr that was being appended / 追記しようとした seq_nr
   * @param headSeqNr head seq_nr, if known / ヘッドの seq_nr（分かれば）
   * @param cause cause kept out of the message / 原因（メッセージには入れない）
   */
  public OptimisticLockException(
      @Nonnull AggregateId aggregateId,
      long seqNr,
      @Nonnull OptionalLong headSeqNr,
      @Nullable Throwable cause) {
    super(buildMessage(aggregateId, seqNr, headSeqNr), cause);
  }

  private static String buildMessage(AggregateId aggregateId, long seqNr, OptionalLong headSeqNr) {
    Objects.requireNonNull(aggregateId, "aggregateId");
    Objects.requireNonNull(headSeqNr, "headSeqNr");
    String base = "optimistic lock conflict: aid=" + aggregateId.asString() + ", seq_nr=" + seqNr;
    return headSeqNr.isPresent() ? base + ", head_seq_nr=" + headSeqNr.getAsLong() : base;
  }

  @Override
  @Nonnull
  public ErrorCategory category() {
    return ErrorCategory.OPTIMISTIC_LOCK;
  }
}
