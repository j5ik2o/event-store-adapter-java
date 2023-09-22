package com.github.j5ik2o.event.store.adapter.java.internal;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.j5ik2o.event.store.adapter.java.*;
import java.io.IOException;
import javax.annotation.Nonnull;

public final class JsonSnapshotSerializer<AID extends AggregateId, A extends Aggregate<A, AID>>
    implements SnapshotSerializer<AID, A> {
  private static final ObjectMapper objectMapper = new ObjectMapper();

  static {
    objectMapper.findAndRegisterModules();
  }

  public JsonSnapshotSerializer() {}

  @Nonnull
  @Override
  public byte[] serialize(@Nonnull A snapshot) throws SerializationException {
    try {
      return objectMapper.writeValueAsBytes(snapshot);
    } catch (JsonProcessingException e) {
      throw new SerializationException(e);
    }
  }

  @Nonnull
  @Override
  public A deserialize(@Nonnull byte[] bytes, @Nonnull Class<A> clazz)
      throws DeserializationException {
    try {
      return objectMapper.readValue(bytes, clazz);
    } catch (IOException e) {
      throw new DeserializationException(e);
    }
  }
}
