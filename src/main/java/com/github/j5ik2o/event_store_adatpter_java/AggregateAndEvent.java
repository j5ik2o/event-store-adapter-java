package com.github.j5ik2o.event_store_adatpter_java;

public record AggregateAndEvent<
    AID extends AggregateId, A extends Aggregate<AID>, E extends Event<AID>>(
    A aggregate, E event) {}
