package com.github.j5ik2o.event.store.adapter.java.core;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class EventStoreConfigTest {

  private static final PayloadSerializer<String> P = JsonPayloadSerializer.of(String.class);
  private static final PayloadSerializer<Integer> A = JsonPayloadSerializer.of(Integer.class);

  @Test
  void missingPayloadSerializerIsConfigurationError() {
    assertThrows(
        ConfigurationException.class,
        () -> EventStoreConfig.<String, Integer>builder().snapshotSerializer(A).build());
  }

  @Test
  void missingSnapshotSerializerIsConfigurationError() {
    assertThrows(
        ConfigurationException.class,
        () -> EventStoreConfig.<String, Integer>builder().payloadSerializer(P).build());
  }

  @Test
  void listenerCanBeOmitted() {
    EventStoreConfig<String, Integer> c =
        EventStoreConfig.<String, Integer>builder()
            .payloadSerializer(P)
            .snapshotSerializer(A)
            .build();

    assertFalse(c.retentionFailureListener().isPresent());
  }

  @Test
  void listenerIsKeptWhenGiven() {
    RetentionFailureListener l = f -> {};

    EventStoreConfig<String, Integer> c =
        EventStoreConfig.<String, Integer>builder()
            .payloadSerializer(P)
            .snapshotSerializer(A)
            .retentionFailureListener(l)
            .build();

    assertTrue(c.retentionFailureListener().isPresent());
  }
}
