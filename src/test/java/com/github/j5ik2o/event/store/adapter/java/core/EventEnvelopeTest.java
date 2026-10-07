package com.github.j5ik2o.event.store.adapter.java.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class EventEnvelopeTest {

  private static final AggregateId AID = AggregateId.of("order", "1");

  private static EventEnvelope.Builder<String> valid() {
    return EventEnvelope.<String>builder()
        .aggregateId(AID)
        .seqNr(1)
        .occurredAt(Instant.EPOCH)
        .payload("p");
  }

  private static ContractViolationException violation(EventEnvelope.Builder<String> b) {
    return assertThrows(ContractViolationException.class, b::build);
  }

  @Test
  void validEnvelopeKeepsEveryElementAndDefaultsManifestToEmpty() {
    EventEnvelope<String> e = valid().build();

    assertEquals(AID, e.aggregateId());
    assertEquals(1, e.seqNr());
    assertEquals(Instant.EPOCH, e.occurredAt());
    assertEquals("p", e.payload());
    assertEquals("", e.manifest());
  }

  @Test
  void explicitManifestIsKept() {
    assertEquals("m", valid().manifest("m").build().manifest());
  }

  @Test
  void missingAggregateIdIsT2() {
    ContractViolationException e =
        violation(EventEnvelope.<String>builder().seqNr(1).occurredAt(Instant.EPOCH).payload("p"));

    assertEquals("T-2", e.rule());
    assertEquals(1, e.seqNr().getAsLong());
  }

  @Test
  void missingSeqNrIsT2WithoutSeqNr() {
    ContractViolationException e =
        violation(
            EventEnvelope.<String>builder()
                .aggregateId(AID)
                .occurredAt(Instant.EPOCH)
                .payload("p"));

    assertEquals("T-2", e.rule());
    assertFalse(e.seqNr().isPresent());
  }

  @Test
  void missingOccurredAtIsT2() {
    ContractViolationException e =
        violation(EventEnvelope.<String>builder().aggregateId(AID).seqNr(1).payload("p"));

    assertEquals("T-2", e.rule());
    assertEquals(1, e.seqNr().getAsLong());
  }

  @Test
  void missingPayloadIsT2() {
    ContractViolationException e =
        violation(
            EventEnvelope.<String>builder().aggregateId(AID).seqNr(1).occurredAt(Instant.EPOCH));

    assertEquals("T-2", e.rule());
    assertEquals(1, e.seqNr().getAsLong());
  }

  @Test
  void seqNrZeroIsW6WithTheValueInTheMessage() {
    ContractViolationException e = violation(valid().seqNr(0));

    assertEquals("W-6", e.rule());
    assertTrue(e.getMessage().contains("W-6"), e.getMessage());
    assertTrue(e.getMessage().contains("0"), e.getMessage());
  }

  @Test
  void seqNrOutOfGeneralRangeIsT9() {
    assertEquals("T-9", violation(valid().seqNr(-1)).rule());
    assertEquals("T-9", violation(valid().seqNr((1L << 53))).rule());
  }

  @Test
  void seqNrMaxIsAccepted() {
    assertEquals((1L << 53) - 1, valid().seqNr((1L << 53) - 1).build().seqNr());
  }

  @Test
  void occurredAtAtBothBoundariesIsAccepted() {
    Instant max = Instant.ofEpochSecond(0, Long.MAX_VALUE);
    Instant min = Instant.ofEpochSecond(0, Long.MIN_VALUE);

    assertEquals(max, valid().occurredAt(max).build().occurredAt());
    assertEquals(min, valid().occurredAt(min).build().occurredAt());
  }

  @Test
  void occurredAtOneNanoPastMaxIsT13WithSeqNrInTheMessage() {
    Instant past = Instant.ofEpochSecond(0, Long.MAX_VALUE).plusNanos(1);

    ContractViolationException e = violation(valid().seqNr(7).occurredAt(past));

    assertEquals("T-13", e.rule());
    assertEquals(7, e.seqNr().getAsLong());
    assertTrue(e.getMessage().contains("7"), e.getMessage());
  }

  @Test
  void occurredAtOneNanoBeforeMinIsT13() {
    Instant before = Instant.ofEpochSecond(0, Long.MIN_VALUE).minusNanos(1);

    assertEquals("T-13", violation(valid().occurredAt(before)).rule());
  }
}
