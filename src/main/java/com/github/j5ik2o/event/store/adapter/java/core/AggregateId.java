package com.github.j5ik2o.event.store.adapter.java.core;

import java.util.Objects;
import javax.annotation.Nonnull;

/** Aggregate ID made of a type name and a value. / 型名と値でできた集約 ID。 */
public final class AggregateId {

  private final String typeName;
  private final String value;
  private final String asString;

  private AggregateId(String typeName, String value) {
    this.typeName = typeName;
    this.value = value;
    this.asString = typeName + "-" + value;
  }

  /**
   * Creates an aggregate ID. / 集約 ID を作ります。
   *
   * @param typeName type name without hyphen / ハイフンを含まない型名
   * @param value value / 値
   * @return aggregate ID / 集約 ID
   */
  @Nonnull
  public static AggregateId of(@Nonnull String typeName, @Nonnull String value) {
    Objects.requireNonNull(typeName, "typeName");
    Objects.requireNonNull(value, "value");
    Validation.checkAidString(typeName, value);
    return new AggregateId(typeName, value);
  }

  /**
   * Returns the type name. / 型名を返します。
   *
   * @return type name / 型名
   */
  @Nonnull
  public String typeName() {
    return typeName;
  }

  /**
   * Returns the value. / 値を返します。
   *
   * @return value / 値
   */
  @Nonnull
  public String value() {
    return value;
  }

  /**
   * Returns the aid string built by the library. / ライブラリが組み立てた aid 文字列を返します。
   *
   * @return aid string / aid 文字列
   */
  @Nonnull
  public String asString() {
    return asString;
  }

  @Override
  public boolean equals(Object o) {
    return o instanceof AggregateId && asString.equals(((AggregateId) o).asString);
  }

  @Override
  public int hashCode() {
    return asString.hashCode();
  }

  @Override
  public String toString() {
    return asString;
  }
}
