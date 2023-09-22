package com.github.j5ik2o.event.store.adapter.java;

public final class DeserializationRuntimeException extends EventStoreBaseRuntimeException {
  public DeserializationRuntimeException() {
    super();
  }

  public DeserializationRuntimeException(String message) {
    super(message);
  }

  public DeserializationRuntimeException(String message, Throwable cause) {
    super(message, cause);
  }

  public DeserializationRuntimeException(Throwable cause) {
    super(cause);
  }
}
