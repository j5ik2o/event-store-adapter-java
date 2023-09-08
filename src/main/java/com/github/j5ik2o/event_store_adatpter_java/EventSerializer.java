package com.github.j5ik2o.event_store_adatpter_java;

public interface EventSerializer<E extends Event> {
  byte[] serialize(E event) throws SerializationException;

  E deserialize(byte[] bytes, Class<E> clazz) throws SerializationException;
}
