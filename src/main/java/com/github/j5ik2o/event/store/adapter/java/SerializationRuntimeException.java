package com.github.j5ik2o.event.store.adapter.java;

public final class SerializationRuntimeException extends EventStoreBaseRuntimeException {
  public SerializationRuntimeException() {
    super();
  }

  public SerializationRuntimeException(String message) {
    super(message);
  }

  public SerializationRuntimeException(String message, Throwable cause) {
    super(message, cause);
  }

  public SerializationRuntimeException(Throwable cause) {
    super(cause);
  }
}
