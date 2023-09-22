package com.github.j5ik2o.event.store.adapter.java;

import javax.annotation.Nonnull;

/**
 * This is an interface for serializing and deserializing events. /
 * イベントのシリアライズとデシリアライズを行うためのインターフェース。
 *
 * @param <AID> Aggregate ID type / 集約IDの型
 * @param <E> Event type / イベントの型
 */
public interface EventSerializer<AID extends AggregateId, E extends Event<AID>> {
  /**
   * Serializes the event. / イベントをシリアライズします。
   *
   * @param event event / イベント
   * @return serialized event / シリアライズされたイベント
   * @throws SerializationException if an error occurred during serialization / シリアライズ中にエラーが発生した場合
   */
  @Nonnull
  byte[] serialize(@Nonnull E event) throws SerializationException;

  /**
   * Deserializes the event. / イベントをデシリアライズします。
   *
   * @param bytes bytes / バイト列
   * @param clazz class of {@link Event} E to be deserialized / デシリアライズ対象のイベントEのクラス
   * @return deserialized event / デシリアライズされたイベント
   * @throws DeserializationException if an error occurred during deserialization /
   *     デシリアライズ中にエラーが発生した場合
   */
  @Nonnull
  E deserialize(@Nonnull byte[] bytes, @Nonnull Class<E> clazz) throws DeserializationException;
}
