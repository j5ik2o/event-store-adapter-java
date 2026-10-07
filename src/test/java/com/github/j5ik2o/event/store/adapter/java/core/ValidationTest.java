package com.github.j5ik2o.event.store.adapter.java.core;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ValidationTest {

  @Test
  void valueContextAcceptsZeroAndMax() {
    assertDoesNotThrow(() -> Validation.checkSeqNr(0, Validation.Context.VALUE));
    assertDoesNotThrow(() -> Validation.checkSeqNr((1L << 53) - 1, Validation.Context.VALUE));
  }

  @Test
  void valueContextRejectsNegativeAndAboveMaxAsT9WithTheValue() {
    ContractViolationException neg =
        assertThrows(
            ContractViolationException.class,
            () -> Validation.checkSeqNr(-1, Validation.Context.VALUE));
    ContractViolationException above =
        assertThrows(
            ContractViolationException.class,
            () -> Validation.checkSeqNr(1L << 53, Validation.Context.VALUE));

    assertEquals("T-9", neg.rule());
    assertTrue(neg.getMessage().contains("-1"), neg.getMessage());
    assertEquals("T-9", above.rule());
    assertTrue(above.getMessage().contains(String.valueOf(1L << 53)), above.getMessage());
  }

  @Test
  void eventContextRejectsZeroAsW6() {
    ContractViolationException e =
        assertThrows(
            ContractViolationException.class,
            () -> Validation.checkSeqNr(0, Validation.Context.EVENT));

    assertEquals("W-6", e.rule());
  }

  @Test
  void snapshotSeqNrDifferentFromEventSeqNrIsW9WithBothNumbers() {
    ContractViolationException e =
        assertThrows(ContractViolationException.class, () -> Validation.checkSnapshotSeqNr(1, 0));

    assertEquals("W-9", e.rule());
    assertTrue(e.getMessage().contains("1"), e.getMessage());
    assertTrue(e.getMessage().contains("0"), e.getMessage());
  }

  @Test
  void equalSnapshotSeqNrIsAccepted() {
    assertDoesNotThrow(() -> Validation.checkSnapshotSeqNr(3, 3));
  }
}
