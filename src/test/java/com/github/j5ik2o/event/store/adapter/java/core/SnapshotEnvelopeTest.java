package com.github.j5ik2o.event.store.adapter.java.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SnapshotEnvelopeTest {

  @Test
  void validSnapshotKeepsElementsAndDefaultsManifestToEmpty() {
    SnapshotEnvelope<String> s = SnapshotEnvelope.<String>builder().aggregate("a").seqNr(3).build();

    assertEquals("a", s.aggregate());
    assertEquals(3, s.seqNr());
    assertEquals("", s.manifest());
  }

  @Test
  void missingAggregateIsT10() {
    ContractViolationException e =
        assertThrows(
            ContractViolationException.class,
            () -> SnapshotEnvelope.<String>builder().seqNr(3).build());

    assertEquals("T-10", e.rule());
  }

  @Test
  void missingSeqNrIsT10WithoutSeqNr() {
    ContractViolationException e =
        assertThrows(
            ContractViolationException.class,
            () -> SnapshotEnvelope.<String>builder().aggregate("a").build());

    assertEquals("T-10", e.rule());
    assertFalse(e.seqNr().isPresent());
  }

  @Test
  void seqNrZeroIsAcceptedForSnapshots() {
    assertEquals(0, SnapshotEnvelope.<String>builder().aggregate("a").seqNr(0).build().seqNr());
  }

  @Test
  void negativeSeqNrIsT9() {
    ContractViolationException e =
        assertThrows(
            ContractViolationException.class,
            () -> SnapshotEnvelope.<String>builder().aggregate("a").seqNr(-1).build());

    assertEquals("T-9", e.rule());
    assertTrue(e.getMessage().contains("-1"), e.getMessage());
  }

  @Test
  void readResultExposesSnapshotAndHead() {
    SnapshotEnvelope<String> s = SnapshotEnvelope.<String>builder().aggregate("a").seqNr(2).build();

    SnapshotReadResult<String> with = SnapshotReadResult.of(s, 5);
    SnapshotReadResult<String> without = SnapshotReadResult.withoutSnapshot(4);

    assertEquals(s, with.snapshot().get());
    assertEquals(5, with.headSeqNr());
    assertFalse(without.snapshot().isPresent());
    assertEquals(4, without.headSeqNr());
  }
}
