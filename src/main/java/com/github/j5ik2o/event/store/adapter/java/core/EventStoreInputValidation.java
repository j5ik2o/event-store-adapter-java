package com.github.j5ik2o.event.store.adapter.java.core;

import java.util.Objects;

/** 保存操作が共有する入力検査。検査式は中核の内部実装へ委譲する。 */
public final class EventStoreInputValidation {
  private EventStoreInputValidation() {}

  public static void checkEvent(EventEnvelope<?> event) {
    Objects.requireNonNull(event, "event");
    checkRead(event.aggregateId(), event.seqNr());
    Validation.checkSeqNr(event.seqNr(), Validation.Context.EVENT);
    Validation.checkOccurredAt(event.occurredAt(), event.seqNr());
  }

  public static void checkEventAndSnapshot(EventEnvelope<?> event, SnapshotEnvelope<?> snapshot) {
    checkEvent(event);
    Objects.requireNonNull(snapshot, "snapshot");
    Validation.checkSeqNr(snapshot.seqNr(), Validation.Context.VALUE);
    Validation.checkSnapshotSeqNr(event.seqNr(), snapshot.seqNr());
  }

  public static void checkRead(AggregateId aggregateId, long seqNr) {
    Objects.requireNonNull(aggregateId, "aggregateId");
    Validation.checkAidString(aggregateId.typeName(), aggregateId.value());
    Validation.checkSeqNr(seqNr, Validation.Context.VALUE);
  }
}
