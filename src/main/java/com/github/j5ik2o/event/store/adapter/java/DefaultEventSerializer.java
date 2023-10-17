package com.github.j5ik2o.event.store.adapter.java;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import javax.annotation.Nonnull;

public final class DefaultEventSerializer<AID extends AggregateId, E extends Event<AID>>
    implements EventSerializer<AID, E> {
  private static final ObjectMapper objectMapper = new ObjectMapper();

  static {
    objectMapper.findAndRegisterModules();
  }

  public DefaultEventSerializer() {}

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
      throws DeserializationException {
    try {
      return objectMapper.readValue(bytes, clazz);
    } catch (IOException e) {
      throw new DeserializationException(e);
    }
  }
}
