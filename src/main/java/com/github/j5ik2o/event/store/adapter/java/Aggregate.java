package com.github.j5ik2o.event.store.adapter.java;

import javax.annotation.Nonnull;

/**
 * This is an interface for representing aggregates. / 集約を表すためのインターフェース。
 *
 * @param <This> Aggregate type / 集約の型
 * @param <AID> Aggregate ID type / 集約IDの型
 */
public interface Aggregate<This extends Aggregate<This, AID>, AID extends AggregateId> {
  /**
   * Returns the aggregate ID. / 集約IDを返します。
   *
   * @return aggregate ID / 集約ID
   */
  @Nonnull
  AID getId();

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
  long getVersion();

  /**
   * Sets the version. / バージョンを設定します。
   *
   * @param version version / バージョン
   * @return this
   */
  This withVersion(long version);
}
