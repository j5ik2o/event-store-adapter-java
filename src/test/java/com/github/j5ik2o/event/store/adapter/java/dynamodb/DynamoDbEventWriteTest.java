package com.github.j5ik2o.event.store.adapter.java.dynamodb;

import static org.junit.jupiter.api.Assertions.*;

import com.github.j5ik2o.event.store.adapter.java.core.*;
import java.lang.reflect.Field;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.dynamodb.model.*;

class DynamoDbEventWriteTest {
  private static final DynamoDbTableConfig TABLES = DynamoDbTableConfigTest.names().build();
  private static final AggregateId AID = AggregateId.of("A", "x");

  static EventEnvelope<String> event(long seqNr, long nanos) {
    return EventEnvelope.<String>builder()
        .aggregateId(AID)
        .seqNr(seqNr)
        .occurredAt(Instant.ofEpochSecond(0, nanos))
        .payload("payload")
        .build();
  }

  private static PayloadSerializer<String> serializer(byte[] bytes) {
    return new PayloadSerializer<String>() {
      public byte[] serialize(String value) {
        return bytes;
      }

      public String deserialize(byte[] value) {
        throw new AssertionError("Write must not deserialize");
      }
    };
  }

  @Test
  void preparationSerializesOnlyPayloadOnceAndCopiesItForBothItems() {
    byte[] bytes = new byte[] {1, 2, 3};
    AtomicInteger calls = new AtomicInteger();
    PayloadSerializer<String> serializer =
        new PayloadSerializer<String>() {
          public byte[] serialize(String value) {
            assertEquals("payload", value);
            calls.incrementAndGet();
            return bytes;
          }

          public String deserialize(byte[] value) {
            throw new AssertionError("Unexpected read");
          }
        };
    TransactWriteItemsRequest request = DynamoDbEventWrite.prepare(event(1, 1), TABLES, serializer);
    bytes[0] = 9;
    assertEquals(1, calls.get());
    assertEquals(2, request.transactItems().size());
    Put journal = request.transactItems().get(0).put();
    Put head = request.transactItems().get(1).put();
    assertEquals("journal", journal.tableName());
    assertEquals("head", head.tableName());
    assertEquals("attribute_not_exists(aid)", journal.conditionExpression());
    assertEquals("attribute_not_exists(aid)", head.conditionExpression());
    assertEquals(
        ReturnValuesOnConditionCheckFailure.ALL_OLD, head.returnValuesOnConditionCheckFailure());
    assertEquals(
        Set.of("aid", "seq_nr", "occurred_at", "manifest", "payload"), journal.item().keySet());
    assertEquals(Set.of("aid", "type_name", "seq_nr", "events"), head.item().keySet());
    assertEquals(1, head.item().get("events").l().size());
    Map<String, AttributeValue> envelope = head.item().get("events").l().get(0).m();
    assertEquals(Set.of("seq_nr", "occurred_at", "manifest", "payload"), envelope.keySet());
    assertEquals("", journal.item().get("manifest").s());
    assertEquals("1", journal.item().get("occurred_at").n());
    assertArrayEquals(new byte[] {1, 2, 3}, journal.item().get("payload").b().asByteArray());
    envelope.forEach((name, value) -> assertEquals(journal.item().get(name), value));
  }

  @Test
  void updateReplacesTheSingleEventAndPreservesMaximumSequence() {
    long seq = (1L << 53) - 1;
    Update head =
        DynamoDbEventWrite.prepare(event(seq, Long.MIN_VALUE), TABLES, serializer(new byte[0]))
            .transactItems()
            .get(1)
            .update();
    assertEquals(Map.of("aid", AttributeValue.fromS("A-x")), head.key());
    assertEquals("seq_nr = :prev", head.conditionExpression());
    assertEquals(Long.toString(seq - 1), head.expressionAttributeValues().get(":prev").n());
    assertEquals(Long.toString(seq), head.expressionAttributeValues().get(":seq").n());
    assertEquals("SET seq_nr = :seq, #events = :events", head.updateExpression());
    assertEquals(Map.of("#events", "events"), head.expressionAttributeNames());
    assertEquals(
        ReturnValuesOnConditionCheckFailure.ALL_OLD, head.returnValuesOnConditionCheckFailure());
    assertEquals(1, head.expressionAttributeValues().get(":events").l().size());
  }

