package com.github.j5ik2o.event.store.adapter.java.core;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Serializes payloads only. / payload だけを直列化する。 */
public interface PayloadSerializer<T> {

  /**
   * Serializes a value. / 値を直列化します。
   *
   * @param value value / 値
   * @return bytes / バイト列
   * @throws SerializationException on failure / 失敗したとき
   */
  @Nonnull
  byte[] serialize(@Nonnull T value) throws SerializationException;

  /**
   * Deserializes bytes. / バイト列を復元します。
   *
   * @param bytes bytes / バイト列
   * @return value / 値
   * @throws SerializationException on failure / 失敗したとき
   */
  @Nullable
  T deserialize(@Nonnull byte[] bytes) throws SerializationException;
}
