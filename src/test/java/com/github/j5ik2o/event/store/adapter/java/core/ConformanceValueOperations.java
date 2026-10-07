package com.github.j5ik2o.event.store.adapter.java.core;

import java.time.Instant;

/** 適合データの値の表の操作を中核へつなぐ。package-private の検査を呼ぶため、core と同じパッケージに置く。 */
public final class ConformanceValueOperations {

  private ConformanceValueOperations() {}

  /** validateSeqNr。value は検査をそのまま呼び、event は封筒の構築を通す。 */
  public static long validateSeqNr(long seqNr, String context) {
    switch (context) {
      case "value":
        Validation.checkSeqNr(seqNr, Validation.Context.VALUE);
        return seqNr;
      case "event":
        return EventEnvelope.<String>builder()
            .aggregateId(AggregateId.of("ConformanceSeqNr", "v"))
            .seqNr(seqNr)
            .occurredAt(Instant.EPOCH)
            .payload("p")
            .build()
            .seqNr();
      default:
        throw new IllegalArgumentException("unknown context: " + context);
    }
  }
}