  @Test
  void timestampConversionKeepsSignedNanosecondBoundaries() {
    for (long nanos :
        List.of(
            Long.MIN_VALUE, Long.MIN_VALUE + 1, -1L, 0L, 1L, Long.MAX_VALUE - 1, Long.MAX_VALUE)) {
      Map<String, AttributeValue> item =
          DynamoDbEventWrite.prepare(event(1, nanos), TABLES, serializer(new byte[0]))
              .transactItems()
              .get(0)
              .put()
              .item();
      assertEquals(Long.toString(nanos), item.get("occurred_at").n());
    }
  }

  @Test
  void invalidInputsAreRejectedBeforeSerialization() throws Exception {
    PayloadSerializer<String> forbidden =
        new PayloadSerializer<String>() {
          public byte[] serialize(String value) {
            throw new AssertionError("Invalid input serialized");
          }

          public String deserialize(byte[] value) {
            throw new AssertionError();
          }
        };
    assertEquals(
        "T-2",
        assertThrows(
                ContractViolationException.class,
                () -> DynamoDbEventWrite.prepare(null, TABLES, forbidden))
            .rule());
    for (long seq : List.of(-1L, 0L, 1L << 53)) {
      EventEnvelope<String> invalid = event(1, 1);
      set(invalid, "seqNr", seq);
      assertEquals(
          seq == 0 ? "W-6" : "T-9",
          assertThrows(
                  ContractViolationException.class,
                  () -> DynamoDbEventWrite.prepare(invalid, TABLES, forbidden))
              .rule());
    }
    for (Instant time :
        List.of(
            Instant.ofEpochSecond(0, Long.MIN_VALUE).minusNanos(1),
            Instant.ofEpochSecond(0, Long.MAX_VALUE).plusNanos(1))) {
      EventEnvelope<String> invalid = event(1, 1);
      set(invalid, "occurredAt", time);
      assertEquals(
          "T-13",
          assertThrows(
                  ContractViolationException.class,
                  () -> DynamoDbEventWrite.prepare(invalid, TABLES, forbidden))
              .rule());
    }
  }

  private static void set(Object target, String name, Object value) throws Exception {
    Field field = target.getClass().getDeclaredField(name);
    field.setAccessible(true);
    field.set(target, value);
  }

  @Test
  void serializationFailuresAreClassifiedIncludingNullResult() {
    SerializationException classified = new SerializationException("serializer failure");
    for (RuntimeException error :
        List.of(classified, new IllegalStateException("serializer failure"))) {
      PayloadSerializer<String> failing =
          new PayloadSerializer<String>() {
            public byte[] serialize(String value) {
              throw error;
            }

            public String deserialize(byte[] value) {
              throw new AssertionError();
            }
          };
      SerializationException failure =
          assertThrows(
              SerializationException.class,
              () -> DynamoDbEventWrite.prepare(event(1, 1), TABLES, failing));
      if (error == classified) assertSame(classified, failure);
      else assertSame(error, failure.getCause());
    }
    assertThrows(
        SerializationException.class,
        () -> DynamoDbEventWrite.prepare(event(1, 1), TABLES, serializer(null)));
  }

  @Test
  void headBoundaryAcceptsExactlyTheLimitAndRejectsOneMoreForPutAndUpdate() {
    for (long seq : List.of(1L, 2L)) {
      assertDoesNotThrow(
          () ->
              DynamoDbEventWrite.prepare(event(seq, 1), TABLES, serializer(new byte[409600 - 77])));
      for (int bytes : List.of(409601 - 77, 409600 - 42, 409601 - 42)) {
        assertEquals(
            "D-7",
            assertThrows(
                    ContractViolationException.class,
                    () ->
                        DynamoDbEventWrite.prepare(
                            event(seq, 1), TABLES, serializer(new byte[bytes])))
                .rule());
      }
    }
  }

