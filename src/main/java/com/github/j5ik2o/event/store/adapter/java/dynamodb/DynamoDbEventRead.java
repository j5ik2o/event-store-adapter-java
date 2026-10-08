package com.github.j5ik2o.event.store.adapter.java.dynamodb;

import com.github.j5ik2o.event.store.adapter.java.core.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import software.amazon.awssdk.services.dynamodb.model.*;

/** Shared journal request, restoration and failure classification for both SDK entry points. */
final class DynamoDbEventRead {
  private DynamoDbEventRead() {}

  static QueryRequest prepare(AggregateId id, long seqNr, DynamoDbTableConfig tables) {
    EventStoreInputValidation.checkRead(id, seqNr);
    return QueryRequest.builder()
        .tableName(tables.journalTableName())
        .keyConditionExpression("aid = :aid AND seq_nr >= :seq_nr")
        .expressionAttributeValues(
            Map.of(
                ":aid", AttributeValue.fromS(id.asString()),
                ":seq_nr", AttributeValue.fromN(Long.toString(seqNr))))
        .consistentRead(true)
        .scanIndexForward(true)
        .build();
  }

  static <P> void append(
      QueryResponse response, PayloadSerializer<P> serializer, List<EventEnvelope<P>> events) {
    for (Map<String, AttributeValue> item : response.items()) {
      events.add(restore(item, serializer));
    }
  }

  static <P> EventEnvelope<P> restore(
      Map<String, AttributeValue> item, PayloadSerializer<P> serializer) {
    EventEnvelope<byte[]> stored;
    try {
      String aid = required(item, "aid", AttributeValue.Type.S).s();
      int separator = aid.indexOf('-');
      if (separator < 0) throw new IllegalArgumentException("Invalid stored aid");
      stored =
          EventEnvelope.<byte[]>builder()
              .aggregateId(
                  AggregateId.of(aid.substring(0, separator), aid.substring(separator + 1)))
              .seqNr(integer(item, "seq_nr"))
              .occurredAt(Instant.ofEpochSecond(0, integer(item, "occurred_at")))
              .manifest(required(item, "manifest", AttributeValue.Type.S).s())
              .payload(required(item, "payload", AttributeValue.Type.B).b().asByteArray())
              .build();
    } catch (RuntimeException failure) {
      throw new StorageException("DynamoDB returned an invalid journal item", failure);
    }
    P payload;
    try {
      payload = serializer.deserialize(stored.payload());
      if (payload == null)
        throw new SerializationException("failed to deserialize payload: result is null");
    } catch (SerializationException failure) {
      throw failure;
    } catch (Exception failure) {
      throw new SerializationException("failed to deserialize payload", failure);
    }
    return EventEnvelope.<P>builder()
        .aggregateId(stored.aggregateId())
        .seqNr(stored.seqNr())
        .occurredAt(stored.occurredAt())
        .manifest(stored.manifest())
        .payload(payload)
        .build();
  }

  private static AttributeValue required(
      Map<String, AttributeValue> item, String name, AttributeValue.Type type) {
    AttributeValue value = item.get(name);
    if (value == null || value.type() != type) {
      throw new IllegalArgumentException("Missing or invalid journal attribute: " + name);
    }
    return value;
  }

  private static long integer(Map<String, AttributeValue> item, String name) {
    return new BigDecimal(required(item, name, AttributeValue.Type.N).n()).longValueExact();
  }

  static EventStoreException classify(Throwable failure) {
    Throwable cause = EventStoreExceptions.unwrap(failure);
    if (cause instanceof EventStoreException) return (EventStoreException) cause;
    return new StorageException("DynamoDB event read failed", cause);
  }
}
