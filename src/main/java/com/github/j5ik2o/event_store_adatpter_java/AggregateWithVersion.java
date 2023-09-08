package com.github.j5ik2o.event_store_adatpter_java;

public record AggregateWithVersion<AID extends AggregateId, A extends Aggregate<AID>>(
    A aggregate, long version) {}
