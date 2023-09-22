package com.github.j5ik2o.event.store.adapter.java;

import javax.annotation.Nonnull;

/**
 * This is an interface for serializing and deserializing snapshots. /
 * スナップショットのシリアライズとデシリアライズを行うためのインターフェース。
 *
 * @param <AID> Aggregate ID type / 集約IDの型
 * @param <A> Aggregate type / 集約の型
 */
public interface SnapshotSerializer<AID extends AggregateId, A extends Aggregate<A, AID>> {
  /**
   * Serializes the snapshot. / スナップショットをシリアライズします。
   *
   * @param snapshot snapshot / スナップショット
   * @return serialized snapshot / シリアライズされたスナップショット
   * @throws SerializationException if an error occurred during serialization / シリアライズ中にエラーが発生した場合
   */
  @Nonnull
  byte[] serialize(@Nonnull A snapshot) throws SerializationException;

  /**
   * Deserializes the snapshot. / スナップショットをデシリアライズします。
   *
   * @param bytes bytes / バイト列
   * @param clazz class of {@link Aggregate} A to be deserialized / デシリアライズ対象の集約Aのクラス
   * @return deserialized snapshot / デシリアライズされたスナップショット
   * @throws DeserializationException if an error occurred during deserialization /
   *     デシリアライズ中にエラーが発生した場合
   */
  @Nonnull
  A deserialize(@Nonnull byte[] bytes, @Nonnull Class<A> clazz) throws DeserializationException;
}
