package com.github.j5ik2o.event_store_adatpter_java;

public interface SnapshotSerializer<AID extends AggregateId, A extends Aggregate<AID>> {
  byte[] serialize(A snapshot) throws SerializationException;

  A deserialize(byte[] bytes, Class<A> clazz) throws SerializationException;
}
