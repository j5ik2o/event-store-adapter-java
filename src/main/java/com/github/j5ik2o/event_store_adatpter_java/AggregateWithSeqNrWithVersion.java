package com.github.j5ik2o.event_store_adatpter_java;

public record AggregateWithSeqNrWithVersion<A extends Aggregate>(
    A aggregate, long seqNr, long version) {}
