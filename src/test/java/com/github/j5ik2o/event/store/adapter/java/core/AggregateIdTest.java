package com.github.j5ik2o.event.store.adapter.java.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class AggregateIdTest {

  private static String repeat(String s, int n) {
    StringBuilder b = new StringBuilder();
    for (int i = 0; i < n; i++) {
      b.append(s);
    }
    return b.toString();
  }

  @Test
  void hyphenInValueIsKept() {
    AggregateId id = AggregateId.of("order", "item-1");

    assertEquals("order-item-1", id.asString());
    assertEquals("order", id.typeName());
    assertEquals("item-1", id.value());
  }

  @Test
  void hyphenInTypeNameIsRejectedWithoutSeqNr() {
    ContractViolationException e =
        assertThrows(ContractViolationException.class, () -> AggregateId.of("order-item", "1"));

    assertEquals("T-11", e.rule());
    assertFalse(e.seqNr().isPresent());
  }

  @Test
  void exactly1024Utf8BytesIsAccepted() {
    AggregateId id = AggregateId.of("型", repeat("あ", 340));

    assertEquals(1024, id.asString().getBytes(StandardCharsets.UTF_8).length);
  }

  @Test
  void utf8Of1025BytesIsRejectedEvenWhenCharacterCountIsSmall() {
    String value = repeat("あ", 340) + "a";

    ContractViolationException e =
        assertThrows(ContractViolationException.class, () -> AggregateId.of("型", value));

    assertEquals("T-12", e.rule());
  }

  @Test
  void emptyTypeNameAndEmptyValueAreAllowed() {
    assertEquals("-x", AggregateId.of("", "x").asString());
    assertEquals("T-", AggregateId.of("T", "").asString());
    assertEquals("-", AggregateId.of("", "").asString());
  }

  @Test
  void equalityAndToStringFollowAsString() {
    AggregateId a = AggregateId.of("order", "1");
    AggregateId b = AggregateId.of("order", "1");

    assertEquals(a, b);
    assertEquals(a.hashCode(), b.hashCode());
    assertEquals("order-1", a.toString());
    assertNotEquals(a, AggregateId.of("order", "2"));
  }
}
