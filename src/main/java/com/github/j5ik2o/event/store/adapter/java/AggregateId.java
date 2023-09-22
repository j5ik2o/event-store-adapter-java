package com.github.j5ik2o.event.store.adapter.java;

import javax.annotation.Nonnull;

/** This is an interface for representing aggregate IDs. / 集約IDを表すためのインターフェース。 */
public interface AggregateId {
  /**
   * Returns the type name. / 型名を返します。
   *
   * @return type name / 型名
   */
  @Nonnull
  String getTypeName();

  /**
   * Returns the value. / 値を返します。
   *
   * @return value / 値
   */
  @Nonnull
  String getValue();

  /**
   * Returns the string representation. / 文字列表現を返します。
   *
   * @return string representation / 文字列表現
   */
  @Nonnull
  String asString();
}
