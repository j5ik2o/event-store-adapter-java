package com.github.j5ik2o.event.store.adapter.java;

import java.time.Instant;
import javax.annotation.Nonnull;

/**
 * This is an interface for representing events. / イベントを表すためのインターフェース。
 *
 * @param <AID> Aggregate ID type / 集約IDの型
 */
public interface Event<AID extends AggregateId> {
  /**
   * Returns the ID. / IDを返します。
   *
   * @return ID / ID
   */
  @Nonnull
  String getId();

  /**
   * Returns the aggregate ID. / 集約IDを返します。
   *
   * @return aggregate ID / 集約ID
   */
  @Nonnull
  AID getAggregateId();

  /**
   * Returns the sequence number. / シーケンス番号を返します。
   *
   * @return sequence number / シーケンス番号
   */
  long getSequenceNumber();

  /**
   * Returns the version. / バージョンを返します。
   *
   * @return version / バージョン
   */
  @Nonnull
  Instant getOccurredAt();

  /**
   * Determines whether it is a generated event. / 生成型のイベントであるかどうかを判定します。
   *
   * @return true if it is a generated event / 生成型のイベントである場合はtrue
   */
  boolean isCreated();
}
