package com.github.j5ik2o.event.store.adapter.java.dynamodbtest;

import static org.junit.jupiter.api.Assertions.*;

import com.github.j5ik2o.event.store.adapter.java.core.PayloadSerializer;
import com.github.j5ik2o.event.store.adapter.java.core.SerializationException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class FaultPayloadSerializerTest {
  @Test
  void everySerializerPhaseAppliesAtTheSpecifiedBoundary() {
    for (boolean snapshot : new boolean[] {false, true}) {
      for (boolean deserialize : new boolean[] {false, true}) {
        for (FaultRegistry.Injection injection : FaultRegistry.Injection.values()) {
          FaultRegistry registry = new FaultRegistry();
          AtomicInteger calls = new AtomicInteger();
          PayloadSerializer<String> delegate =
              new PayloadSerializer<String>() {
                @Override
                public byte[] serialize(String value) {
                  calls.incrementAndGet();
                  return new byte[] {1};
                }

                @Override
                public String deserialize(byte[] bytes) {
                  calls.incrementAndGet();
                  return "value";
                }
              };
          SerializationException failure = new SerializationException("original");
          String phase =
              (deserialize ? "deserialize-" : "serialize-") + (snapshot ? "snapshot" : "event");
          FaultRegistry.Fault fault =
              registry.register(
                  1, phase, 1, injection, DynamoDbFaultEffects.serializationError(failure));
          FaultPayloadSerializer<String> serializer =
              new FaultPayloadSerializer<>(delegate, registry, snapshot);
          FaultRegistry.Operation first = registry.begin(1, true);

          assertSame(
              failure,
              assertThrows(
                  SerializationException.class,
                  () -> {
                    if (deserialize) serializer.deserialize(new byte[] {1});
                    else serializer.serialize("value");
                  }));
          assertEquals(injection == FaultRegistry.Injection.REPLACE_REQUEST ? 0 : 1, calls.get());
          assertEquals(1, registry.applications(fault));
          assertEquals("passed", registry.finish(first).status);

          FaultRegistry.Operation next = registry.begin(2, true);
          assertArrayEquals(new byte[] {1}, serializer.serialize("value"));
          assertEquals("value", serializer.deserialize(new byte[] {1}));
          assertEquals("passed", registry.finish(next).status);
        }
      }
    }
  }
}
