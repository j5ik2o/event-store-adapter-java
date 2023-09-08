package com.github.j5ik2o.event_store_adatpter_java.internal;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.j5ik2o.event_store_adatpter_java.AggregateId;
import com.github.j5ik2o.event_store_adatpter_java.Event;
import com.github.j5ik2o.event_store_adatpter_java.EventSerializer;
import com.github.j5ik2o.event_store_adatpter_java.SerializationException;
import java.io.IOException;

public class JsonEventSerializer<AID extends AggregateId, E extends Event<AID>>
    implements EventSerializer<AID, E> {
  private final ObjectMapper objectMapper;

  public JsonEventSerializer(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  @Override
  public byte[] serialize(E event) throws SerializationException {
    try {
      return objectMapper.writeValueAsBytes(event);
    } catch (JsonProcessingException e) {
      throw new SerializationException(e);
    }
  }

  @Override
  public E deserialize(byte[] bytes, Class<E> clazz) throws SerializationException {
    try {
      return objectMapper.readValue(bytes, clazz);
    } catch (IOException e) {
      throw new SerializationException(e);
    }
  }
}
