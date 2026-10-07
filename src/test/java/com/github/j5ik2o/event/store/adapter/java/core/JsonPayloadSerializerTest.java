package com.github.j5ik2o.event.store.adapter.java.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

class JsonPayloadSerializerTest {

  @Test
  void jsonRootNullDeserializesWithoutBeingRejected() {
    PayloadSerializer<String> serializer = JsonPayloadSerializer.of(String.class);

    String result = serializer.deserialize("null".getBytes(StandardCharsets.UTF_8));

    org.junit.jupiter.api.Assertions.assertNull(result);
  }

  @Test
  void roundTripWithClass() {
    PayloadSerializer<List> s = JsonPayloadSerializer.of(List.class);

    assertEquals(List.of("a", "b"), s.deserialize(s.serialize(List.of("a", "b"))));
  }

  @Test
  void roundTripWithMapperAndClass() {
    PayloadSerializer<String> s = JsonPayloadSerializer.of(new ObjectMapper(), String.class);

    assertEquals("x", s.deserialize(s.serialize("x")));
  }

  @Test
  void roundTripWithTypeReference() {
    PayloadSerializer<List<String>> s =
        JsonPayloadSerializer.of(new ObjectMapper(), new TypeReference<List<String>>() {});

    assertEquals(List.of("a"), s.deserialize(s.serialize(List.of("a"))));
  }

  @Test
  void brokenJsonIsSerializationException() {
    PayloadSerializer<List> s = JsonPayloadSerializer.of(List.class);

    assertThrows(
        SerializationException.class,
        () -> s.deserialize("{not json".getBytes(StandardCharsets.UTF_8)));
  }
}
