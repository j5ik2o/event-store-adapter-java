package com.github.j5ik2o.event.store.adapter.java.core;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class EventStoreInputValidationTest {
  private static final AggregateId AID = AggregateId.of("Order", "1");

  private static EventEnvelope<String> event() {
    return EventEnvelope.<String>builder()
        .aggregateId(AID)
        .seqNr(1)
        .occurredAt(Instant.ofEpochSecond(0, Long.MIN_VALUE))
        .payload("event")
        .build();
  }

  @Test
  void validEnvelopesAndInclusiveReadBoundariesPassSharedValidation() {
    EventEnvelope<String> event = event();
    SnapshotEnvelope<String> snapshot =
        SnapshotEnvelope.<String>builder().seqNr(1).aggregate("snapshot").build();

    assertDoesNotThrow(() -> EventStoreInputValidation.checkEvent(event));
    assertDoesNotThrow(() -> EventStoreInputValidation.checkEventAndSnapshot(event, snapshot));
    assertDoesNotThrow(() -> EventStoreInputValidation.checkRead(AID, 0));
    assertDoesNotThrow(() -> EventStoreInputValidation.checkRead(AID, (1L << 53) - 1));
  }

  @Test
  void sharedValidationRejectsMismatchingSnapshotAsW9() {
    SnapshotEnvelope<String> snapshot =
        SnapshotEnvelope.<String>builder().seqNr(0).aggregate("snapshot").build();

    ContractViolationException failure =
        assertThrows(
            ContractViolationException.class,
            () -> EventStoreInputValidation.checkEventAndSnapshot(event(), snapshot));

    assertEquals("W-9", failure.rule());
  }

  @Test
  void sharedReadValidationRejectsOutOfRangeNumberAsT9() {
    ContractViolationException failure =
        assertThrows(
            ContractViolationException.class, () -> EventStoreInputValidation.checkRead(AID, -1));

    assertEquals("T-9", failure.rule());
    assertEquals(-1, failure.seqNr().getAsLong());
  }
}
