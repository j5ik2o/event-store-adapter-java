package com.github.j5ik2o.event.store.adapter.java.apisurface.fixtures.eventsreturnpayloads;

import com.github.j5ik2o.event.store.adapter.java.core.AggregateId;
import com.github.j5ik2o.event.store.adapter.java.core.EventEnvelope;
import com.github.j5ik2o.event.store.adapter.java.core.SnapshotEnvelope;
import com.github.j5ik2o.event.store.adapter.java.core.SnapshotReadResult;
import java.util.List;
import java.util.Optional;

/** 否定の試験用に、getEventsByIdSinceSeqNr が封筒ではなくペイロードの列を返すストア。 */
public interface EventStore<P, A> {

  void persistEvent(EventEnvelope<P> event);

  void persistEventAndSnapshot(EventEnvelope<P> event, SnapshotEnvelope<A> snapshot);

  Optional<SnapshotReadResult<A>> getLatestSnapshotById(AggregateId aggregateId);

  List<P> getEventsByIdSinceSeqNr(AggregateId aggregateId, long seqNr);
}