  private static CancellationReason reason(String code, Map<String, AttributeValue> item) {
    return CancellationReason.builder().code(code).item(item).build();
  }

  private static EventStoreException classify(long seq, CancellationReason... reasons) {
    return DynamoDbEventWrite.classify(
        event(seq, 1),
        new CompletionException(
            TransactionCanceledException.builder()
                .message("raw SDK text")
                .cancellationReasons(reasons)
                .build()));
  }

  @Test
  void cancellationPriorityIsConflictThenHeadThenJournalThenStorage() {
    CancellationReason gap =
        reason("ConditionalCheckFailed", Map.of("seq_nr", AttributeValue.fromN("1")));
    assertInstanceOf(
        OptimisticLockException.class, classify(3, reason("TransactionConflict", Map.of()), gap));
    assertInstanceOf(
        OptimisticLockException.class,
        classify(
            3,
            reason("ConditionalCheckFailed", Map.of()),
            reason("TransactionConflict", Map.of())));
    assertEquals(
        "W-8",
        ((ContractViolationException) classify(3, reason("ConditionalCheckFailed", Map.of()), gap))
            .rule());
    assertInstanceOf(
        OptimisticLockException.class,
        classify(
            3, reason("ConditionalCheckFailed", Map.of()), reason("ThrottlingError", Map.of())));
    assertInstanceOf(
        StorageException.class,
        classify(3, reason("ThrottlingError", Map.of()), reason("None", Map.of())));
    assertInstanceOf(StorageException.class, classify(3));
  }

  @Test
  void headUsesReturnedOldItemAndMissingItemMeansHeadZero() {
    CancellationReason none = reason("None", Map.of());
    for (long seq : List.of(1L, 2L, 3L)) {
      EventStoreException failure =
          classify(
              seq,
              none,
              reason("ConditionalCheckFailed", Map.of("seq_nr", AttributeValue.fromN("3.00"))));
      assertInstanceOf(OptimisticLockException.class, failure);
      assertFalse(failure.getMessage().contains("raw SDK text"));
    }
    for (Map<String, AttributeValue> head :
        List.of(Map.<String, AttributeValue>of(), Map.of("seq_nr", AttributeValue.fromN("1")))) {
      ContractViolationException failure =
          (ContractViolationException) classify(3, none, reason("ConditionalCheckFailed", head));
      assertEquals("W-8", failure.rule());
      assertEquals(3, failure.seqNr().getAsLong());
      assertTrue(failure.getMessage().contains("W-8"));
      assertTrue(failure.getMessage().contains("3"));
    }
    assertInstanceOf(
        StorageException.class,
        classify(
            2,
            none,
            reason("ConditionalCheckFailed", Map.of("seq_nr", AttributeValue.fromN("1")))));
  }

  @Test
  void malformedOldHeadsAreStorageInsteadOfBeingTreatedAsAbsent() {
    List<Map<String, AttributeValue>> heads = new ArrayList<>();
    heads.add(Map.of("aid", AttributeValue.fromS("A-x")));
    for (AttributeValue value :
        List.of(
            AttributeValue.fromS("1"),
            AttributeValue.fromN("1.5"),
            AttributeValue.fromN("x"),
            AttributeValue.fromN("-1"),
            AttributeValue.fromN("9007199254740992"))) {
      heads.add(Map.of("seq_nr", value));
    }
    for (Map<String, AttributeValue> head : heads) {
      assertInstanceOf(
          StorageException.class,
          classify(3, reason("None", Map.of()), reason("ConditionalCheckFailed", head)));
    }
    StorageException error = new StorageException("classified");
    assertSame(error, DynamoDbEventWrite.classify(event(1, 1), new CompletionException(error)));
  }
}
