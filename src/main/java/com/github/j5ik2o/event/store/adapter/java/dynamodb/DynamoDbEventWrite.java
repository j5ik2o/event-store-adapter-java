package com.github.j5ik2o.event.store.adapter.java.dynamodb;

import com.github.j5ik2o.event.store.adapter.java.core.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.dynamodb.model.*;

/** Shared preparation and cancellation classification for both SDK entry points. */
final class DynamoDbEventWrite {
  private DynamoDbEventWrite() {}

  static <P> TransactWriteItemsRequest prepare(
      EventEnvelope<P> event, DynamoDbTableConfig tables, PayloadSerializer<P> serializer) {
    if (event == null) {
      throw new ContractViolationException("T-2", OptionalLong.empty(), "event is required");
    }
    EventStoreInputValidation.checkEvent(event);
    AttributeValue payload = AttributeValue.fromB(serialize(serializer, event.payload()));
    AttributeValue seqNr = AttributeValue.fromN(Long.toString(event.seqNr()));
    Map<String, AttributeValue> envelope = new LinkedHashMap<>();
    envelope.put("seq_nr", seqNr);
    envelope.put(
        "occurred_at", AttributeValue.fromN(Long.toString(epochNanoseconds(event.occurredAt()))));
    envelope.put("manifest", AttributeValue.fromS(event.manifest()));
    envelope.put("payload", payload);
    AttributeValue aid = AttributeValue.fromS(event.aggregateId().asString());
    Map<String, AttributeValue> journal = new LinkedHashMap<>(envelope);
    journal.put("aid", aid);
    AttributeValue events = AttributeValue.fromL(List.of(AttributeValue.fromM(envelope)));
    Map<String, AttributeValue> head =
        Map.of(
            "aid", aid,
            "type_name", AttributeValue.fromS(event.aggregateId().typeName()),
            "seq_nr", seqNr,
            "events", events);
    checkSize(journal, event.seqNr());
    checkSize(head, event.seqNr());

    TransactWriteItem journalPut =
        TransactWriteItem.builder()
            .put(
                Put.builder()
                    .tableName(tables.journalTableName())
                    .item(journal)
                    .conditionExpression("attribute_not_exists(aid)")
                    .build())
            .build();
    TransactWriteItem headWrite;
    if (event.seqNr() == 1) {
      headWrite =
          TransactWriteItem.builder()
              .put(
                  Put.builder()
                      .tableName(tables.headTableName())
                      .item(head)
                      .conditionExpression("attribute_not_exists(aid)")
                      .returnValuesOnConditionCheckFailure(
                          ReturnValuesOnConditionCheckFailure.ALL_OLD)
                      .build())
              .build();
    } else {
      headWrite =
          TransactWriteItem.builder()
              .update(
                  Update.builder()
                      .tableName(tables.headTableName())
                      .key(Map.of("aid", aid))
                      .conditionExpression("seq_nr = :prev")
                      .updateExpression("SET seq_nr = :seq, #events = :events")
                      .expressionAttributeNames(Map.of("#events", "events"))
                      .expressionAttributeValues(
                          Map.of(
                              ":prev",
                              AttributeValue.fromN(Long.toString(event.seqNr() - 1)),
                              ":seq",
                              seqNr,
                              ":events",
                              events))
                      .returnValuesOnConditionCheckFailure(
                          ReturnValuesOnConditionCheckFailure.ALL_OLD)
                      .build())
              .build();
    }
    return TransactWriteItemsRequest.builder().transactItems(journalPut, headWrite).build();
  }

  private static <P> SdkBytes serialize(PayloadSerializer<P> serializer, P payload) {
    try {
      return SdkBytes.fromByteArray(serializer.serialize(payload));
    } catch (SerializationException failure) {
      throw failure;
    } catch (Exception failure) {
      throw new SerializationException("failed to serialize payload", failure);
    }
  }

  private static long epochNanoseconds(Instant instant) {
    long seconds = instant.getEpochSecond();
    return seconds >= 0
        ? Math.addExact(Math.multiplyExact(seconds, 1_000_000_000L), instant.getNano())
        : Math.addExact(
            Math.multiplyExact(seconds + 1, 1_000_000_000L), instant.getNano() - 1_000_000_000L);
  }

  private static void checkSize(Map<String, AttributeValue> item, long seqNr) {
    if (DynamoDbItemSize.estimate(item) > 409600) {
      throw new ContractViolationException(
          "D-7", OptionalLong.of(seqNr), "item exceeds 409600 bytes");
    }
  }

  static EventStoreException classify(EventEnvelope<?> event, Throwable error) {
    Throwable cause = EventStoreExceptions.unwrap(error);
    if (cause instanceof EventStoreException) return (EventStoreException) cause;
    if (cause instanceof TransactionCanceledException) {
      List<CancellationReason> reasons =
          ((TransactionCanceledException) cause).cancellationReasons();
      if (reasons.stream().anyMatch(reason -> "TransactionConflict".equals(reason.code()))) {
        return new OptimisticLockException(
            event.aggregateId(), event.seqNr(), OptionalLong.empty(), cause);
      }
      if (reasons.size() > 1 && "ConditionalCheckFailed".equals(reasons.get(1).code())) {
        if (event.seqNr() == 1) {
          return new OptimisticLockException(
              event.aggregateId(), event.seqNr(), OptionalLong.empty(), cause);
        }
        try {
          long head = oldHeadSeqNr(event, reasons.get(1));
          if (event.seqNr() <= head) {
            return new OptimisticLockException(
                event.aggregateId(), event.seqNr(), OptionalLong.of(head), cause);
          }
          if (event.seqNr() - head >= 2) {
            return new ContractViolationException(
                "W-8", OptionalLong.of(event.seqNr()), "event sequence has a gap");
          }
        } catch (RuntimeException invalidHead) {
          return new StorageException("DynamoDB returned an invalid previous head", cause);
        }
      } else if (!reasons.isEmpty() && "ConditionalCheckFailed".equals(reasons.get(0).code())) {
        return new OptimisticLockException(
            event.aggregateId(), event.seqNr(), OptionalLong.empty(), cause);
      }
    }
    return new StorageException("DynamoDB event write failed", cause);
  }

  private static long oldHeadSeqNr(EventEnvelope<?> event, CancellationReason reason) {
    if (reason.item().isEmpty()) return 0;
    AttributeValue seqNr = reason.item().get("seq_nr");
    if (seqNr == null || seqNr.n() == null)
      throw new IllegalArgumentException("Missing head seq_nr");
    long head = new BigDecimal(seqNr.n()).longValueExact();
    EventStoreInputValidation.checkRead(event.aggregateId(), head);
    return head;
  }
}
