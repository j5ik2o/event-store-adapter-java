package com.github.j5ik2o.event_store_adatpter_java;

public interface AggregateId {
  String getTypeName();

  String getValue();

  String asString();
}
