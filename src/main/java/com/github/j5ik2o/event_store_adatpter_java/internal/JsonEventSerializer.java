package com.github.j5ik2o.event_store_adatpter_java.internal;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.j5ik2o.event_store_adatpter_java.AggregateId;
import com.github.j5ik2o.event_store_adatpter_java.Event;
import com.github.j5ik2o.event_store_adatpter_java.EventSerializer;
import com.github.j5ik2o.event_store_adatpter_java.SerializationException;
import java.io.IOException;
import javax.annotation.Nonnull;

public final class JsonEventSerializer<AID extends AggregateId, E extends Event<AID>>
    implements EventSerializer<AID, E> {
  @Nonnull private final ObjectMapper objectMapper;

  public JsonEventSerializer(@Nonnull ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  @Nonnull
  @Override
  public byte[] serialize(@Nonnull E event) throws SerializationException {
    try {
      return objectMapper.writeValueAsBytes(event);
    } catch (JsonProcessingException e) {
      throw new SerializationException(e);
    }
  }

  @Nonnull
  @Override
  public E deserialize(@Nonnull byte[] bytes, @Nonnull Class<E> clazz)
      throws SerializationException {
    try {
      return objectMapper.readValue(bytes, clazz);
    } catch (IOException e) {
      throw new SerializationException(e);
    }
  }
}
