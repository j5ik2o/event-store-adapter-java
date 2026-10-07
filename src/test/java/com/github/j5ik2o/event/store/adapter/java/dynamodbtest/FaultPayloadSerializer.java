package com.github.j5ik2o.event.store.adapter.java.dynamodbtest;

import com.github.j5ik2o.event.store.adapter.java.core.PayloadSerializer;
import java.util.concurrent.atomic.AtomicLong;

final class FaultPayloadSerializer<T> implements PayloadSerializer<T> {
  private final PayloadSerializer<T> delegate;
  private final FaultRegistry faults;
  private final String serializePhase;
  private final String deserializePhase;
  private final AtomicLong calls = new AtomicLong();

  FaultPayloadSerializer(PayloadSerializer<T> delegate, FaultRegistry faults, boolean snapshot) {
    this.delegate = delegate;
    this.faults = faults;
    serializePhase = snapshot ? "serialize-snapshot" : "serialize-event";
    deserializePhase = snapshot ? "deserialize-snapshot" : "deserialize-event";
  }

  @Override
  public byte[] serialize(T value) {
    FaultRegistry.Selection selected = faults.select(faults.operation(), serializePhase);
    try {
      fail(selected, FaultRegistry.Injection.REPLACE_REQUEST);
      byte[] bytes = delegate.serialize(value);
      fail(selected, FaultRegistry.Injection.REPLACE_RESPONSE);
      return bytes.clone();
    } finally {
      faults.release(selected);
    }
  }

  @Override
  public T deserialize(byte[] bytes) {
    FaultRegistry.Selection selected = faults.select(faults.operation(), deserializePhase);
    try {
      fail(selected, FaultRegistry.Injection.REPLACE_REQUEST);
      T value = delegate.deserialize(bytes.clone());
      fail(selected, FaultRegistry.Injection.REPLACE_RESPONSE);
      return value;
    } finally {
      faults.release(selected);
    }
  }

  private void fail(FaultRegistry.Selection selection, FaultRegistry.Injection injection) {
    if (selection == null || selection.fault.injection != injection) return;
    RuntimeException error = selection.fault.effect.serializationError();
    faults.applied(selection, -calls.incrementAndGet());
    throw error;
  }
}
