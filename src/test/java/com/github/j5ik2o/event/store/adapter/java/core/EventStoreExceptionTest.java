package com.github.j5ik2o.event.store.adapter.java.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.OptionalLong;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import org.junit.jupiter.api.Test;

class EventStoreExceptionTest {

  @Test
  void optimisticLockMessageHasAidSeqNrAndHeadButNotTheCauseText() {
    RuntimeException cause = new RuntimeException("SECRET-SDK-TEXT");
    OptimisticLockException e =
        new OptimisticLockException(AggregateId.of("order", "1"), 7, OptionalLong.of(9), cause);

    assertEquals(ErrorCategory.OPTIMISTIC_LOCK, e.category());
    assertTrue(e.getMessage().contains("order-1"), e.getMessage());
    assertTrue(e.getMessage().contains("7"), e.getMessage());
    assertTrue(e.getMessage().contains("9"), e.getMessage());
    assertFalse(e.getMessage().contains("SECRET-SDK-TEXT"), e.getMessage());
    assertFalse(e.toString().contains("SECRET-SDK-TEXT"), e.toString());
    assertSame(cause, e.getCause());
  }

  @Test
  void optimisticLockWithoutHeadStillWorks() {
    OptimisticLockException e =
        new OptimisticLockException(AggregateId.of("order", "1"), 7, OptionalLong.empty(), null);

    assertTrue(e.getMessage().contains("order-1"), e.getMessage());
    assertTrue(e.getMessage().contains("7"), e.getMessage());
  }

  @Test
  void contractViolationMessageHasRuleAndSeqNrWhenPresent() {
    ContractViolationException e =
        new ContractViolationException("W-9", OptionalLong.of(5), "mismatch");

    assertEquals(ErrorCategory.CONTRACT_VIOLATION, e.category());
    assertEquals("W-9", e.rule());
    assertEquals(5, e.seqNr().getAsLong());
    assertTrue(e.getMessage().contains("W-9"), e.getMessage());
    assertTrue(e.getMessage().contains("5"), e.getMessage());
  }

  @Test
  void contractViolationWithoutSeqNrHasNoSeqNrInMessage() {
    ContractViolationException e =
        new ContractViolationException("T-11", OptionalLong.empty(), "hyphen");

    assertFalse(e.seqNr().isPresent());
    assertTrue(e.getMessage().contains("T-11"), e.getMessage());
    assertFalse(e.getMessage().contains("seq_nr"), e.getMessage());
  }

  @Test
  void serializationExceptionIsSerializationCategoryAndUnchecked() {
    EventStoreException e = new SerializationException("x", new RuntimeException());

    assertEquals(ErrorCategory.SERIALIZATION, e.category());
    assertTrue(e instanceof RuntimeException);
  }

  @Test
  void configurationExceptionIsConfigurationCategoryAndUnchecked() {
    EventStoreException e = new ConfigurationException("x");

    assertEquals(ErrorCategory.CONFIGURATION, e.category());
    assertTrue(e instanceof RuntimeException);
  }

  @Test
  void storageExceptionIsStorageCategoryAndUnchecked() {
    EventStoreException e = new StorageException("x", new RuntimeException());

    assertEquals(ErrorCategory.STORAGE, e.category());
    assertTrue(e instanceof RuntimeException);
  }

  @Test
  void unwrapRemovesNestedCompletionAndExecutionWrappers() {
    ConfigurationException inner = new ConfigurationException("x");
    Throwable wrapped = new CompletionException(new ExecutionException(inner));

    assertSame(inner, EventStoreExceptions.unwrap(wrapped));
  }

  @Test
  void unwrapReturnsOtherThrowablesAsIs() {
    RuntimeException plain = new RuntimeException("x");

    assertSame(plain, EventStoreExceptions.unwrap(plain));
  }
}
