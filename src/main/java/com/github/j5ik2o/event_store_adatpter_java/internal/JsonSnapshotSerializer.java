package com.github.j5ik2o.event_store_adatpter_java.internal;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.j5ik2o.event_store_adatpter_java.Aggregate;
import com.github.j5ik2o.event_store_adatpter_java.AggregateId;
import com.github.j5ik2o.event_store_adatpter_java.SerializationException;
import com.github.j5ik2o.event_store_adatpter_java.SnapshotSerializer;
import java.io.IOException;

public class JsonSnapshotSerializer<AID extends AggregateId, A extends Aggregate<AID>>
    implements SnapshotSerializer<AID, A> {
  private final ObjectMapper objectMapper;

  public JsonSnapshotSerializer(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  @Override
  public byte[] serialize(A snapshot) throws SerializationException {
    try {
      return objectMapper.writeValueAsBytes(snapshot);
    } catch (JsonProcessingException e) {
      throw new SerializationException(e);
    }
  }

  @Override
  public A deserialize(byte[] bytes, Class<A> clazz) throws SerializationException {
    try {
      return objectMapper.readValue(bytes, clazz);
    } catch (IOException e) {
      throw new SerializationException(e);
    }
  }
}
