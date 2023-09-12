package com.github.j5ik2o.event_store_adatpter_java.internal;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.j5ik2o.event_store_adatpter_java.Aggregate;
import com.github.j5ik2o.event_store_adatpter_java.AggregateId;
import com.github.j5ik2o.event_store_adatpter_java.SerializationException;
import com.github.j5ik2o.event_store_adatpter_java.SnapshotSerializer;
import java.io.IOException;
import javax.annotation.Nonnull;

public final class JsonSnapshotSerializer<AID extends AggregateId, A extends Aggregate<AID>>
    implements SnapshotSerializer<AID, A> {
  @Nonnull private final ObjectMapper objectMapper;

  public JsonSnapshotSerializer(@Nonnull ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

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
      throws SerializationException {
    try {
      return objectMapper.readValue(bytes, clazz);
    } catch (IOException e) {
      throw new SerializationException(e);
    }
  }
}
